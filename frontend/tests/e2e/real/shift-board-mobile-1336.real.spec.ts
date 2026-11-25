import { appendFileSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import type { Browser, BrowserContext, Locator, Page } from '@playwright/test'
import { test } from '@playwright/test'
import {
  FE, API, RUN, ROLE_ID, apiAs, createActor, createTeam, joinAsMember, must, sql, expect, readAssets, gotoAndHydrate,
  type Actor, type Scope,
} from './helpers/n1p1-world'
import { loginViaApi } from '../fixtures/auth'

/**
 * 戦役 CMP-261125-1336（シフトボードのスマホ表示と枠ごとの割当表示）の実機E2E。実 BE・実 FE・実 Chromium、モックなし。
 *
 * 入口: /teams/{slug}/shifts/{scheduleId}/board と、概要カレンダー /shift/{scheduleId}。
 * 割当の追加・解除は必ず画面（「+ 追加」→ダイアログ、×、ドラッグ＆ドロップ）で行う。
 * API を使うのはログイン・前提データ（利用者・チーム・所属・権限グループ・ポジション・シフト表・枠・初期割当）の作成・後始末と、
 * 画面操作の結果の裏取り（枠の割当の読み取り）、および DB（読み取りのみ）での割当確認だけ。
 *
 * 実行（cd frontend）:
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 E2E_EVIDENCE_DIR=<証跡dir> \
 *     npx playwright test -c <config> tests/e2e/real/shift-board-mobile-1336.real.spec.ts
 * 世界を残す: E2E_KEEP_WORLD=1（後始末を省き、world.json を証跡 dir に保存する。保存自体は常に行う）
 */
const EVID_DIR = process.env.E2E_EVIDENCE_DIR || path.resolve('..', 'docs', 'evidence', 'CMP-261125-1336')
const LEDGER = path.resolve('tests', 'e2e', '.auth', 'cmp261125-1336-assets.json')
const KEEP = process.env.E2E_KEEP_WORLD === '1'
const TAG = `R${RUN}`
const EVID_LOG = path.join(EVID_DIR, 'evidence.log')

function note(line: string) {
  const text = `[cmp-261125-1336] ${line}`
  console.log(text)
  try {
    appendFileSync(EVID_LOG, `${text}\n`)
  } catch {
    // 証跡ログの書き込み失敗でテストは落とさない
  }
}

interface World {
  admin: Actor
  deputy: Actor     // DEPUTY_ADMIN + MANAGE_SHIFTS 権限グループ
  viewDeputy: Actor // DEPUTY_ADMIN（MANAGE_SHIFTS なし）
  m1: Actor         // S1 に初期割当
  m2: Actor         // S2（満員）に初期割当
  m3: Actor         // S3（S1・S2 と時間が一部重なる）に初期割当
  m4: Actor         // S6（S5 の日跨ぎ時間と重なる）に初期割当
  m5: Actor         // 追加操作の対象（どこにも入っていない）
  m6: Actor         // 追加操作の対象（デスクトップのドラッグ用）
  bAdmin: Actor
  teamA: Scope
  teamB: Scope
  posA: number
  posB: number
  posLong: number
  scheduleA: number
  scheduleB: number
  s1: number; s2: number; s3: number; s4: number; s5: number; s6: number
}
let W: World
const sessions = new Map<string, { context: BrowserContext; page: Page }>()
const createdSchedules: Array<{ id: number; owner: Actor }> = []
const createdPositions: Array<{ id: number; owner: Actor }> = []

type Viewport = { width: number; height: number }
const MOBILE: Viewport = { width: 390, height: 844 }
const DESKTOP: Viewport = { width: 1280, height: 800 }

/** 利用者として新しい文脈でログインする。viewport とタッチ・ロケールを指定できる。 */
async function sessionOf(browser: Browser, actor: Actor, viewport: Viewport, locale: 'ja' | 'en' = 'ja') {
  const key = `${actor.email}|${viewport.width}x${viewport.height}|${locale}`
  const hit = sessions.get(key)
  if (hit) return hit.page
  const mobile = viewport.width < 768
  const context = await browser.newContext({
    baseURL: FE,
    storageState: { cookies: [], origins: [] },
    locale: locale === 'en' ? 'en-US' : 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    viewport,
    hasTouch: mobile,
    isMobile: mobile,
    deviceScaleFactor: mobile ? 2 : 1,
  })
  const page = await context.newPage()
  await page.goto(`${FE}/robots.txt`)
  await loginViaApi(page, actor, { apiBaseUrl: API, deferNavigation: true })
  if (locale === 'en') {
    await context.addCookies([{ name: 'i18n_locale', value: 'en', url: FE }])
  }
  sessions.set(key, { context, page })
  return page
}

const boardPath = (slug: string, scheduleId: number) => `/teams/${slug}/shifts/${scheduleId}/board`

async function openBoard(page: Page, slug: string, scheduleId: number): Promise<number> {
  const resP = page.waitForResponse(
    (r) => r.request().method() === 'GET' && new URL(r.url()).pathname === `/api/v1/shifts/schedules/${scheduleId}`,
    { timeout: 120_000 },
  )
  await page.goto(`${FE}${boardPath(slug, scheduleId)}`, { waitUntil: 'commit' })
  return (await resP).status()
}

async function boardReady(page: Page) {
  await expect(page.getByTestId('shift-board-table')).toBeVisible({ timeout: 90_000 })
  await page.waitForTimeout(800)
}

async function loadBoard(page: Page, slug = W.teamA.slug, scheduleId = W.scheduleA) {
  expect(await openBoard(page, slug, scheduleId)).toBe(200)
  await boardReady(page)
}

const cell = (page: Page, slotId: number) => page.getByTestId(`shift-cell-${slotId}`)
const countOf = async (page: Page, slotId: number) => ((await cell(page, slotId).getByTestId('shift-cell-count').innerText()).replace(/\s+/g, ' ').trim())
const dialogOf = (page: Page) => page.getByRole('dialog')
const optionOf = (page: Page, actor: Actor) => page.getByTestId(`shift-add-member-option-${actor.id}`)

function patchAssignments(page: Page) {
  return page.waitForResponse((r) => r.request().method() === 'PATCH' && /\/api\/v1\/shifts\/slots\/\d+\/assignments$/.test(new URL(r.url()).pathname), { timeout: 60_000 })
}

async function slotState(slotId: number) {
  const r = await must(W.admin, 'GET', `/api/v1/shifts/schedules/${W.scheduleA}/slots`)
  const s = (r.data as Array<{ id: number; version: number; assignedUserIds: number[] }>).find((x) => x.id === slotId)
  if (!s) throw new Error(`枠 ${slotId} が無い`)
  return s
}

/** DB（読み取りのみ）の枠の割当ユーザー ID（shift_slots.assigned_user_ids の JSON 配列）。 */
function dbAssigned(slotId: number): number[] {
  const out = sql(`SELECT IFNULL(assigned_user_ids, '[]') FROM shift_slots WHERE id=${slotId} AND deleted_at IS NULL;`)
  return (JSON.parse(out || '[]') as number[]).sort((a, b) => a - b)
}

async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(EVID_DIR, `${name}.png`) })
}

/** ページ本体が横にはみ出していないか（表の内側コンテナの横スクロールは含めない）。 */
async function pageOverflow(page: Page) {
  return page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
    bodyScrollWidth: document.body.scrollWidth,
  }))
}

test.describe.configure({ mode: 'serial' })

const LONG_POSITION = `Long position name for wrapping check ${TAG}`

test.beforeAll(async () => {
  test.setTimeout(600_000)
  mkdirSync(EVID_DIR, { recursive: true })
  const admin = await createActor('c1336adm')
  const deputy = await createActor('c1336dep')
  const viewDeputy = await createActor('c1336vdp')
  const m1 = await createActor('c1336m1')
  const m2 = await createActor('c1336m2')
  const m3 = await createActor('c1336m3')
  const m4 = await createActor('c1336m4')
  const m5 = await createActor('c1336m5')
  const m6 = await createActor('c1336m6')
  const bAdmin = await createActor('c1336badm')
  const teamA = await createTeam(admin, `Team1336A${TAG}`, 'PUBLIC', LEDGER)
  const teamB = await createTeam(bAdmin, `Team1336B${TAG}`, 'PUBLIC', LEDGER)
  for (const u of [deputy, viewDeputy]) {
    await joinAsMember('teams', admin, teamA.slug, u)
    await must(admin, 'PATCH', `/api/v1/teams/${teamA.slug}/members/${u.id}/role`, { roleId: ROLE_ID.DEPUTY_ADMIN })
  }
  const manageShiftsId = Number(sql(`SELECT id FROM permissions WHERE name = 'MANAGE_SHIFTS';`))
  const group = await must(admin, 'POST', `/api/v1/teams/${teamA.slug}/permission-groups`, {
    name: `manage-shifts-${TAG}`, targetRole: 'DEPUTY_ADMIN', permissionIds: [manageShiftsId],
  })
  await must(admin, 'PUT', `/api/v1/teams/${teamA.slug}/members/${deputy.id}/permission-groups`, { groupIds: [Number(group.data.id)] })
  for (const u of [m1, m2, m3, m4, m5, m6]) await joinAsMember('teams', admin, teamA.slug, u)

  const posA = await must(admin, 'POST', `/api/v1/shifts/positions?teamId=${teamA.numericId}`, { name: `Reception${TAG}`, displayOrder: 1 })
  const posLong = await must(admin, 'POST', `/api/v1/shifts/positions?teamId=${teamA.numericId}`, { name: LONG_POSITION, displayOrder: 2 })
  const posB = await must(bAdmin, 'POST', `/api/v1/shifts/positions?teamId=${teamB.numericId}`, { name: `BReception${TAG}`, displayOrder: 1 })
  const schA = await must(admin, 'POST', `/api/v1/shifts/schedules?teamId=${teamA.numericId}`, { title: `Board1336 ${TAG}`, startDate: '2027-03-08', endDate: '2027-03-14' })
  const schB = await must(bAdmin, 'POST', `/api/v1/shifts/schedules?teamId=${teamB.numericId}`, { title: `BoardB1336 ${TAG}`, startDate: '2027-03-08', endDate: '2027-03-14' })
  const mk = async (date: string, start: string, end: string, positionId: number | null, requiredCount: number, endsNextDay = false) =>
    (await must(admin, 'POST', `/api/v1/shifts/schedules/${schA.data.id}/slots`, {
      slotDate: date, startTime: start, endTime: end, positionId, requiredCount, note: null, endsNextDay,
    })).data.id as number
  // S1/S4: 同じ日時・同じポジションの複数枠。S2: 同じ日時で別ポジション・必要 1（満員にする）。S3: ポジションなし・S1/S2 と時間が一部重なる。
  // S5: 日跨ぎ（22:00-翌 02:00）。S6: 翌日 01:00-03:00（S5 と重なる）。
  const s1 = await mk('2027-03-09', '09:00:00', '13:00:00', posA.data.id, 3)
  const s2 = await mk('2027-03-09', '09:00:00', '13:00:00', posLong.data.id, 1)
  const s3 = await mk('2027-03-09', '12:00:00', '15:00:00', null, 2)
  const s4 = await mk('2027-03-09', '09:00:00', '13:00:00', posA.data.id, 2)
  const s5 = await mk('2027-03-10', '22:00:00', '02:00:00', posA.data.id, 2, true)
  const s6 = await mk('2027-03-11', '01:00:00', '03:00:00', posA.data.id, 2)
  createdSchedules.push({ id: schA.data.id, owner: admin }, { id: schB.data.id, owner: bAdmin })
  createdPositions.push({ id: posA.data.id, owner: admin }, { id: posLong.data.id, owner: admin }, { id: posB.data.id, owner: bAdmin })
  for (const [owner, id] of [[admin, schA.data.id], [bAdmin, schB.data.id]] as const) {
    for (const status of ['COLLECTING', 'ADJUSTING']) await must(owner, 'POST', `/api/v1/shifts/schedules/${id}/transition?status=${status}`)
  }
  // 初期割当（前提作成）
  const seed = async (slotId: number, u: Actor) => {
    const version = (await must(admin, 'GET', `/api/v1/shifts/schedules/${schA.data.id}/slots`)).data.find((x: { id: number }) => x.id === slotId).version
    await must(admin, 'PATCH', `/api/v1/shifts/slots/${slotId}/assignments`, { addUserIds: [u.id], slotVersion: version })
  }
  await seed(s1, m1)
  await seed(s2, m2)
  await seed(s3, m3)
  await seed(s6, m4)
  W = {
    admin, deputy, viewDeputy, m1, m2, m3, m4, m5, m6, bAdmin, teamA, teamB,
    posA: posA.data.id, posB: posB.data.id, posLong: posLong.data.id, scheduleA: schA.data.id, scheduleB: schB.data.id,
    s1, s2, s3, s4, s5, s6,
  }
  const actors = Object.fromEntries(Object.entries(W).filter(([, v]) => v && typeof v === 'object' && 'email' in (v as object)).map(([k, v]) => [k, { id: (v as Actor).id, email: (v as Actor).email, password: (v as Actor).password, nickname: (v as Actor).nickname }]))
  writeFileSync(path.join(EVID_DIR, 'world.json'), JSON.stringify({ run: RUN, FE, API, ...W, actors }, null, 2))
  note(`世界作成 teamA=${teamA.slug} scheduleA=${schA.data.id} slots=${[s1, s2, s3, s4, s5, s6].join(',')} KEEP=${KEEP}`)
})

test.afterAll(async () => {
  test.setTimeout(300_000)
  for (const { context } of sessions.values()) {
    try { await context.close() } catch { /* 閉じ済み */ }
  }
  if (KEEP) {
    note('E2E_KEEP_WORLD=1: 後始末を省略（世界を残す）')
    return
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
  const teams = Number(sql(`SELECT COUNT(*) FROM teams WHERE deleted_at IS NULL AND name LIKE '%${TAG}%';`))
  const schedules = Number(sql(`SELECT COUNT(*) FROM shift_schedules WHERE deleted_at IS NULL AND title LIKE '%${TAG}%';`))
  note(`後始末 残件: チーム=${teams} シフト表=${schedules} 失敗=${failures.length}`)
  expect(failures, `削除失敗: ${failures.join(' / ')}`).toEqual([])
  expect(teams).toBe(0)
  expect(schedules).toBe(0)
})

// ---- S1: 390px ADMIN ---------------------------------------------------------------------

test('S1-a 390px ADMIN: ヘッダーが折れず右端が画面内、ステータスが日本語、表が内側だけ横スクロールし日付列が固定', async ({ browser }) => {
  const page = await sessionOf(browser, W.admin, MOBILE)
  await loadBoard(page)
  const url = page.url()
  const header = await page.getByTestId('shift-board-header').boundingBox()
  expect(header!.x + header!.width, 'ヘッダーの右端が画面内').toBeLessThanOrEqual(MOBILE.width + 0.5)
  const actions = await page.getByTestId('shift-board-actions').boundingBox()
  expect(actions!.x + actions!.width).toBeLessThanOrEqual(MOBILE.width + 0.5)
  const title = page.getByRole('heading', { name: 'シフトボード' })
  await expect(title).toBeVisible()
  const tb = await title.boundingBox()
  expect(tb!.height, '見出しが縦書き状に折れていない（1〜2行の高さ）').toBeLessThan(80)
  const badge = page.getByTestId('shift-board-header').getByText('調整中')
  await expect(badge, 'ステータスが i18n（調整中）').toBeVisible()
  await expect(page.getByTestId('shift-board-header').getByText(/\bADJUSTING\b/)).toHaveCount(0)
  const ov = await pageOverflow(page)
  note(`S1-a url=${url} pageOverflow=${JSON.stringify(ov)} header.right=${header!.x + header!.width} actions.right=${actions!.x + actions!.width}`)
  expect(ov.scrollWidth, 'ページ本体が横にはみ出さない').toBeLessThanOrEqual(ov.clientWidth)
  const scroll = page.getByTestId('shift-board-scroll')
  const dims = await scroll.evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth }))
  expect(dims.sw, '表の内側コンテナが横スクロールできる').toBeGreaterThan(dims.cw)
  const firstDate = page.locator('tbody tr').first().locator('td').first()
  const before = await firstDate.boundingBox()
  await scroll.evaluate((el) => { el.scrollLeft = 300 })
  await page.waitForTimeout(300)
  const after = await firstDate.boundingBox()
  note(`S1-a 日付列 x: スクロール前=${before!.x} 後=${after!.x} scrollLeft=${await scroll.evaluate((el) => el.scrollLeft)}`)
  expect(Math.abs(after!.x - before!.x), '日付列が左に固定').toBeLessThan(2)
  await shot(page, 'S1a-board-390-scrolled')
  await scroll.evaluate((el) => { el.scrollLeft = 0 })
  await page.waitForTimeout(200)
  await shot(page, 'S1a-board-390-top')
})

test('S1-b 390px ADMIN: 各セルが「〇 / 〇人」、ポジションなし・同日時複数枠・日跨ぎ枠が見える', async ({ browser }) => {
  const page = await sessionOf(browser, W.admin, MOBILE)
  await loadBoard(page)
  const counts: Record<string, string> = {}
  for (const [name, id] of Object.entries({ s1: W.s1, s2: W.s2, s3: W.s3, s4: W.s4, s5: W.s5, s6: W.s6 })) {
    await cell(page, id).scrollIntoViewIfNeeded()
    counts[name] = await countOf(page, id)
    expect(counts[name], `${name} の表示`).toMatch(/\d+ \/ \d+人/)
  }
  note(`S1-b セル人数 ${JSON.stringify(counts)}`)
  expect(counts.s1).toContain('1 / 3人')
  expect(counts.s2).toContain('1 / 1人')
  expect(counts.s2).toContain('満員')
  expect(counts.s3).toContain('1 / 2人')
  expect(counts.s4).toContain('0 / 2人')
  expect(counts.s5).toContain('0 / 2人')
  await expect(page.getByText('ポジションなし').first(), 'ポジションなしの列').toBeVisible()
  await expect(cell(page, W.s3), 'ポジションなしの枠が見える').toBeVisible()
  // S1 と S4 は同じ日時・同じポジション → 同じ行・同じ列に両方が出る
  const b1 = await cell(page, W.s1).boundingBox()
  const b4 = await cell(page, W.s4).boundingBox()
  note(`S1-b S1.box=${JSON.stringify(b1)} S4.box=${JSON.stringify(b4)}`)
  expect(b1 && b4 && Math.abs(b1.x - b4.x) < 2 && b4.y > b1.y, '同じ日時・列に縦に並ぶ').toBeTruthy()
  const ov = await pageOverflow(page)
  expect(ov.scrollWidth).toBeLessThanOrEqual(ov.clientWidth)
  await shot(page, 'S1b-cells-390')
})

test('S1-c 390px ADMIN: 「+追加」→ダイアログ（その枠の人は出ない・重なる別枠の人は理由付きで選べない）→追加→人数が増える→×で解除', async ({ browser }) => {
  const page = await sessionOf(browser, W.admin, MOBILE)
  await loadBoard(page)
  const add = cell(page, W.s1).getByTestId('shift-cell-add')
  await add.scrollIntoViewIfNeeded()
  const box = await add.boundingBox()
  note(`S1-c 追加ボタン寸法 ${Math.round(box!.width)}x${Math.round(box!.height)}`)
  expect(box!.height, 'タップ領域 44px 以上').toBeGreaterThanOrEqual(44)
  await add.tap()
  const dialog = dialogOf(page)
  await expect(dialog).toBeVisible()
  await expect(page.getByTestId('shift-add-member-target')).toContainText('2027/03/09 09:00〜13:00')
  const url = page.url()
  // その枠（S1）に既にいる m1 は出ない
  await expect(optionOf(page, W.m1), 'S1 の割当済みは出ない').toHaveCount(0)
  // m2 は S2（同日時）、m3 は S3（12:00-15:00 と重なる）に入っているため選べない（理由付き）
  for (const u of [W.m2, W.m3]) {
    await expect(optionOf(page, u)).toBeVisible()
    await expect(optionOf(page, u), `${u.label} は選べない`).toBeDisabled()
    await expect(optionOf(page, u)).toContainText('時間が重なるため選べません')
  }
  await expect(optionOf(page, W.m5)).toBeEnabled()
  note(`S1-c url=${url} ダイアログ: m1(S1既存)=非表示, m2/m3=無効+理由, m5=有効`)
  await shot(page, 'S1c-1-dialog-390')
  const [res] = await Promise.all([patchAssignments(page), optionOf(page, W.m5).tap()])
  expect(res.status()).toBe(200)
  await expect(dialog).toBeHidden()
  await expect.poll(() => countOf(page, W.s1)).toContain('2 / 3人')
  expect(dbAssigned(W.s1).sort()).toEqual([W.m1.id, W.m5.id].sort())
  note(`S1-c 追加後 S1=${await countOf(page, W.s1)} DB=${JSON.stringify(dbAssigned(W.s1))}`)
  await shot(page, 'S1c-2-added-390')
  // × で解除
  const chipX = cell(page, W.s1).locator('.rounded-full', { hasText: W.m5.nickname }).getByTestId('shift-chip-remove')
  const xb = await chipX.boundingBox()
  note(`S1-c ×ボタン寸法 ${Math.round(xb!.width)}x${Math.round(xb!.height)}`)
  expect(xb!.width).toBeGreaterThanOrEqual(44)
  const [res2] = await Promise.all([patchAssignments(page), chipX.tap()])
  expect(res2.status()).toBe(200)
  await expect.poll(() => countOf(page, W.s1)).toContain('1 / 3人')
  expect(dbAssigned(W.s1)).toEqual([W.m1.id])
  note(`S1-c 解除後 S1=${await countOf(page, W.s1)} DB=${JSON.stringify(dbAssigned(W.s1))}`)
  await shot(page, 'S1c-3-removed-390')
})

test('S1-d 390px ADMIN: 日跨ぎ枠の追加ダイアログで翌日 01:00-03:00 の枠にいる人が理由付きで選べない／満員の枠は追加不可で理由表示', async ({ browser }) => {
  const page = await sessionOf(browser, W.admin, MOBILE)
  await loadBoard(page)
  const add5 = cell(page, W.s5).getByTestId('shift-cell-add')
  await add5.scrollIntoViewIfNeeded()
  await add5.tap()
  await expect(dialogOf(page)).toBeVisible()
  await expect(optionOf(page, W.m4)).toBeDisabled()
  await expect(optionOf(page, W.m4)).toContainText('時間が重なるため選べません')
  await expect(optionOf(page, W.m5)).toBeEnabled()
  note('S1-d 日跨ぎ S5(3/10 22:00-翌02:00) のダイアログで m4(S6 3/11 01:00-03:00) は無効+理由、m5 は有効')
  await shot(page, 'S1d-1-overnight-dialog-390')
  await page.keyboard.press('Escape')
  await expect(dialogOf(page)).toBeHidden()
  // 満員の S2
  const add2 = cell(page, W.s2).getByTestId('shift-cell-add')
  await add2.scrollIntoViewIfNeeded()
  await expect(add2, '満員の枠は追加不可').toBeDisabled()
  await expect(cell(page, W.s2).getByTestId('shift-cell-full-reason')).toContainText('満員のため追加できません')
  await shot(page, 'S1d-2-full-390')
  note('S1-d S2(1/1) の「+追加」は disabled、理由「満員のため追加できません」表示')
})

test('S1-e 390px ADMIN: 「未割当メンバー」ボタンで一覧が開く', async ({ browser }) => {
  const page = await sessionOf(browser, W.admin, MOBILE)
  await loadBoard(page)
  await page.getByTestId('member-pool-open').tap()
  const drawer = page.getByTestId('member-pool-drawer').or(dialogOf(page))
  await expect(drawer.first()).toBeVisible()
  await expect(dialogOf(page).getByText(W.m5.nickname)).toBeVisible()
  await expect(dialogOf(page).getByText(W.m1.nickname), '割当済みの m1 は未割当に出ない').toHaveCount(0)
  note('S1-e 未割当メンバー引き出しが開き m5/m6 が並ぶ、割当済みの m1 は出ない')
  await shot(page, 'S1e-pool-390')
  await page.keyboard.press('Escape')
})

// ---- S2: 390px 他ロール ------------------------------------------------------------------

test('S2-a 390px MANAGE_SHIFTS 付与の副管理者: 割り当てできる', async ({ browser }) => {
  const page = await sessionOf(browser, W.deputy, MOBILE)
  await loadBoard(page)
  await expect(page.getByTestId('member-pool-open')).toBeVisible()
  const add = cell(page, W.s4).getByTestId('shift-cell-add')
  await add.scrollIntoViewIfNeeded()
  await add.tap()
  await expect(dialogOf(page)).toBeVisible()
  const [res] = await Promise.all([patchAssignments(page), optionOf(page, W.m6).tap()])
  expect(res.status()).toBe(200)
  await expect.poll(() => countOf(page, W.s4)).toContain('1 / 2人')
  expect(dbAssigned(W.s4)).toEqual([W.m6.id])
  note(`S2-a url=${page.url()} 副管理者(MANAGE_SHIFTS)が S4 に m6 を追加 -> ${await countOf(page, W.s4)} DB=${JSON.stringify(dbAssigned(W.s4))}`)
  await shot(page, 'S2a-deputy-manage-390')
  // 元に戻す（×）
  const [res2] = await Promise.all([patchAssignments(page), cell(page, W.s4).locator('.rounded-full', { hasText: W.m6.nickname }).getByTestId('shift-chip-remove').tap()])
  expect(res2.status()).toBe(200)
  expect(dbAssigned(W.s4)).toEqual([])
})

test('S2-b 390px 権限なし副管理者・MEMBER: 追加・×・未割当ボタンが出ない（閲覧のみ）', async ({ browser }) => {
  for (const [label, actor] of [['権限なし副管理者', W.viewDeputy], ['MEMBER', W.m1]] as const) {
    const page = await sessionOf(browser, actor, MOBILE)
    await loadBoard(page)
    await expect(page.getByTestId('shift-cell-add'), `${label}: 追加ボタン`).toHaveCount(0)
    await expect(page.getByTestId('shift-chip-remove'), `${label}: ×`).toHaveCount(0)
    await expect(page.getByTestId('member-pool-open'), `${label}: 未割当ボタン`).toHaveCount(0)
    await expect(page.getByTestId('auto-assign-button'), `${label}: 自動割当`).toHaveCount(0)
    const body = (await page.locator('body').innerText()).replace(/\s+/g, ' ')
    const first = (await cell(page, W.s1).innerText()).replace(/\s+/g, ' ')
    note(`S2-b ${label} url=${page.url()} 追加/×/未割当ボタン=0件 S1セル表示="${first}"`)
    expect(body).not.toMatch(/Forbidden|権限がありません/)
    await shot(page, `S2b-${actor === W.m1 ? 'member' : 'viewdeputy'}-390`)
  }
})

test('S3 他チーム ADMIN: チーム A のシフト表を URL 直打ちで開いても弾かれ、題名・枠・メンバーが出ない', async ({ browser }) => {
  const page = await sessionOf(browser, W.bAdmin, MOBILE)
  const status = await openBoard(page, W.teamA.slug, W.scheduleA)
  await expect(page.getByTestId('shift-board-load-error')).toBeVisible({ timeout: 60_000 })
  await page.waitForTimeout(800)
  await expect(page.getByText('Board1336')).toHaveCount(0)
  await expect(page.locator('tbody tr')).toHaveCount(0)
  await expect(page.getByTestId('shift-cell-add')).toHaveCount(0)
  const text = (await page.getByTestId('shift-board-load-error').innerText()).replace(/\s+/g, ' ')
  note(`S3 url=${page.url()} schedule GET -> ${status} 画面="${text}"`)
  await shot(page, 'S3-other-team-admin-390')
  // B の slug で A のシフト表（slug 不一致）も同じ扱い
  const status2 = await openBoard(page, W.teamB.slug, W.scheduleA)
  await expect(page.getByTestId('shift-board-load-error')).toBeVisible({ timeout: 60_000 })
  note(`S3 B の slug で A のシフト表 -> GET ${status2} 取得失敗表示`)
})

// ---- S4: 1280px ADMIN --------------------------------------------------------------------

test('S4 1280px ADMIN: ドラッグで割当でき、同じ日時の複数枠がそれぞれ表示・操作できる', async ({ browser }) => {
  const page = await sessionOf(browser, W.admin, DESKTOP)
  await loadBoard(page)
  await expect(page.getByTestId('member-pool-aside')).toBeVisible()
  await expect(page.getByTestId('member-pool-open'), 'デスクトップでは引き出しボタンは出ない').toBeHidden()
  const url = page.url()
  // 同じ日時・同じポジションの S1 と S4 が両方表示
  await expect(cell(page, W.s1)).toBeVisible()
  await expect(cell(page, W.s4)).toBeVisible()
  // ドラッグ: プールの m6 を S4 のセルへ
  const item = page.locator('[draggable="true"]').filter({ hasText: W.m6.nickname })
  const [res] = await Promise.all([patchAssignments(page), item.dragTo(cell(page, W.s4))])
  expect(res.status()).toBe(200)
  await expect.poll(() => countOf(page, W.s4)).toContain('1 / 2人')
  expect(await countOf(page, W.s1)).toContain('1 / 3人')
  expect(dbAssigned(W.s4)).toEqual([W.m6.id])
  note(`S4 url=${url} ドラッグで S4 に m6 -> S4=${await countOf(page, W.s4)} S1=${await countOf(page, W.s1)}（S1 は変わらない）DB S4=${JSON.stringify(dbAssigned(W.s4))}`)
  await shot(page, 'S4-desktop-drag-1280')
  // 同じ日時の別枠（S1）はダイアログで独立に操作できる
  await cell(page, W.s1).getByTestId('shift-cell-add').click()
  await expect(dialogOf(page)).toBeVisible()
  await expect(optionOf(page, W.m6), 'S4 にいる m6 は S1（同日時）では重複で選べない').toBeDisabled()
  await expect(optionOf(page, W.m5)).toBeEnabled()
  await shot(page, 'S4-desktop-dialog-1280')
  await page.keyboard.press('Escape')
  // 後始末（×）
  const [res2] = await Promise.all([patchAssignments(page), cell(page, W.s4).locator('.rounded-full', { hasText: W.m6.nickname }).getByTestId('shift-chip-remove').click()])
  expect(res2.status()).toBe(200)
  expect(dbAssigned(W.s4)).toEqual([])
})

// ---- S5: 二度押し ------------------------------------------------------------------------

test('S5 390px 二度押し: 確定を連打しても PATCH は 1 回', async ({ browser }) => {
  const page = await sessionOf(browser, W.admin, MOBILE)
  await loadBoard(page)
  const patches: string[] = []
  const onReq = (r: import('@playwright/test').Request) => {
    if (r.method() === 'PATCH' && /\/shifts\/slots\/\d+\/assignments$/.test(new URL(r.url()).pathname)) patches.push(r.url())
  }
  page.on('request', onReq)
  const add = cell(page, W.s4).getByTestId('shift-cell-add')
  await add.scrollIntoViewIfNeeded()
  await add.tap()
  await expect(dialogOf(page)).toBeVisible()
  const opt = optionOf(page, W.m5)
  // 同一フレームで 2 回クリック + 続けて実タップ
  const first = patchAssignments(page)
  await opt.evaluate((el) => { (el as HTMLElement).click(); (el as HTMLElement).click(); (el as HTMLElement).click() })
  await first
  await page.waitForTimeout(1500)
  page.off('request', onReq)
  note(`S5 連打(3連クリック) PATCH 回数=${patches.length} DB S4=${JSON.stringify(dbAssigned(W.s4))}`)
  expect(patches.length, 'PATCH は 1 回').toBe(1)
  expect(dbAssigned(W.s4)).toEqual([W.m5.id])
  await expect.poll(() => countOf(page, W.s4)).toContain('1 / 2人')
  await shot(page, 'S5-double-tap-390')
  const [res] = await Promise.all([patchAssignments(page), cell(page, W.s4).locator('.rounded-full', { hasText: W.m5.nickname }).getByTestId('shift-chip-remove').tap()])
  expect(res.status()).toBe(200)
  expect(dbAssigned(W.s4)).toEqual([])
})

// ---- S6: 概要カレンダー 360px ------------------------------------------------------------

test('S6 360px 概要カレンダー（/shift/{id}）: 枠の札が切れず右端が画面内、伏せた枠の表示語', async ({ browser }) => {
  const NARROW: Viewport = { width: 360, height: 780 }
  for (const [label, actor, masked] of [['ADMIN', W.admin, false], ['MEMBER', W.m1, true]] as const) {
    const page = await sessionOf(browser, actor, NARROW)
    await page.goto(`${FE}/shift/${W.scheduleA}`, { waitUntil: 'commit' })
    const cal = page.getByTestId('shift-overview-calendar')
    await expect(cal).toBeVisible({ timeout: 90_000 })
    await page.waitForTimeout(1000)
    const ov = await pageOverflow(page)
    const calDims = await cal.evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth }))
    const overflowing = await cal.locator('a').evaluateAll((els, vw) => els.filter((e) => e.getBoundingClientRect().right > (vw as number) + 0.5).length, NARROW.width)
    const text = (await cal.innerText()).replace(/\s+/g, ' ')
    const tags = (await cal.locator('a').allInnerTexts()).map((x) => x.replace(/\s+/g, ' '))
    note(`S6 ${label} url=${page.url()} pageOverflow=${JSON.stringify(ov)} calendar=${JSON.stringify(calDims)} 画面外の札=${overflowing} 札の文言: 伏せ=${text.includes('割当は非公開')} 札の人数表示=${/\d+\/\d+/.test(tags.join(' '))} 札=${JSON.stringify(tags.slice(0, 3))}`)
    expect(ov.scrollWidth, `${label}: ページ本体が横にはみ出さない`).toBeLessThanOrEqual(ov.clientWidth)
    expect(overflowing, `${label}: 札が画面外へ出ない`).toBe(0)
    if (masked) {
      expect(text, 'MEMBER は割当を伏せる').toContain('割当は非公開')
      expect(tags.join(' '), '伏せた枠の札に人数（n/m）を出さない').not.toMatch(/\d+\/\d+/)
    } else {
      expect(tags.join(' ')).toMatch(/\d\/\d/)
    }
    await shot(page, `S6-overview-360-${label}`)
  }
})

// ---- S7: en ロケール ---------------------------------------------------------------------

test('S7 en ロケール: 390px ボードで生キー・日本語の固定文言が出ない', async ({ browser }) => {
  // アカウント言語（users.locale）が表示言語の正本。このテスト用に作った MANAGE_SHIFTS 付与の副管理者だけを en にする
  // （画面の言語設定 UI は本件の対象外のため、前提として直接設定する。他のシナリオでは使い終わっている）。
  sql(`UPDATE users SET locale='en' WHERE id=${W.deputy.id} AND email='${W.deputy.email}';`)
  const page = await sessionOf(browser, W.deputy, MOBILE, 'en')
  await loadBoard(page)
  await page.getByTestId('shift-cell-add').first().scrollIntoViewIfNeeded()
  await page.getByTestId('shift-cell-add').first().tap()
  await expect(dialogOf(page)).toBeVisible()
  const dialogText = (await dialogOf(page).innerText()).replace(/\s+/g, ' ')
  await shot(page, 'S7-en-dialog-390')
  await page.keyboard.press('Escape')
  await expect(dialogOf(page)).toBeHidden()
  await page.getByTestId('member-pool-open').tap()
  await expect(dialogOf(page)).toBeVisible()
  const poolText = (await dialogOf(page).innerText()).replace(/\s+/g, ' ')
  await page.keyboard.press('Escape')
  const bodyText = (await page.locator('body').innerText()).replace(/\s+/g, ' ')
  await shot(page, 'S7-en-board-390')
  note(`S7 url=${page.url()} body="${bodyText.slice(0, 400)}" dialog="${dialogText.slice(0, 200)}" pool="${poolText.slice(0, 100)}"`)
  const all = `${bodyText} ${dialogText} ${poolText}`
  expect(all, '日本語の固定文言が無い').not.toMatch(/[぀-ヿ一-鿿]/)
  expect(all, '生キー（shift.xxx.yyy）が無い').not.toMatch(/\bshift\.[a-zA-Z]+\.[a-zA-Z]+/)
  expect(bodyText).toMatch(/\d+ \/ \d+/)
})
