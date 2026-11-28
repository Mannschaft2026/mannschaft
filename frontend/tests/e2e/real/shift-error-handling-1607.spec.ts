import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { test } from '@playwright/test'
import type { Browser, BrowserContext, Locator, Page } from '@playwright/test'
import {
  FE, RUN, ROLE_ID, API, apiAs, createActor, createOrg, createTeam, joinAsMember, must, sql, expect,
  readAssets, type Actor, type Scope,
} from './helpers/n1p1-world'
import { loginViaApi } from '../fixtures/auth'

/**
 * 戦役 CMP-260910-1607（PR #4869）の実機E2E。
 *   ① シフト系 PATCH/POST の不正入力・未知項目を 500 から 400 へ寄せた（StrictShiftRequest）
 *   ② 予算閾値警告の確認（POST /shift-budget/alerts/{id}/acknowledge）を、確認済みなら 409（SHIFT_BUDGET_033）に変え、
 *      FE は確認者・時刻つきの上書き確認ダイアログを出して overwrite:true で再送する
 *
 * 対象操作はすべて画面（クリック・入力・送信）で行う。API を使うのは
 *   - ログイン（loginViaApi）・前提データの作成（利用者・組織・チーム・シフト表・枠・交代依頼の起票）・後始末
 *   - 警告 1 件ぶんの割当・警告行の前提投入（SQL。警告は閾値超過の発火でしか生まれず、画面から作る導線が無いため）
 *   - 負の視点（権限なし・他テナント）での直接 API 確認（FE の出し分けだけで BE が素通りしていないかの検査）
 *   - DB の永続化確認（読み取り SQL）
 * に限る。
 *
 * シナリオ:
 *  S1 確認の正常系（BUDGET_ADMIN A が確認 → 確認済み表示、DB acknowledged_by=A、監査ログ 1 件）
 *  S2 古い画面での再確認（A が確認 → B の古い画面 → 氏名入り上書き確認ダイアログ。キャンセルで DB は A のまま、上書きで B に変わり監査が増える）
 *  S3 確認ボタン連打で POST は 1 回
 *  S4 権限なし MEMBER M・他組織管理者 X は画面でも API でも拒否される
 *  S5 シフト画面の通常操作（状態遷移・シフト表編集・枠編集・割当追加/解除・交代の承諾/承認/却下）が従来どおり成功する
 *  S6 言語 en で上書きダイアログに日本語・生キーが出ない
 *
 * 実行（cd frontend）:
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 \
 *     CHROMIUM_PATH=/opt/pw-browsers/chromium-1243/chrome-linux64/chrome \
 *     npx playwright test -c playwright-cmp1607.config.ts
 * E2E_KEEP_WORLD=1 を付けると後始末をせず、利用者・組織・チームと世界の情報（world.json）を残す（アリシゼーション用）。
 */
const EVID_DIR = process.env.EVID_DIR
  ?? '/tmp/claude-0/-home-user-mannschaft/08337c2f-0ee1-5cd2-9adc-35efb3089852/scratchpad/e2e-1607'
const KEEP_WORLD = process.env.E2E_KEEP_WORLD === '1'
const LEDGER = path.resolve('tests', 'e2e', '.auth', 'cmp260910-1607-assets.json')
const TAG = `R${RUN}`
const EVID_LOG = path.join(EVID_DIR, 'evidence.log')

function note(line: string) {
  console.log(`[cmp-260910-1607] ${line}`)
  try {
    appendFileSync(EVID_LOG, `${line}\n`)
  } catch {
    // 証跡ログの書き込み失敗でテストは落とさない（画面・DB の検証が本体）
  }
}

interface World {
  orgAdminA: Actor   // 組織 O の ADMIN（作成者）= BUDGET_ADMIN
  orgAdminB: Actor   // 組織 O の ADMIN（招待→昇格）= BUDGET_ADMIN
  orgMember: Actor   // 組織 O の MEMBER（権限なし）
  otherAdmin: Actor  // 別組織 O2 の ADMIN（他テナント）
  orgO: Scope
  orgO2: Scope
  teamAdmin: Actor   // シフト用チームの ADMIN
  tm1: Actor
  tm2: Actor
  team: Scope
  positionId: number
  scheduleId: number
  slotIds: number[]
  slotDates: string[]
}
let W: World
const sessions: BrowserContext[] = []
const createdAllocations: number[] = []
let allocationMonth = 0

// ---------------------------------------------------------------------------------------------
// 前提データ
// ---------------------------------------------------------------------------------------------

/** 警告 1 件を作る（割当 1 件＋閾値 80% の警告 1 件。未確認）。期間が重ならないよう月をずらす。 */
function makeAlert(orgId: number, createdBy: number): { alertId: number; allocationId: number } {
  allocationMonth += 1
  const start = new Date(Date.UTC(2031, allocationMonth - 1, 1))
  const end = new Date(Date.UTC(2031, allocationMonth, 0))
  const iso = (d: Date) => d.toISOString().slice(0, 10)
  const out = sql(`
    INSERT INTO shift_budget_allocations (organization_id, team_id, fiscal_year_id, budget_category_id, period_start, period_end,
      allocated_amount, consumed_amount, confirmed_amount, currency, note, created_by, version)
    VALUES (${orgId}, NULL, 9101, 9102, '${iso(start)}', '${iso(end)}', 300000, 250000, 0, 'JPY', '1607実機E2E ${TAG}', ${createdBy}, 0);
    SET @a = LAST_INSERT_ID();
    INSERT INTO budget_threshold_alerts (allocation_id, threshold_percent, triggered_at, consumed_amount_at_trigger, notified_user_ids)
    VALUES (@a, 80, NOW() - INTERVAL 1 HOUR, 240000, '[]');
    SELECT LAST_INSERT_ID(), @a;`)
  const [alertId, allocationId] = out.split(/\s+/).map(Number)
  createdAllocations.push(allocationId!)
  return { alertId: alertId!, allocationId: allocationId! }
}

function ackRow(alertId: number): { by: number | null; at: string | null } {
  const row = sql(`SELECT IFNULL(acknowledged_by,'NULL'), IFNULL(acknowledged_at,'NULL') FROM budget_threshold_alerts WHERE id=${alertId};`).split('\t')
  return { by: row[0] === 'NULL' ? null : Number(row[0]), at: row[1] === 'NULL' ? null : row[1]! }
}

function auditCount(alertId: number): number {
  return Number(sql(`SELECT COUNT(*) FROM audit_logs WHERE event_type='BUDGET_THRESHOLD_ALERT_ACKNOWLEDGED' AND organization_id=${W.orgO.numericId} AND JSON_EXTRACT(metadata, '$.alert_id') = ${alertId};`))
}

async function waitAuditCount(alertId: number, expected: number): Promise<number> {
  // 監査ログは @Async で書かれるため、期待件数になるまで待つ
  for (let i = 0; i < 40; i++) {
    const n = auditCount(alertId)
    if (n === expected) return n
    await new Promise((r) => setTimeout(r, 500))
  }
  return auditCount(alertId)
}

async function joinWithRole(admin: Actor, kind: 'organizations' | 'teams', slug: string, user: Actor, roleId: number) {
  await joinAsMember(kind, admin, slug, user)
  if (roleId !== ROLE_ID.MEMBER) await must(admin, 'PATCH', `/api/v1/${kind}/${slug}/members/${user.id}/role`, { roleId })
}

// ---------------------------------------------------------------------------------------------
// ブラウザ
// ---------------------------------------------------------------------------------------------

interface OpenOptions {
  /** 組織スコープを注入する（組織 ID・名前）。 */
  org?: { id: number; name: string }
  locale?: 'ja' | 'en'
}

/** 新しい文脈で実ログイン（API ログインは前提作成の一部）し、Page を返す。 */
async function openAs(browser: Browser, actor: Actor, options: OpenOptions = {}): Promise<Page> {
  const context = await browser.newContext({
    baseURL: FE,
    storageState: { cookies: [], origins: [] },
    locale: options.locale === 'en' ? 'en-US' : 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    viewport: { width: 1280, height: 900 },
  })
  sessions.push(context)
  if (options.locale) await context.addCookies([{ name: 'i18n_locale', value: options.locale, url: FE }])
  const page = await context.newPage()
  if (options.org) {
    await page.addInitScript(
      (data) => {
        localStorage.setItem('currentScope', JSON.stringify({ type: 'organization', id: String(data.id), name: data.name }))
      },
      options.org,
    )
  }
  await page.goto(`${FE}/robots.txt`)
  await loginViaApi(page, actor, { apiBaseUrl: API, deferNavigation: true })
  return page
}

async function hydrated(page: Page) {
  await page.waitForFunction(
    () => {
      const el = document.querySelector('#__nuxt')
      return el !== null && '__vue_app__' in el && el.childElementCount > 0
    },
    undefined,
    { timeout: 180_000 },
  )
}

async function gotoPage(page: Page, url: string) {
  await page.goto(`${FE}${url}`, { waitUntil: 'commit' })
  await hydrated(page)
}

const ALERTS_URL = '/admin/shift-budget/alerts'
const orgOpt = (): OpenOptions['org'] => ({ id: W.orgO.numericId, name: W.orgO.name })

/** 警告一覧の対象行（ID セルで特定）。 */
const alertRow = (page: Page, alertId: number): Locator =>
  page.locator('tbody tr').filter({ has: page.getByRole('cell', { name: String(alertId), exact: true }) })

async function openAlerts(page: Page) {
  const listed = page.waitForResponse(
    (r) => r.request().method() === 'GET' && /\/api\/v1\/shift-budget\/alerts/.test(new URL(r.url()).pathname),
    { timeout: 120_000 },
  )
  await gotoPage(page, ALERTS_URL)
  return (await listed).status()
}

/** POST acknowledge の送信を数える。 */
function countAckPosts(page: Page): { count: () => number; statuses: number[] } {
  const state = { n: 0, statuses: [] as number[] }
  page.on('request', (r) => {
    if (r.method() === 'POST' && /\/api\/v1\/shift-budget\/alerts\/\d+\/acknowledge$/.test(new URL(r.url()).pathname)) state.n += 1
  })
  page.on('response', (r) => {
    if (r.request().method() === 'POST' && /\/api\/v1\/shift-budget\/alerts\/\d+\/acknowledge$/.test(new URL(r.url()).pathname)) state.statuses.push(r.status())
  })
  return { count: () => state.n, statuses: state.statuses }
}

const ackPostResponse = (page: Page) =>
  page.waitForResponse((r) => r.request().method() === 'POST' && /\/shift-budget\/alerts\/\d+\/acknowledge$/.test(new URL(r.url()).pathname), { timeout: 60_000 })

async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(EVID_DIR, name) })
}

// ---------------------------------------------------------------------------------------------
// セットアップ / 後始末
// ---------------------------------------------------------------------------------------------

test.describe.configure({ mode: 'serial' })

test.beforeAll(async () => {
  test.setTimeout(900_000)
  mkdirSync(EVID_DIR, { recursive: true })
  const orgAdminA = await createActor('c1607orga')
  const orgAdminB = await createActor('c1607orgb')
  const orgMember = await createActor('c1607orgm')
  const otherAdmin = await createActor('c1607othx')
  const teamAdmin = await createActor('c1607tadm')
  const tm1 = await createActor('c1607tm1')
  const tm2 = await createActor('c1607tm2')

  const orgO = await createOrg(orgAdminA, `1607組織O${TAG}`, 'PUBLIC', LEDGER)
  const orgO2 = await createOrg(otherAdmin, `1607組織X${TAG}`, 'PUBLIC', LEDGER)
  await joinWithRole(orgAdminA, 'organizations', orgO.slug, orgAdminB, ROLE_ID.ADMIN)
  await joinWithRole(orgAdminA, 'organizations', orgO.slug, orgMember, ROLE_ID.MEMBER)

  // シフト用チーム（組織とは独立。予算連動で公開が時給検査に当たらないようにする）
  const team = await createTeam(teamAdmin, `1607チーム${TAG}`, 'PUBLIC', LEDGER)
  await joinAsMember('teams', teamAdmin, team.slug, tm1)
  await joinAsMember('teams', teamAdmin, team.slug, tm2)
  const position = await must(teamAdmin, 'POST', `/api/v1/shifts/positions?teamId=${team.numericId}`, { name: `受付${TAG}`, displayOrder: 1 })
  const slotDates = ['2027-03-09', '2027-03-10']
  const schedule = await must(teamAdmin, 'POST', `/api/v1/shifts/schedules?teamId=${team.numericId}`, {
    title: `1607シフト${TAG}`, startDate: '2027-03-08', endDate: '2027-03-14',
  })
  const slotIds: number[] = []
  for (const date of slotDates) {
    const slot = await must(teamAdmin, 'POST', `/api/v1/shifts/schedules/${schedule.data.id}/slots`, {
      slotDate: date, startTime: '09:00:00', endTime: '12:00:00', positionId: position.data.id, requiredCount: 3, note: null,
    })
    slotIds.push(slot.data.id)
  }
  W = {
    orgAdminA, orgAdminB, orgMember, otherAdmin, orgO, orgO2, teamAdmin, tm1, tm2, team,
    positionId: position.data.id, scheduleId: schedule.data.id, slotIds, slotDates,
  }

  const world = {
    note: 'CMP-260910-1607 実機E2E の世界。全ユーザー同一パスワード。ログインは FE のログイン画面から。警告は spec が SQL で前提投入する',
    fe: { base: FE, login: `${FE}/login`, budgetAlerts: `${FE}${ALERTS_URL}`, shiftList: `${FE}/shift`, teamShifts: `${FE}/teams/${team.slug}/shifts` },
    be: API,
    password: 'Passw0rd!2026',
    scopeInjection: `localStorage.currentScope = {"type":"organization","id":"${orgO.numericId}","name":"${orgO.name}"}（組織スコープ切替の UI を経ずに予算画面を開くための前提）`,
    orgO: { ...orgO, owner: 'orgAdminA' },
    orgO2: orgO2,
    team: { ...team, scheduleId: schedule.data.id, slotIds, slotDates },
    users: Object.fromEntries(
      Object.entries({ orgAdminA, orgAdminB, orgMember, otherAdmin, teamAdmin, tm1, tm2 }).map(([k, v]) => [k, { email: v.email, id: v.id, nickname: v.nickname, label: v.label }]),
    ),
    roles: {
      orgAdminA: 'O の ADMIN（BUDGET_ADMIN あり）', orgAdminB: 'O の ADMIN（BUDGET_ADMIN あり）', orgMember: 'O の MEMBER（予算権限なし）',
      otherAdmin: '別組織 X の ADMIN（O から見て他テナント）', teamAdmin: 'シフトチームの ADMIN', tm1: 'シフトチーム MEMBER', tm2: 'シフトチーム MEMBER',
    },
  }
  writeFileSync(path.join(EVID_DIR, 'world.json'), JSON.stringify(world, null, 2))
})

test.afterAll(async () => {
  test.setTimeout(300_000)
  for (const context of sessions) {
    try {
      await context.close()
    } catch (error) {
      note(`context close 失敗: ${String(error)}`)
    }
  }
  if (KEEP_WORLD) {
    note('E2E_KEEP_WORLD=1: 後始末をしない（世界を残す）')
    return
  }
  const failures: string[] = []
  if (W) {
    const s = await apiAs(W.teamAdmin, 'DELETE', `/api/v1/shifts/schedules/${W.scheduleId}`)
    if (![200, 204, 404].includes(s.status)) failures.push(`schedule ${W.scheduleId} -> ${s.status}`)
    const p = await apiAs(W.teamAdmin, 'DELETE', `/api/v1/shifts/positions/${W.positionId}`)
    if (![200, 204, 404].includes(p.status)) failures.push(`position ${W.positionId} -> ${p.status}`)
  }
  // 警告・割当・監査ログは、この実行が作った割当（createdAllocations）と組織に属するものだけを消す
  if (createdAllocations.length) {
    const ids = createdAllocations.join(',')
    sql(`DELETE FROM budget_threshold_alerts WHERE allocation_id IN (${ids}); DELETE FROM shift_budget_allocations WHERE id IN (${ids});`)
  }
  if (W) sql(`DELETE FROM audit_logs WHERE organization_id IN (${W.orgO.numericId}, ${W.orgO2.numericId}) AND event_type='BUDGET_THRESHOLD_ALERT_ACKNOWLEDGED';`)
  for (const record of [...readAssets(LEDGER)].reverse()) {
    const r = await apiAs(record.owner, 'DELETE', record.path)
    if (![200, 204, 404].includes(r.status)) failures.push(`${record.label} -> ${r.status}`)
  }
  const orgs = Number(sql(`SELECT COUNT(*) FROM organizations WHERE deleted_at IS NULL AND name LIKE '%${TAG}%';`))
  const teams = Number(sql(`SELECT COUNT(*) FROM teams WHERE deleted_at IS NULL AND name LIKE '%${TAG}%';`))
  const schedules = Number(sql(`SELECT COUNT(*) FROM shift_schedules WHERE deleted_at IS NULL AND title LIKE '%${TAG}%';`))
  const allocations = Number(sql(`SELECT COUNT(*) FROM shift_budget_allocations WHERE note LIKE '%${TAG}%';`))
  note(`後始末 残件: 組織=${orgs} チーム=${teams} シフト表=${schedules} 予算割当=${allocations} 失敗=${failures.length}`)
  expect(failures, `削除失敗: ${failures.join(' / ')}`).toEqual([])
  expect(orgs, '残った組織').toBe(0)
  expect(teams, '残ったチーム').toBe(0)
  expect(schedules, '残ったシフト表').toBe(0)
  expect(allocations, '残った予算割当').toBe(0)
})

// ---------------------------------------------------------------------------------------------
// S1 確認の正常系
// ---------------------------------------------------------------------------------------------

test('S1 権限あり A: 未確認の警告を画面で確認 → 確認済み表示・DB acknowledged_by=A・監査 1 件', async ({ browser }) => {
  const { alertId } = makeAlert(W.orgO.numericId, W.orgAdminA.id)
  const page = await openAs(browser, W.orgAdminA, { org: orgOpt() })
  const posts = countAckPosts(page)
  expect(await openAlerts(page), '警告一覧の取得は 200').toBe(200)
  const row = alertRow(page, alertId)
  await expect(row, '開始 URL /admin/shift-budget/alerts に警告行がある').toBeVisible()
  await expect(row.getByText('未承認')).toBeVisible()
  await shot(page, 's1-1-before.png')

  await row.getByRole('button', { name: '承認' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await shot(page, 's1-2-dialog.png')
  const [res] = await Promise.all([ackPostResponse(page), page.getByTestId('alert-acknowledge-submit').click()])
  expect(res.status(), '確認の POST は 200').toBe(200)
  await expect(page.getByText('警告を承認しました').first()).toBeVisible()
  // 一覧 API は未確認の警告だけを返す（BudgetThresholdAlertService#list）。確認済みになった行は再取得後の一覧から消える
  await expect(alertRow(page, alertId), '確認済みになり、未確認一覧から消える').toHaveCount(0)
  await shot(page, 's1-3-acked.png')

  expect(ackRow(alertId).by, 'DB の acknowledged_by は A').toBe(W.orgAdminA.id)
  expect(ackRow(alertId).at).not.toBeNull()
  expect(await waitAuditCount(alertId, 1), '監査ログ 1 件').toBe(1)
  expect(posts.count(), 'POST は 1 回').toBe(1)
  note(`S1 OK alert=${alertId} acknowledged_by=${ackRow(alertId).by} audit=1 POST=${posts.count()} status=${posts.statuses}`)
})

// ---------------------------------------------------------------------------------------------
// S2 古い画面での再確認
// ---------------------------------------------------------------------------------------------

/** A と B が同じ未確認の警告の画面を開き、A が先に確認する。B は古い画面のまま返す。 */
async function staleSetup(browser: Browser, alertId: number, locale: 'ja' | 'en' = 'ja') {
  const pageB = await openAs(browser, W.orgAdminB, { org: orgOpt(), locale })
  expect(await openAlerts(pageB)).toBe(200)
  await expect(alertRow(pageB, alertId)).toBeVisible()
  const pageA = await openAs(browser, W.orgAdminA, { org: orgOpt(), locale })
  expect(await openAlerts(pageA)).toBe(200)
  const ackLabel = locale === 'en' ? 'Acknowledge' : '承認'
  await alertRow(pageA, alertId).getByRole('button', { name: ackLabel }).click()
  const [res] = await Promise.all([ackPostResponse(pageA), pageA.getByTestId('alert-acknowledge-submit').click()])
  expect(res.status(), 'A の確認は 200').toBe(200)
  await expect(alertRow(pageA, alertId), 'A の画面で確認済みの行が一覧から消える').toHaveCount(0)
  expect(ackRow(alertId).by).toBe(W.orgAdminA.id)
  return { pageA, pageB, ackLabel }
}

test('S2-a 古い画面の B が確認 → 氏名入りの上書き確認ダイアログ。キャンセルで一覧が再取得され DB は A のまま', async ({ browser }) => {
  const { alertId } = makeAlert(W.orgO.numericId, W.orgAdminA.id)
  const { pageB } = await staleSetup(browser, alertId)
  const auditBefore = await waitAuditCount(alertId, 1)
  const at = ackRow(alertId).at

  // B は古い画面のまま「承認」ボタンが見えている
  const rowB = alertRow(pageB, alertId)
  await expect(rowB.getByText('未承認'), 'B の画面は古いまま（未承認表示）').toBeVisible()
  await rowB.getByRole('button', { name: '承認' }).click()
  const [res] = await Promise.all([ackPostResponse(pageB), pageB.getByTestId('alert-acknowledge-submit').click()])
  expect(res.status(), '確認済みへの再確認は 409').toBe(409)
  const body = await res.json()
  note(`S2-a 409 応答本文: ${JSON.stringify(body)}`)

  const message = pageB.getByTestId('alert-overwrite-message')
  await expect(message).toBeVisible()
  const text = (await message.innerText()).trim()
  note(`S2-a 上書き確認ダイアログの文言: ${text}`)
  expect(text).toMatch(/さんが.+に確認済みです。上書きしますか？/)
  const nameMatch = text.match(/^(.+)さんが/)
  expect(nameMatch?.[1]?.trim().length ?? 0, '氏名が空でない').toBeGreaterThan(0)
  expect(text, '汎用の「別の管理者」ではなく具体の氏名').not.toContain('別の管理者')
  await shot(pageB, 's2a-1-overwrite-dialog.png')

  const reload = pageB.waitForResponse((r) => r.request().method() === 'GET' && /\/shift-budget\/alerts/.test(new URL(r.url()).pathname))
  await pageB.getByTestId('alert-overwrite-cancel').click()
  expect((await reload).status(), 'キャンセルで一覧を再取得').toBe(200)
  await expect(alertRow(pageB, alertId), '一覧が再取得され、確認済みの行は未確認一覧から消える（古い「承認」ボタンが残らない）').toHaveCount(0)
  await shot(pageB, 's2a-2-after-cancel.png')

  expect(ackRow(alertId), 'DB は A のまま（確認者・時刻とも不変）').toEqual({ by: W.orgAdminA.id, at })
  await new Promise((r) => setTimeout(r, 1500))
  expect(auditCount(alertId), '監査ログは増えない').toBe(auditBefore)
})

test('S2-b 古い画面の B が確認 → 上書きする → DB が B に変わり監査ログが増える', async ({ browser }) => {
  const { alertId } = makeAlert(W.orgO.numericId, W.orgAdminA.id)
  const { pageB } = await staleSetup(browser, alertId)
  expect(await waitAuditCount(alertId, 1)).toBe(1)

  await alertRow(pageB, alertId).getByRole('button', { name: '承認' }).click()
  const [first] = await Promise.all([ackPostResponse(pageB), pageB.getByTestId('alert-acknowledge-submit').click()])
  expect(first.status()).toBe(409)
  await expect(pageB.getByTestId('alert-overwrite-message')).toBeVisible()
  await shot(pageB, 's2b-1-overwrite-dialog.png')

  const [second] = await Promise.all([ackPostResponse(pageB), pageB.getByTestId('alert-overwrite-submit').click()])
  expect(second.status(), '上書き指定の再送は 200').toBe(200)
  expect(JSON.parse(second.request().postData() ?? '{}').overwrite, '再送は overwrite:true').toBe(true)
  await expect(pageB.getByText('警告を承認しました').first()).toBeVisible()
  await expect(alertRow(pageB, alertId)).toHaveCount(0)
  await shot(pageB, 's2b-2-overwritten.png')

  expect(ackRow(alertId).by, 'DB の acknowledged_by が B に変わる').toBe(W.orgAdminB.id)
  expect(await waitAuditCount(alertId, 2), '監査ログが 2 件（A の確認 + B の上書き）').toBe(2)
  note(`S2-b OK alert=${alertId} acknowledged_by=${ackRow(alertId).by} audit=${auditCount(alertId)}`)
})

// ---------------------------------------------------------------------------------------------
// S3 連打
// ---------------------------------------------------------------------------------------------

test('S3 確認ボタンの連打で POST は 1 回だけ', async ({ browser }) => {
  const { alertId } = makeAlert(W.orgO.numericId, W.orgAdminA.id)
  const page = await openAs(browser, W.orgAdminA, { org: orgOpt() })
  const posts = countAckPosts(page)
  expect(await openAlerts(page)).toBe(200)
  await alertRow(page, alertId).getByRole('button', { name: '承認' }).click()
  const submit = page.getByTestId('alert-acknowledge-submit')
  await expect(submit).toBeVisible()
  // 1 回のイベントループ内で 5 連打する（実ユーザーの連打より厳しい）
  await submit.evaluate((el) => {
    for (let i = 0; i < 5; i++) (el as HTMLButtonElement).click()
  })
  // 送信中にもう一度クリック（無効化・消滅済みなら何も起きない。存在確認は DOM 側で行い、失敗は握りつぶさない）
  await page.evaluate(() => {
    document.querySelector<HTMLButtonElement>('[data-testid="alert-acknowledge-submit"]')?.click()
  })
  await expect(alertRow(page, alertId)).toHaveCount(0)
  await new Promise((r) => setTimeout(r, 1500))
  expect(posts.count(), '連打しても POST は 1 回').toBe(1)
  expect(posts.statuses, '応答は 200 のみ（409 が混ざらない）').toEqual([200])
  expect(await waitAuditCount(alertId, 1)).toBe(1)
  note(`S3 OK alert=${alertId} POST回数=${posts.count()} statuses=${posts.statuses}`)
})

// ---------------------------------------------------------------------------------------------
// S4 権限なし・他テナント
// ---------------------------------------------------------------------------------------------

for (const who of ['orgMember', 'otherAdmin'] as const) {
  test(`S4 ${who === 'orgMember' ? '権限なし MEMBER M' : '他組織の管理者 X'}: 警告画面・API とも拒否され、警告は見えず確認もできない`, async ({ browser }) => {
    const { alertId } = makeAlert(W.orgO.numericId, W.orgAdminA.id)
    const actor = W[who]
    const page = await openAs(browser, actor, { org: orgOpt() })
    // URL 直打ちで警告画面を開く（組織 O のスコープ）
    const status = await openAlerts(page)
    note(`S4 ${who} GET alerts 画面→API の応答 ${status}`)
    expect([403, 404], '警告一覧 API は拒否される').toContain(status)
    await expect(page.getByRole('cell', { name: String(alertId), exact: true }), '警告の行は出ない').toHaveCount(0)
    await expect(page.getByRole('button', { name: '承認', exact: true }), '承認ボタンの導線が出ない').toHaveCount(0)
    await page.waitForTimeout(800)
    const bodyText = (await page.locator('body').innerText()).replace(/\s+/g, ' ')
    note(`S4 ${who} 画面の文言: ${bodyText.slice(0, 400)}`)
    await shot(page, `s4-${who}-alerts.png`)

    // 直接 API（FE の出し分けだけで BE が素通りしていないかの検査）
    const headers = { 'X-Organization-Id': String(W.orgO.numericId) }
    const listRes = await page.request.get(`${API}/api/v1/shift-budget/alerts?page=0&size=20`, { headers })
    const ackRes = await page.request.post(`${API}/api/v1/shift-budget/alerts/${alertId}/acknowledge`, { headers, data: { comment: 'S4' } })
    note(`S4 ${who} 直接API: GET alerts=${listRes.status()} POST acknowledge=${ackRes.status()} ${await ackRes.text()}`)
    expect([403, 404]).toContain(listRes.status())
    expect([403, 404]).toContain(ackRes.status())
    expect(ackRow(alertId), '警告は未確認のまま').toEqual({ by: null, at: null })
  })
}

// ---------------------------------------------------------------------------------------------
// S5 シフトの通常操作
// ---------------------------------------------------------------------------------------------

const dialogOf = (page: Page) => page.getByRole('dialog')
const slotRow = (page: Page, index: number): Locator => page.locator('tbody tr').nth(index)
const addButton = (row: Locator) => row.getByRole('button', { name: /^\+/ })
const chipIn = (row: Locator, actor: Actor) => row.locator('.rounded-full', { hasText: actor.nickname })
const patchAssignments = (page: Page) =>
  page.waitForResponse((r) => r.request().method() === 'PATCH' && /\/api\/v1\/shifts\/slots\/\d+\/assignments$/.test(new URL(r.url()).pathname))

async function assignedOf(slotId: number): Promise<number[]> {
  const r = await must(W.teamAdmin, 'GET', `/api/v1/shifts/schedules/${W.scheduleId}/slots`)
  return ((r.data as Array<{ id: number; assignedUserIds: number[] }>).find((s) => s.id === slotId)?.assignedUserIds ?? []).sort()
}

async function transition(page: Page, label: string, expectedStatus: string) {
  const [res] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === 'POST' && /\/shifts\/schedules\/\d+\/transition/.test(new URL(r.url()).pathname)),
    page.getByRole('button', { name: label }).click(),
  ])
  expect(res.status(), `${label} は 200`).toBe(200)
  await expect(page.getByText('ステータスを変更しました').first()).toBeVisible()
  const status = sql(`SELECT status FROM shift_schedules WHERE id=${W.scheduleId};`)
  expect(status).toBe(expectedStatus)
}

test('S5-a シフト表の状態遷移（希望収集→調整）とシフト表の編集保存が画面で成功する', async ({ browser }) => {
  const page = await openAs(browser, W.teamAdmin)
  await gotoPage(page, `/shift/${W.scheduleId}`)
  await expect(page.getByRole('button', { name: '希望収集を開始' })).toBeVisible({ timeout: 60_000 })
  await shot(page, 's5a-1-draft.png')
  await transition(page, '希望収集を開始', 'COLLECTING')
  await transition(page, '調整を開始', 'ADJUSTING')

  // シフト表の編集（題名・メモ）を保存
  await page.getByRole('button', { name: '編集' }).first().click()
  const dialog = dialogOf(page)
  await expect(dialog).toBeVisible()
  const newTitle = `1607シフト編集後${TAG}`
  await dialog.locator('input').first().fill(newTitle)
  await dialog.locator('input').nth(1).fill('実機E2Eのメモ')
  const [res] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === 'PATCH' && new RegExp(`/shifts/schedules/${W.scheduleId}$`).test(new URL(r.url()).pathname)),
    dialog.getByRole('button', { name: '保存' }).click(),
  ])
  expect(res.status(), 'シフト表の編集保存は 200（400 化で壊れていない）').toBe(200)
  await expect(page.getByText('保存しました').first()).toBeVisible()
  await expect(page.getByText(newTitle).first()).toBeVisible()
  expect(sql(`SELECT title FROM shift_schedules WHERE id=${W.scheduleId};`)).toBe(newTitle)
  await shot(page, 's5a-2-edited.png')
})

test('S5-b 枠の編集保存（/shift/{id}/edit）が画面で成功する', async ({ browser }) => {
  const page = await openAs(browser, W.teamAdmin)
  await gotoPage(page, `/shift/${W.scheduleId}/edit`)
  const pencil = page.locator('button:has(.pi-pencil)').first()
  await expect(pencil).toBeVisible({ timeout: 60_000 })
  await pencil.click()
  const dialog = dialogOf(page)
  await expect(dialog).toBeVisible()
  await dialog.locator('input[type="time"]').nth(1).fill('13:00')
  const [res] = await Promise.all([
    page.waitForResponse((r) => ['PATCH', 'PUT'].includes(r.request().method()) && /\/shifts\/(schedules\/\d+\/)?slots\/\d+$/.test(new URL(r.url()).pathname)),
    dialog.getByRole('button', { name: '保存' }).click(),
  ])
  expect(res.status(), '枠の編集保存は 200').toBe(200)
  await expect(dialog).toBeHidden()
  const slots = (await must(W.teamAdmin, 'GET', `/api/v1/shifts/schedules/${W.scheduleId}/slots`)).data as Array<{ id: number; time: { endTime: string } }>
  const edited = slots.find((x) => x.id === W.slotIds[0])
  expect(edited?.time.endTime, '枠 1 の終了時刻が画面で入力した 13:00 で保存されている').toMatch(/^13:00/)
  await shot(page, 's5b-slot-edited.png')
})

test('S5-c ボードで割当の追加・解除が画面で成功する', async ({ browser }) => {
  const page = await openAs(browser, W.teamAdmin)
  await gotoPage(page, `/teams/${W.team.slug}/shifts/${W.scheduleId}/board`)
  await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 60_000 })

  // 枠 1 に tm1、枠 2 に tm1 と tm2 をダイアログから追加
  const addVia = async (rowIndex: number, actor: Actor) => {
    await addButton(slotRow(page, rowIndex)).click()
    const dialog = dialogOf(page)
    await expect(dialog).toBeVisible()
    const [res] = await Promise.all([patchAssignments(page), dialog.getByText(actor.nickname, { exact: true }).click()])
    expect(res.status(), `${actor.label} の追加は 200`).toBe(200)
    await expect(dialog).toBeHidden()
    await expect(chipIn(slotRow(page, rowIndex), actor)).toBeVisible()
  }
  await addVia(0, W.tm1)
  await addVia(1, W.tm1)
  await addVia(1, W.tm2)
  // 枠 2 から tm2 を解除（チップの削除ボタン）
  const [removed] = await Promise.all([patchAssignments(page), chipIn(slotRow(page, 1), W.tm2).getByRole('button', { name: '削除' }).click()])
  expect(removed.status(), '割当解除は 200').toBe(200)
  await expect(chipIn(slotRow(page, 1), W.tm2)).toHaveCount(0)
  await shot(page, 's5c-board.png')
  expect(await assignedOf(W.slotIds[0]!)).toEqual([W.tm1.id])
  expect(await assignedOf(W.slotIds[1]!)).toEqual([W.tm1.id])
})

test('S5-d シフト表の確定公開 → 交代依頼の承諾（tm2）→ 承認・却下（管理者）が画面で成功する', async ({ browser }) => {
  const admin = await openAs(browser, W.teamAdmin)
  await gotoPage(admin, `/shift/${W.scheduleId}`)
  await expect(admin.getByRole('button', { name: '確定公開' })).toBeVisible({ timeout: 60_000 })
  await transition(admin, '確定公開', 'PUBLISHED')
  await expect(admin.getByText('確定済み').first()).toBeVisible()
  await shot(admin, 's5d-1-published.png')

  // 前提: tm1 が 2 つの枠の交代を tm2 へ依頼（起票は API。承諾・承認・却下は画面）
  const swapIds: number[] = []
  for (const slotId of W.slotIds) {
    const swap = await must(W.tm1, 'POST', '/api/v1/shifts/swap-requests', { slotId, targetUserIds: [W.tm2.id], openCall: false, reason: `1607交代${TAG}` })
    swapIds.push(swap.data.id)
  }
  note(`S5-d 交代依頼を API で起票（前提）: ${swapIds}`)

  const swapsUrl = `/teams/${W.team.slug}/shifts?tab=swaps`
  const member = await openAs(browser, W.tm2)
  await gotoPage(member, swapsUrl)
  const accepts = member.getByRole('button', { name: '承諾' })
  await expect(accepts.first()).toBeVisible({ timeout: 60_000 })
  for (let i = 0; i < swapIds.length; i++) {
    const [res] = await Promise.all([
      member.waitForResponse((r) => r.request().method() === 'POST' && /swap-requests\/\d+\/accept/.test(new URL(r.url()).pathname)),
      member.getByRole('button', { name: '承諾' }).first().click(),
    ])
    expect(res.status(), '交代の承諾は 200').toBe(200)
    await expect(member.getByText('交代を承諾しました').first()).toBeVisible()
  }
  await shot(member, 's5d-2-accepted.png')

  await gotoPage(admin, swapsUrl)
  await expect(admin.getByRole('button', { name: '承認', exact: true }).first()).toBeVisible({ timeout: 60_000 })
  const [approved] = await Promise.all([
    admin.waitForResponse((r) => r.request().method() === 'POST' && /swap-requests\/\d+\/(approve|resolve)/.test(new URL(r.url()).pathname)),
    admin.getByRole('button', { name: '承認', exact: true }).first().click(),
  ])
  expect(approved.status(), '交代の承認は 200').toBe(200)
  await expect(admin.getByText('交代を承認しました').first()).toBeVisible()
  const [rejected] = await Promise.all([
    admin.waitForResponse((r) => r.request().method() === 'POST' && /swap-requests\/\d+\/(reject|resolve)/.test(new URL(r.url()).pathname)),
    admin.getByRole('button', { name: '却下', exact: true }).first().click(),
  ])
  expect(rejected.status(), '交代の却下は 200').toBe(200)
  await expect(admin.getByText('交代を却下しました').first()).toBeVisible()
  await shot(admin, 's5d-3-resolved.png')
  const statuses = sql(`SELECT status FROM shift_swap_requests WHERE id IN (${swapIds.join(',')}) ORDER BY id;`).split('\n').sort()
  expect(statuses, '承認 1 件・却下 1 件').toEqual(['APPROVED', 'REJECTED'])
})

// ---------------------------------------------------------------------------------------------
// S6 en
// ---------------------------------------------------------------------------------------------

test('S6 言語 en: 上書き確認ダイアログに日本語・生キーが出ない', async ({ browser }) => {
  const { alertId } = makeAlert(W.orgO.numericId, W.orgAdminA.id)
  // 表示言語はアカウント設定（users.locale）が決める。前提として A・B のアカウント言語を en にし、終わったら ja へ戻す
  const both = `${W.orgAdminA.id}, ${W.orgAdminB.id}`
  sql(`UPDATE users SET locale='en' WHERE id IN (${both});`)
  try {
    await s6Body(browser, alertId)
  } finally {
    sql(`UPDATE users SET locale='ja' WHERE id IN (${both});`)
  }
})

async function s6Body(browser: Browser, alertId: number) {
  const { pageB } = await staleSetup(browser, alertId, 'en')
  await alertRow(pageB, alertId).getByRole('button', { name: 'Acknowledge' }).click()
  const [res] = await Promise.all([ackPostResponse(pageB), pageB.getByTestId('alert-acknowledge-submit').click()])
  expect(res.status()).toBe(409)
  const dialog = pageB.getByRole('dialog', { name: 'Alert already acknowledged' })
  const message = pageB.getByTestId('alert-overwrite-message')
  await expect(message).toBeVisible()
  const header = (await dialog.locator('.p-dialog-title, [class*="dialog-title"]').first().innerText()).trim()
  const text = (await message.innerText()).trim()
  const whole = (await dialog.innerText()).trim()
  note(`S6 header="${header}" message="${text}" dialog="${whole.replace(/\n/g, ' | ')}"`)
  await shot(pageB, 's6-en-overwrite-dialog.png')
  expect(header).toBe('Alert already acknowledged')
  expect(text).toMatch(/already acknowledged this alert at .+ Do you want to overwrite it\?/)
  expect(whole).toContain('Overwrite')
  expect(whole, '生キーが出ない').not.toMatch(/shiftBudget\.|alert\.alreadyAcknowledged/)
  // 氏名（利用者データ）を除いた固定文言に日本語が混ざらない
  const fixed = whole.replace(W.orgAdminA.label, '').replace(/検証/g, '')
  expect(fixed, '固定文言に日本語が混ざらない').not.toMatch(/[぀-ヿ㐀-鿿]/)
  await pageB.getByTestId('alert-overwrite-cancel').click()}
