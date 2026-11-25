import { mkdirSync, rmSync } from 'node:fs'
import path from 'node:path'
import type { Browser, BrowserContext, Locator, Page } from '@playwright/test'
import {
  FE, RUN, ROLE_ID, apiAs, createActor, createTeam, joinAsMember, must, openAs, sql, expect,
  readAssets, type Actor, type Scope,
} from './helpers/n1p1-world'
import { test } from '@playwright/test'

/**
 * 戦役 CMP-261123-1821「シフト枠の割当で他チームのユーザーを自チームの枠に追加できる」是正（PR #4810）の実機E2E。
 *
 * 入口: /teams/{slug}/shifts/{scheduleId}/board（pages/teams/[slug]/shifts/[scheduleId]/board.vue）。
 * 対象操作（割当の追加）は必ず画面の「+ 追加」ボタン→「メンバーを追加」ダイアログ、または未割当メンバー欄からのドラッグ＆ドロップで行う。
 * API を使うのはログイン・前提データ（利用者・チーム・所属・ポジション・シフト表・枠）の作成・後始末と、
 * 見える／見えないの裏取り・画面から到達できない攻撃（他チーム利用者 ID を混ぜた PATCH）の SHIFT_049 確認だけ。
 *
 * AC-1  権限あり（チーム ADMIN・DEPUTY_ADMIN）: URL 直打ちでボードが開き、未割当に正規メンバーだけが並ぶ
 *       （SUPPORTER・他チーム利用者・非所属は並ばない）。ダイアログとドラッグ＆ドロップで割り当てられ、再読込み後も残る
 * AC-2  他チームの slug の URL でシフト表を開くと、存在しない画面として扱われる（不一致検出）。題名・他チームのメンバーが出ない
 * AC-3  画面から割当中に、割当先が不適格になっていた（降格）場合は SHIFT_049 で拒否され、枠に残らない
 * ROLE  権限なし（同チーム MEMBER・SUPPORTER・部外者）と他テナント（別チームの ADMIN）: URL 直打ちの見え方と、画面からの送信の結果
 * API   画面から到達できない攻撃（追加・置換・移動に他チーム利用者・SUPPORTER・不在 ID を混ぜる）は SHIFT_049。応答は同一
 *
 * 実行（cd frontend）:
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8080 \
 *     npx playwright test -c playwright.cloud.local.config.ts tests/e2e/real/cmp-261123-1821-shift-assign-authz.real.spec.ts
 * 後始末: 作成したシフト表・ポジションと、記録したチームを afterAll で API の DELETE で消し、残件 0 を SQL（読み取りのみ）で確かめる。
 * 利用者はテスト専用ドメインのまま残る（削除 API が無い。他の real spec と同じ）。
 */
const EVID_DIR = path.resolve('..', 'docs', 'evidence', 'CMP-261123-1821')
const LEDGER = path.resolve('tests', 'e2e', '.auth', 'cmp261123-1821-assets.json')
const TAG = `R${RUN}`

interface World {
  admin: Actor      // チーム A の ADMIN（作成者）
  deputy: Actor     // チーム A の DEPUTY_ADMIN（シフト管理者）
  member: Actor     // チーム A の MEMBER（割当対象）
  member2: Actor    // チーム A の MEMBER（割当対象。ドラッグ＆ドロップ用）
  member3: Actor    // チーム A の MEMBER（降格される。AC-3 用）
  supporter: Actor  // チーム A の SUPPORTER（割当対象外）
  outsider: Actor   // どのチームにも属さない
  bAdmin: Actor     // チーム B（別テナント）の ADMIN
  bMember: Actor    // チーム B の MEMBER（他チーム利用者。混入の標的）
  dual: Actor       // チーム A の DEPUTY_ADMIN かつ チーム B の MEMBER（slug 不一致の検出用）
  teamA: Scope
  teamB: Scope
  positionA: number
  positionB: number
  scheduleA: number
  scheduleB: number
  slotA1: number
  slotA2: number
  slotA3: number
  slotB1: number
}
let W: World
const sessions = new Map<string, { context: BrowserContext; page: Page }>()
const createdSchedules: Array<{ id: number; owner: Actor }> = []
const createdPositions: Array<{ id: number; owner: Actor }> = []

async function sessionOf(browser: Browser, actor: Actor) {
  const hit = sessions.get(actor.email)
  if (hit) return hit.page
  const { context, page } = await openAs(browser, actor)
  sessions.set(actor.email, { context, page })
  return page
}

const boardUrl = (slug: string, scheduleId: number) => `/teams/${slug}/shifts/${scheduleId}/board`
const SLOT_DATE = ['2027-01-12', '2027-01-13', '2027-01-14']

/** 招待で参加させ、特権ロール（招待では付与できない）は参加後にチーム管理者が変更する。 */
async function joinWithRole(admin: Actor, teamSlug: string, user: Actor, roleId: number) {
  if (roleId === ROLE_ID.ADMIN || roleId === ROLE_ID.DEPUTY_ADMIN) {
    await joinAsMember('teams', admin, teamSlug, user)
    await must(admin, 'PATCH', `/api/v1/teams/${teamSlug}/members/${user.id}/role`, { roleId })
    return
  }
  if (roleId === ROLE_ID.SUPPORTER) {
    // 実運用の SUPPORTER はフォロー（サポーター申請）で入る。招待で SUPPORTER を付与すると membership が MEMBER になり別物の状態になる。
    await must(user, 'POST', `/api/v1/teams/${teamSlug}/follow`)
    return
  }
  throw new Error(`未対応のロール ${roleId}`)
}

async function slotVersion(actor: Actor, scheduleId: number, slotId: number): Promise<{ version: number; assigned: number[] }> {
  const r = await must(actor, 'GET', `/api/v1/shifts/schedules/${scheduleId}/slots`)
  const s = (r.data as Array<{ id: number; version: number; assignedUserIds: number[] }>).find((x) => x.id === slotId)
  if (!s) throw new Error(`枠 ${slotId} が見つからない`)
  return { version: s.version, assigned: s.assignedUserIds }
}

/** ボード画面を URL 直打ちで開き、取得 API（シフト表）の応答状態を返す。 */
async function openBoard(page: Page, slug: string, scheduleId: number): Promise<number> {
  const resP = page.waitForResponse(
    (r) => r.request().method() === 'GET' && new URL(r.url()).pathname === `/api/v1/shifts/schedules/${scheduleId}`,
    { timeout: 120_000 },
  )
  await page.goto(`${FE}${boardUrl(slug, scheduleId)}`, { waitUntil: 'commit' })
  const res = await resP
  return res.status()
}

/** ボードが描画され、枠の表（または空表示）とメンバー欄が確定するまで待つ。 */
async function waitBoardReady(page: Page) {
  await expect(page.getByRole('heading', { name: 'シフトボード' }).or(page.getByRole('heading').first())).toBeVisible({ timeout: 60_000 })
  await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 60_000 })
}

const poolItems = (page: Page) => page.locator('[draggable="true"]')
const slotRow = (page: Page, index: number): Locator => page.locator('tbody tr').nth(index)
const addButton = (row: Locator) => row.getByRole('button', { name: /^\+/ })

async function poolNames(page: Page): Promise<string[]> {
  await page.waitForTimeout(500)
  return (await poolItems(page).locator('p.font-medium').allTextContents()).map((s) => s.trim())
}

function patchAssignmentsResponse(page: Page) {
  return page.waitForResponse((r) => r.request().method() === 'PATCH' && /\/api\/v1\/shifts\/slots\/\d+\/assignments$/.test(new URL(r.url()).pathname))
}

test.describe.configure({ mode: 'serial' })

test.beforeAll(async () => {
  test.setTimeout(600_000)
  mkdirSync(EVID_DIR, { recursive: true })
  const admin = await createActor('c1821adm')
  const deputy = await createActor('c1821dep')
  const member = await createActor('c1821mem')
  const member2 = await createActor('c1821mm2')
  const member3 = await createActor('c1821mm3')
  const supporter = await createActor('c1821sup')
  const outsider = await createActor('c1821out')
  const bAdmin = await createActor('c1821badm')
  const bMember = await createActor('c1821bmem')
  const dual = await createActor('c1821dual')
  const teamA = await createTeam(admin, `1821チームA${TAG}`, 'PUBLIC', LEDGER)
  const teamB = await createTeam(bAdmin, `1821チームB${TAG}`, 'PUBLIC', LEDGER)
  await joinWithRole(admin, teamA.slug, deputy, ROLE_ID.DEPUTY_ADMIN)
  await joinAsMember('teams', admin, teamA.slug, member)
  await joinAsMember('teams', admin, teamA.slug, member2)
  await joinAsMember('teams', admin, teamA.slug, member3)
  // サポーター受け入れを有効にしてから、実運用どおりフォロー（サポーター申請）で SUPPORTER になる
  const teamRead = await must(admin, 'GET', `/api/v1/teams/${teamA.slug}`)
  const teamVersion = teamRead.data.metadata.version
  await must(admin, 'PATCH', `/api/v1/teams/${teamA.slug}`, { supporterEnabled: true, version: teamVersion })
  await joinWithRole(admin, teamA.slug, supporter, ROLE_ID.SUPPORTER)
  await joinWithRole(admin, teamA.slug, dual, ROLE_ID.DEPUTY_ADMIN)
  await joinAsMember('teams', bAdmin, teamB.slug, bMember)
  await joinAsMember('teams', bAdmin, teamB.slug, dual)

  const posA = await must(admin, 'POST', `/api/v1/shifts/positions?teamId=${teamA.numericId}`, { name: `受付${TAG}`, displayOrder: 1 })
  const posB = await must(bAdmin, 'POST', `/api/v1/shifts/positions?teamId=${teamB.numericId}`, { name: `B受付${TAG}`, displayOrder: 1 })
  const schA = await must(admin, 'POST', `/api/v1/shifts/schedules?teamId=${teamA.numericId}`, { title: `1821シフトA${TAG}`, startDate: '2027-01-11', endDate: '2027-01-17' })
  const schB = await must(bAdmin, 'POST', `/api/v1/shifts/schedules?teamId=${teamB.numericId}`, { title: `1821シフトB${TAG}`, startDate: '2027-01-11', endDate: '2027-01-17' })
  const slotOf = async (owner: Actor, scheduleId: number, positionId: number, date: string) =>
    (await must(owner, 'POST', `/api/v1/shifts/schedules/${scheduleId}/slots`, {
      slotDate: date, startTime: '09:00:00', endTime: '12:00:00', positionId, requiredCount: 6, note: null,
    })).data.id as number
  const slotA1 = await slotOf(admin, schA.data.id, posA.data.id, SLOT_DATE[0]!)
  const slotA2 = await slotOf(admin, schA.data.id, posA.data.id, SLOT_DATE[1]!)
  const slotA3 = await slotOf(admin, schA.data.id, posA.data.id, SLOT_DATE[2]!)
  const slotB1 = await slotOf(bAdmin, schB.data.id, posB.data.id, SLOT_DATE[0]!)
  createdSchedules.push({ id: schA.data.id, owner: admin }, { id: schB.data.id, owner: bAdmin })
  createdPositions.push({ id: posA.data.id, owner: admin }, { id: posB.data.id, owner: bAdmin })
  // 権限なしの者にも見える状態（調整中）まで進める。公開（PUBLISHED）は予算連動の検査があるため使わない。
  for (const [owner, id] of [[admin, schA.data.id], [bAdmin, schB.data.id]] as const) {
    for (const status of ['COLLECTING', 'ADJUSTING']) {
      await must(owner, 'POST', `/api/v1/shifts/schedules/${id}/transition?status=${status}`)
    }
  }
  W = {
    admin, deputy, member, member2, member3, supporter, outsider, bAdmin, bMember, dual, teamA, teamB,
    positionA: posA.data.id, positionB: posB.data.id, scheduleA: schA.data.id, scheduleB: schB.data.id,
    slotA1, slotA2, slotA3, slotB1,
  }
})

test.afterAll(async () => {
  test.setTimeout(300_000)
  for (const { context } of sessions.values()) {
    try {
      await context.close()
    } catch (error) {
      console.log(`[cmp-261123-1821] context close 失敗: ${String(error)}`)
    }
  }
  const failures: string[] = []
  for (const s of [...createdSchedules].reverse()) {
    const r = await apiAs(s.owner, 'DELETE', `/api/v1/shifts/schedules/${s.id}`)
    if (![200, 204, 404].includes(r.status)) failures.push(`schedule ${s.id} -> ${r.status}`)
  }
  for (const p of [...createdPositions].reverse()) {
    const r = await apiAs(p.owner, 'DELETE', `/api/v1/shifts/positions/${p.id}`)
    if (![200, 204, 404].includes(r.status)) failures.push(`position ${p.id} -> ${r.status}`)
  }
  for (const record of [...readAssets(LEDGER)].reverse()) {
    const r = await apiAs(record.owner, 'DELETE', record.path)
    if (![200, 204, 404].includes(r.status)) failures.push(`${record.label} -> ${r.status}`)
  }
  rmSync(LEDGER, { force: true })
  // 残件確認（読み取りのみ）: この実行の RUN タグのチーム・その配下のシフト表・ポジションが残っていないこと
  const teamIds = sql(`SELECT id FROM teams WHERE name LIKE '%${TAG}%';`).split('\n').filter(Boolean)
  const teams = Number(sql(`SELECT COUNT(*) FROM teams WHERE deleted_at IS NULL AND name LIKE '%${TAG}%';`))
  const inList = teamIds.length ? teamIds.join(',') : '0'
  const schedules = Number(sql(`SELECT COUNT(*) FROM shift_schedules WHERE deleted_at IS NULL AND title LIKE '%${TAG}%';`))
  const slots = Number(sql(`SELECT COUNT(*) FROM shift_slots s JOIN shift_schedules h ON h.id=s.schedule_id WHERE s.deleted_at IS NULL AND h.deleted_at IS NULL AND h.title LIKE '%${TAG}%';`))
  const positions = Number(sql(`SELECT COUNT(*) FROM shift_positions WHERE name LIKE '%${TAG}%' AND team_id IN (${inList});`))
  console.log(`[cmp-261123-1821] 後始末 残件: チーム=${teams} シフト表=${schedules} 枠=${slots} ポジション=${positions} 失敗=${failures.length}`)
  expect(failures, `削除失敗: ${failures.join(' / ')}`).toEqual([])
  expect(teams, '残ったチーム').toBe(0)
  expect(schedules, '残ったシフト表').toBe(0)
  expect(slots, '残った枠').toBe(0)
  expect(positions, '残ったポジション').toBe(0)
})

test('AC-1 権限あり: チーム ADMIN が URL 直打ちでボードを開く。未割当には正規メンバーだけが並ぶ', async ({ browser }) => {
  test.setTimeout(300_000)
  const page = await sessionOf(browser, W.admin)
  expect(await openBoard(page, W.teamA.slug, W.scheduleA)).toBe(200)
  await waitBoardReady(page)
  const raw = await must(W.admin, 'GET', `/api/v1/teams/${W.teamA.slug}/members?size=200`)
  const roleOf = (a: Actor) => (raw.data as Array<{ userId: number; roleName: string }>).find((m) => m.userId === a.id)?.roleName
  expect(roleOf(W.supporter), '前提: SUPPORTER は SUPPORTER として一覧に載る').toBe('SUPPORTER')
  const names = await poolNames(page)
  console.log(`[cmp-261123-1821] ADMIN の未割当一覧: ${JSON.stringify(names)}`)
  for (const ok of [W.admin, W.deputy, W.member, W.member2, W.member3, W.dual]) {
    expect(names, `正規メンバー ${ok.label} が並ぶ`).toContain(ok.nickname)
  }
  for (const ng of [W.supporter, W.bMember, W.bAdmin, W.outsider]) {
    expect(names, `${ng.label} は並ばない`).not.toContain(ng.nickname)
  }
  await page.screenshot({ path: path.join(EVID_DIR, 'ac1-admin-board.png') })
})

// ---- 画面操作の部品 --------------------------------------------------------------------

const dialogOf = (page: Page) => page.getByRole('dialog')
const chipIn = (row: Locator, actor: Actor) => row.locator('.rounded-full', { hasText: actor.nickname })
const cellOf = (row: Locator) => row.locator('td').nth(1).locator('div.rounded-lg').first()

/** 枠の「+」→「メンバーを追加」ダイアログで利用者を選んで割り当てる。PATCH の応答を返す。 */
async function addViaDialog(page: Page, rowIndex: number, actor: Actor) {
  await addButton(slotRow(page, rowIndex)).click()
  const dialog = dialogOf(page)
  await expect(dialog).toBeVisible()
  const [res] = await Promise.all([patchAssignmentsResponse(page), dialog.getByText(actor.nickname, { exact: true }).click()])
  return res
}

/** 未割当メンバー欄からドラッグ＆ドロップで枠へ割り当てる。PATCH の応答を返す。 */
async function dropFromPool(page: Page, rowIndex: number, actor: Actor) {
  const [res] = await Promise.all([
    patchAssignmentsResponse(page),
    poolItems(page).filter({ hasText: actor.nickname }).dragTo(cellOf(slotRow(page, rowIndex))),
  ])
  return res
}

async function assignedOf(slotId: number): Promise<number[]> {
  return (await slotVersion(W.admin, W.scheduleA, slotId)).assigned
}

async function reloadBoard(page: Page, slug = W.teamA.slug, scheduleId = W.scheduleA) {
  expect(await openBoard(page, slug, scheduleId)).toBe(200)
  await waitBoardReady(page)
}

// ---- 権限あり: 追加・移動 ------------------------------------------------------------------

test('AC-1 権限あり ADMIN: ダイアログに正規メンバーだけが出て、画面で割り当てられ、再読込み後も残る', async ({ browser }) => {
  test.setTimeout(300_000)
  const page = await sessionOf(browser, W.admin)
  await addButton(slotRow(page, 0)).click()
  const dialog = dialogOf(page)
  await expect(dialog).toBeVisible()
  const text = await dialog.innerText()
  for (const ok of [W.member, W.member2, W.deputy]) expect(text, `ダイアログに ${ok.label}`).toContain(ok.nickname)
  for (const ng of [W.supporter, W.bMember, W.bAdmin, W.outsider]) expect(text, `ダイアログに ${ng.label} は出ない`).not.toContain(ng.nickname)
  await page.screenshot({ path: path.join(EVID_DIR, 'ac1-admin-add-dialog.png') })
  const [res] = await Promise.all([patchAssignmentsResponse(page), dialog.getByText(W.member.nickname, { exact: true }).click()])
  expect(res.status(), '正規メンバーの追加は 200').toBe(200)
  await expect(dialog).toBeHidden()
  await expect(chipIn(slotRow(page, 0), W.member)).toBeVisible()
  expect(await poolNames(page), '割当済みは未割当から消える').not.toContain(W.member.nickname)
  await reloadBoard(page)
  await expect(chipIn(slotRow(page, 0), W.member)).toBeVisible()
  expect(await assignedOf(W.slotA1)).toEqual([W.member.id])
  await page.screenshot({ path: path.join(EVID_DIR, 'ac1-admin-added.png') })
})

test('AC-1 権限あり ADMIN: 未割当欄からのドラッグ＆ドロップで割り当てられ、再読込み後も残る', async ({ browser }) => {
  test.setTimeout(300_000)
  const page = await sessionOf(browser, W.admin)
  const res = await dropFromPool(page, 1, W.member2)
  expect(res.status(), 'D&D の追加は 200').toBe(200)
  await expect(chipIn(slotRow(page, 1), W.member2)).toBeVisible()
  await reloadBoard(page)
  await expect(chipIn(slotRow(page, 1), W.member2)).toBeVisible()
  expect(await assignedOf(W.slotA2)).toEqual([W.member2.id])
})

test('AC-1 権限あり ADMIN: 割当の移動（枠 2 → 枠 3）が画面でできる（取り外して別の枠へ付け直す）', async ({ browser }) => {
  test.setTimeout(300_000)
  const page = await sessionOf(browser, W.admin)
  const row1 = slotRow(page, 1)
  const [removed] = await Promise.all([patchAssignmentsResponse(page), chipIn(row1, W.member2).getByRole('button', { name: '削除' }).click()])
  expect(removed.status()).toBe(200)
  await expect(chipIn(row1, W.member2)).toHaveCount(0)
  const added = await addViaDialog(page, 2, W.member2)
  expect(added.status()).toBe(200)
  await expect(chipIn(slotRow(page, 2), W.member2)).toBeVisible()
  await reloadBoard(page)
  await expect(chipIn(slotRow(page, 2), W.member2)).toBeVisible()
  expect(await assignedOf(W.slotA2)).toEqual([])
  expect(await assignedOf(W.slotA3)).toEqual([W.member2.id])
  await page.screenshot({ path: path.join(EVID_DIR, 'ac1-admin-moved.png') })
})

test('AC-1 権限あり ADMIN: 割当済みのチップを別の枠へドラッグして移動できる（枠間ドラッグの導線）', async ({ browser }) => {
  test.setTimeout(300_000)
  // 既知の欠陥の固定: ShiftMemberChip に draggable が無く、ShiftBoardMatrix は drop を常に fromSlotId=null で通知する。
  // useShiftBoard.moveUser / board.vue の onDropUser(fromSlotId!==null) は画面から呼ばれない（到達できない）。
  // FE が枠間ドラッグを実装したら本 fail を外す。
  test.fail(true, 'DEFECT: 割当済みチップを別の枠へドラッグして移動する導線が無い（ShiftMemberChip に draggable 無し／Matrix は fromSlotId を常に null で通知）')
  const page = await sessionOf(browser, W.admin)
  await reloadBoard(page)
  const chip = chipIn(slotRow(page, 2), W.member2)
  await expect(chip).toBeVisible()
  await chip.dragTo(cellOf(slotRow(page, 0)), { timeout: 10_000 })
  await page.waitForTimeout(1_500)
  expect(await assignedOf(W.slotA1), 'ドラッグで枠 1 へ移っている').toContain(W.member2.id)
  expect(await assignedOf(W.slotA3), 'ドラッグで枠 3 から外れている').not.toContain(W.member2.id)
})

test('AC-1 権限あり DEPUTY_ADMIN（シフト管理者）: URL 直打ちでボードが開き、画面で割り当てられ、他チーム利用者・SUPPORTER は並ばない', async ({ browser }) => {
  test.setTimeout(300_000)
  const page = await sessionOf(browser, W.deputy)
  await reloadBoard(page)
  const names = await poolNames(page)
  for (const ng of [W.supporter, W.bMember, W.bAdmin, W.outsider]) expect(names, `${ng.label} は並ばない`).not.toContain(ng.nickname)
  expect(names).toContain(W.member3.nickname)
  const res = await addViaDialog(page, 0, W.dual)
  expect(res.status()).toBe(200)
  await expect(chipIn(slotRow(page, 0), W.dual)).toBeVisible()
  await reloadBoard(page)
  await expect(chipIn(slotRow(page, 0), W.dual)).toBeVisible()
  expect((await assignedOf(W.slotA1)).sort()).toEqual([W.member.id, W.dual.id].sort())
  await page.screenshot({ path: path.join(EVID_DIR, 'ac1-deputy-board.png') })
})

// ---- 画面中に割当先が不適格になった場合 ------------------------------------------------------

test('AC-3 画面を開いている間に割当先がチームから外れた場合、画面からの追加は SHIFT_049 で拒否され枠に残らない', async ({ browser }) => {
  test.setTimeout(300_000)
  const page = await sessionOf(browser, W.admin)
  await reloadBoard(page)
  expect(await poolNames(page), '開いた時点では member3 は正規メンバーとして並ぶ').toContain(W.member3.nickname)
  // 前提操作: 画面を開いたまま、別の管理操作で member3 をチームから外す（古い一覧が残った状態を作る）
  await must(W.admin, 'DELETE', `/api/v1/teams/${W.teamA.slug}/members/${W.member3.id}`, undefined, [200, 204])
  await addButton(slotRow(page, 1)).click()
  const dialog = dialogOf(page)
  await expect(dialog.getByText(W.member3.nickname, { exact: true })).toBeVisible()
  const [res] = await Promise.all([patchAssignmentsResponse(page), dialog.getByText(W.member3.nickname, { exact: true }).click()])
  const body = await res.json()
  console.log(`[cmp-261123-1821] 除名済みメンバーの画面追加: status=${res.status()} code=${body?.error?.code} msg=${body?.error?.message}`)
  expect(body?.error?.code, '拒否コード').toBe('SHIFT_049')
  await expect(page.getByText('割り当てできないメンバーが含まれています').first()).toBeVisible({ timeout: 15_000 })
  await page.screenshot({ path: path.join(EVID_DIR, 'ac3-stale-member-rejected.png') })
  await expect(chipIn(slotRow(page, 1), W.member3)).toHaveCount(0)
  expect(await assignedOf(W.slotA2)).toEqual([])
})

// ---- URL の slug とシフト表のチームの不一致 ----------------------------------------------------

async function expectBoardHidden(page: Page, slug: string, scheduleId: number, titleNeedle: string, label: string) {
  const status = await openBoard(page, slug, scheduleId)
  console.log(`[cmp-261123-1821] ${label}: schedule GET -> ${status}`)
  await expect(page.getByTestId('shift-board-load-error'), `${label}: 取得失敗表示`).toBeVisible({ timeout: 60_000 })
  await page.waitForTimeout(1_000)
  await expect(page.getByText(titleNeedle), `${label}: シフト表の題名が出ない`).toHaveCount(0)
  await expect(page.locator('[draggable="true"]'), `${label}: 未割当メンバーが並ばない`).toHaveCount(0)
  await expect(page.locator('tbody tr'), `${label}: 枠が出ない`).toHaveCount(0)
  return status
}

test('AC-2 slug 不一致: チーム A のシフト表をチーム B の slug で開くと存在しない画面として扱われ、B のメンバーが並ばない（dual）', async ({ browser }) => {
  test.setTimeout(300_000)
  const page = await sessionOf(browser, W.dual)
  // 対照: 正しい slug では開ける
  await reloadBoard(page)
  const status = await expectBoardHidden(page, W.teamB.slug, W.scheduleA, '1821シフトA', 'dual: B の slug で A のシフト表')
  expect(status, 'dual は A のシフト表を読める（不一致の検出が効いているかを見る条件）').toBe(200)
  await page.screenshot({ path: path.join(EVID_DIR, 'ac2-slug-mismatch-dual.png') })
  // 逆向き: A の slug で B のシフト表
  await expectBoardHidden(page, W.teamA.slug, W.scheduleB, '1821シフトB', 'dual: A の slug で B のシフト表')
})

test('AC-2 slug 不一致の戻り導線: 取得失敗の画面から、URL の slug のシフト表一覧へ戻れる', async ({ browser }) => {
  test.setTimeout(300_000)
  const page = await sessionOf(browser, W.dual)
  await openBoard(page, W.teamB.slug, W.scheduleA)
  await expect(page.getByTestId('shift-board-load-error')).toBeVisible({ timeout: 60_000 })
  // 操作群（自動割当など）は出ない
  await expect(page.getByTestId('auto-assign-button')).toHaveCount(0)
  await page.getByRole('link', { name: '一覧に戻る' }).first().click()
  await expect(page).toHaveURL(new RegExp(`/teams/${W.teamB.slug}/shifts$`), { timeout: 60_000 })
})

// ---- 権限なし・他テナント: URL 直打ち ------------------------------------------------------------

test('ROLE 他テナント（チーム B の ADMIN）: A のシフト表を B の slug でも A の slug でも開けず、題名・メンバー・枠が出ない', async ({ browser }) => {
  test.setTimeout(300_000)
  const page = await sessionOf(browser, W.bAdmin)
  const s1 = await expectBoardHidden(page, W.teamB.slug, W.scheduleA, '1821シフトA', 'B ADMIN: B の slug で A のシフト表')
  const s2 = await expectBoardHidden(page, W.teamA.slug, W.scheduleA, '1821シフトA', 'B ADMIN: A の slug で A のシフト表')
  expect([403, 404]).toContain(s1)
  expect([403, 404]).toContain(s2)
  await page.screenshot({ path: path.join(EVID_DIR, 'role-badmin-direct.png') })
  // 存在しないシフト表と同じ見え方か（存在オラクルになっていないか）
  const none = await openBoard(page, W.teamA.slug, 999_999_999)
  expect(none, '不在のシフト表と同じ状態コード').toBe(s2)
})

test('ROLE 部外者: URL 直打ちでシフト表が開けず、題名・メンバー・枠が出ない', async ({ browser }) => {
  test.setTimeout(300_000)
  const page = await sessionOf(browser, W.outsider)
  const s = await expectBoardHidden(page, W.teamA.slug, W.scheduleA, '1821シフトA', '部外者')
  expect([403, 404]).toContain(s)
  await page.screenshot({ path: path.join(EVID_DIR, 'role-outsider-direct.png') })
})

test('ROLE SUPPORTER: URL 直打ちでボードの枠・メンバーが見えない（枠一覧は 403）', async ({ browser }) => {
  test.setTimeout(300_000)
  const page = await sessionOf(browser, W.supporter)
  await page.goto(`${FE}${boardUrl(W.teamA.slug, W.scheduleA)}`, { waitUntil: 'commit' })
  await expect(page.getByTestId('shift-board-load-error')).toBeVisible({ timeout: 90_000 })
  await expect(page.locator('tbody tr')).toHaveCount(0)
  await expect(page.locator('[draggable="true"]')).toHaveCount(0)
  await page.screenshot({ path: path.join(EVID_DIR, 'role-supporter-direct.png') })
  const r = await apiAs(W.supporter, 'GET', `/api/v1/shifts/schedules/${W.scheduleA}/slots`)
  expect(r.status, 'SUPPORTER の枠一覧 API').toBe(403)
})

test('ROLE 同チームの一般 MEMBER: URL 直打ちでボードは開くが、画面から割り当てようとしても 403 で拒否され、枠に残らない', async ({ browser }) => {
  test.setTimeout(300_000)
  const page = await sessionOf(browser, W.member)
  await reloadBoard(page)
  await page.screenshot({ path: path.join(EVID_DIR, 'role-member-board.png') })
  // 自動割当などの管理導線は出ない
  await expect(page.getByTestId('auto-assign-button')).toHaveCount(0)
  const adds = await addButton(slotRow(page, 1)).count()
  console.log(`[cmp-261123-1821] 一般 MEMBER のボードで枠の追加ボタン数(行 2) = ${adds}`)
  if (adds === 0) return // 導線が無い＝割当操作へ到達できない（合格）
  await addButton(slotRow(page, 1)).click()
  const dialog = dialogOf(page)
  await expect(dialog).toBeVisible()
  const [res] = await Promise.all([patchAssignmentsResponse(page), dialog.getByText(W.member2.nickname, { exact: true }).click()])
  expect(res.status(), '画面から送信しても BE は 403').toBe(403)
  await page.waitForTimeout(1_000)
  await page.screenshot({ path: path.join(EVID_DIR, 'role-member-403.png') })
  await expect(chipIn(slotRow(page, 1), W.member2)).toHaveCount(0)
  expect(await assignedOf(W.slotA2)).toEqual([])
})

test('ROLE 同チームの一般 MEMBER: 権限が無いので割当の追加ボタン・未割当ドラッグ欄・外すボタンが表示されない', async ({ browser }) => {
  test.setTimeout(300_000)
  // CMP-261125-1336: FE が ADMIN / MANAGE_SHIFTS を持つ DEPUTY_ADMIN にだけ割当操作を出すようにしたので、
  // 旧「既知の欠陥の固定（test.fail）」は外した。一般 MEMBER には死んだ導線（押すと 403）を出さない。
  const page = await sessionOf(browser, W.member)
  await reloadBoard(page)
  await expect(addButton(slotRow(page, 1))).toHaveCount(0)
  await expect(page.locator('[draggable="true"]')).toHaveCount(0)
  await expect(page.getByTestId('shift-chip-remove')).toHaveCount(0)
})

// ---- 画面から到達できない攻撃（API での裏取り）--------------------------------------------------

test('API 追加・置換・移動に他チーム利用者・SUPPORTER・不在 ID を混ぜると SHIFT_049。応答は同一で、枠は変わらない', async () => {
  test.setTimeout(300_000)
  const before = await assignedOf(W.slotA2)
  const attackers: Array<[string, number]> = [
    ['他チーム MEMBER', W.bMember.id],
    ['他チーム ADMIN', W.bAdmin.id],
    ['部外者', W.outsider.id],
    ['SUPPORTER', W.supporter.id],
    ['チームから外れた元メンバー', W.member3.id],
    ['存在しない利用者 ID', 999_999_999],
  ]
  const seen = new Map<string, string>()
  for (const [label, uid] of attackers) {
    const { version } = await slotVersion(W.admin, W.scheduleA, W.slotA2)
    const add = await apiAs(W.admin, 'PATCH', `/api/v1/shifts/slots/${W.slotA2}/assignments`, { addUserIds: [uid], slotVersion: version })
    const repl = await apiAs(W.admin, 'PATCH', `/api/v1/shifts/slots/${W.slotA2}`, { assignedUserIds: [uid] })
    const mv = await apiAs(W.admin, 'POST', `/api/v1/shifts/slots/${W.slotA3}/assignments/move`, {
      targetSlotId: W.slotA2, userId: uid, sourceSlotVersion: (await slotVersion(W.admin, W.scheduleA, W.slotA3)).version, targetSlotVersion: version,
    })
    for (const [kind, r] of [['追加', add], ['置換', repl], ['移動', mv]] as const) {
      expect(r.status, `${label} ${kind} は 4xx`).toBeGreaterThanOrEqual(400)
      expect(r.body?.error?.code, `${label} ${kind} のコード`).toBe('SHIFT_049')
      const sig = `${r.status}|${r.body.error.code}|${r.body.error.message}`
      const prev = seen.get(kind)
      if (prev) expect(sig, `${label} ${kind}: 他の不適格者・存在しない ID と同じ応答（存在オラクルなし）`).toBe(prev)
      seen.set(kind, sig)
    }
  }
  console.log(`[cmp-261123-1821] 拒否応答: ${JSON.stringify([...seen.entries()])}`)
  expect(await assignedOf(W.slotA2), '拒否された操作で枠は変わらない').toEqual(before)
})

test('API 正規メンバーと他チーム利用者の混在は全体を拒否し、正規メンバーも入らない。正規メンバーだけなら 200', async () => {
  test.setTimeout(300_000)
  const { version } = await slotVersion(W.admin, W.scheduleA, W.slotA2)
  const mixed = await apiAs(W.admin, 'PATCH', `/api/v1/shifts/slots/${W.slotA2}/assignments`, { addUserIds: [W.member.id, W.bMember.id], slotVersion: version })
  expect(mixed.body?.error?.code).toBe('SHIFT_049')
  expect(await assignedOf(W.slotA2)).toEqual([])
  const ok = await apiAs(W.admin, 'PATCH', `/api/v1/shifts/slots/${W.slotA2}/assignments`, { addUserIds: [W.member.id, W.member2.id], slotVersion: version })
  expect(ok.status).toBe(200)
  // 置換: 既存割当の正規メンバーだけの置換は通り、新規の他チーム利用者が混ざると拒否される
  const keep = await apiAs(W.admin, 'PATCH', `/api/v1/shifts/slots/${W.slotA2}`, { assignedUserIds: [W.member.id, W.member2.id] })
  expect(keep.status, '正規メンバーだけの置換は通る').toBe(200)
  const bad = await apiAs(W.admin, 'PATCH', `/api/v1/shifts/slots/${W.slotA2}`, { assignedUserIds: [W.member.id, W.member2.id, W.bAdmin.id] })
  expect(bad.body?.error?.code).toBe('SHIFT_049')
  // 移動: 正規メンバーの枠間移動は通る
  const a2 = await slotVersion(W.admin, W.scheduleA, W.slotA2)
  const a3 = await slotVersion(W.admin, W.scheduleA, W.slotA3)
  const mv = await apiAs(W.admin, 'POST', `/api/v1/shifts/slots/${W.slotA2}/assignments/move`, {
    targetSlotId: W.slotA3, userId: W.member.id, sourceSlotVersion: a2.version, targetSlotVersion: a3.version,
  })
  expect(mv.status, '正規メンバーの移動は 200').toBe(200)
  expect((await assignedOf(W.slotA3)).includes(W.member.id)).toBe(true)
})

test('API 追加・置換のリストは 200 件まで（201 件は 400）', async () => {
  test.setTimeout(120_000)
  const { version } = await slotVersion(W.admin, W.scheduleA, W.slotA2)
  const ids = Array.from({ length: 201 }, (_, i) => 900_000_000 + i)
  const r = await apiAs(W.admin, 'PATCH', `/api/v1/shifts/slots/${W.slotA2}/assignments`, { addUserIds: ids, slotVersion: version })
  expect(r.status).toBe(400)
  const r2 = await apiAs(W.admin, 'PATCH', `/api/v1/shifts/slots/${W.slotA2}`, { assignedUserIds: ids })
  expect(r2.status).toBe(400)
})

test('API 権限なし・他テナントの操作は拒否される（一般 MEMBER・SUPPORTER は 403、部外者・他チーム ADMIN は不在と同じ応答）', async () => {
  test.setTimeout(300_000)
  const { version } = await slotVersion(W.admin, W.scheduleA, W.slotA2)
  const body = { addUserIds: [W.member2.id], slotVersion: version }
  const absent = await apiAs(W.admin, 'PATCH', '/api/v1/shifts/slots/999999999/assignments', body)
  for (const [actor, label] of [[W.member, '一般 MEMBER'], [W.supporter, 'SUPPORTER']] as const) {
    const r = await apiAs(actor, 'PATCH', `/api/v1/shifts/slots/${W.slotA2}/assignments`, body)
    expect(r.status, `${label} の割当追加`).toBe(403)
  }
  for (const [actor, label] of [[W.outsider, '部外者'], [W.bAdmin, '他チーム ADMIN']] as const) {
    const r = await apiAs(actor, 'PATCH', `/api/v1/shifts/slots/${W.slotA2}/assignments`, body)
    expect(r.status, `${label} の割当追加`).toBe(absent.status)
    expect(r.body?.error?.code, `${label} は不在と同じコード`).toBe(absent.body?.error?.code)
  }
  const unauth = await apiAs(null, 'PATCH', `/api/v1/shifts/slots/${W.slotA2}/assignments`, body)
  expect([401, 403]).toContain(unauth.status)
})

test('最終確認: どの枠にも他チーム・非所属・SUPPORTER・除名済みの利用者は割り当てられていない（API と SQL）', async () => {
  test.setTimeout(120_000)
  const bad = [W.bMember.id, W.bAdmin.id, W.outsider.id, W.supporter.id, W.member3.id]
  for (const slotId of [W.slotA1, W.slotA2, W.slotA3]) {
    const a = await assignedOf(slotId)
    for (const id of bad) expect(a, `枠 ${slotId} に不適格な利用者が居ない`).not.toContain(id)
  }
  const rows = sql(`SELECT id, assigned_user_ids FROM shift_slots WHERE schedule_id=${W.scheduleA} AND deleted_at IS NULL;`)
  console.log(`[cmp-261123-1821] 最終の割当(SQL): ${rows.replace(/\n/g, ' / ')}`)
  for (const id of bad) expect(new RegExp(`[\\[ ,]${id}[\\], ]`).test(rows), `SQL の割当に ${id} が無い`).toBe(false)
})
