/**
 * CMP-260909-0543 実機 E2E — シフト予算の一覧取得失敗が「登録されていません」の空状態に化けない
 *
 * 対象画面（同ディレクトリの同型 catch を直した 3 画面）:
 *   /admin/shift-budget/allocations（割当一覧）
 *   /admin/shift-budget/dashboard  （予算消化ダッシュボード）
 *   /admin/shift-budget/alerts     （予算警告履歴）
 *
 * 受け入れ条件
 *   AC-1 一覧取得が失敗したとき、「データが無い」と読める空状態に落ちず、失敗した旨と再試行手段が画面内に恒久表示される
 *        （トーストは時間で消えるため、画面内の表示であること）
 *   AC-2 成功して 0 件（本当にデータが無い）の空状態とは区別される
 *
 * 実 BE を失敗させる方法（共有の BE/DB は止めない・壊さない）
 *   - 403: 権限のないユーザー（組織の一般 MEMBER・他テナント）で開く。BE が SHIFT_BUDGET_018 / 403 を返す正当な経路。
 *   - 通信断（補助。実 BE の失敗ではない）: 取得リクエスト 1 本をブラウザ側で接続失敗にする障害注入（LOAD-04）。
 *     応答の中身は差し替えない。通信を戻して「再試行」を押し、実 BE から取得し直せることまで確認する。
 *     （setOffline は dev server のページ分割チャンク取得まで失敗し、画面遷移自体が起きないため使えない）
 *   - 500（サーバー障害）は、共有 BE/DB を壊さずに実環境で起こせる正当な手段が無い（組織別の無効化フラグを
 *     切り替える API も無い）ため実機では未実施。FE 部品の 500 分岐は単体テストの範囲。
 *
 * 前提: feature.shift-budget.enabled=true の BE（既定は OFF のため、検証用 BE を別ポートで起動して実施する）
 *
 * 実行例:
 *   BASE_URL=http://localhost:3002 API_BASE_URL=http://localhost:8081 \
 *     npx playwright test tests/e2e/real/cmp-260909-0543-load-error-state.real.spec.ts
 */

import {
  test,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type Browser,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const BE_API = `${API_BASE_URL}/api/v1`
const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

/** 組織 11「E2Eテストマンション管理組合」(slug=e2e)。e2e-user が ADMIN（BUDGET_ADMIN / BUDGET_VIEW を持つ）。 */
const ORG_ID = 11
const ORG_SLUG = 'e2e'
const ORG_NAME = 'E2Eテストマンション管理組合'
const ADMIN = { email: 'e2e-user@test.mannschaft.local', password: PASSWORD }
/** 組織 11 に所属しない seed ユーザー。テスト中だけ MEMBER として招待加入させ、afterAll で除去する。 */
const MEMBER = { email: 'e2e-dummy-18@test.mannschaft.local', password: PASSWORD }
/** どの組織にも所属しない seed ユーザー（他テナント）。 */
const OUTSIDER = { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD }

const SHOT = process.env.EVIDENCE_DIR ?? '../docs/evidence/CMP-260909-0543'
const RUN_TAG = `R${Date.now().toString(36)}`
const PERIOD_START = '2046-05-01'
const PERIOD_END = '2046-05-31'

const PAGES = {
  allocations: { path: '/admin/shift-budget/allocations', errorTestId: 'shift-budget-allocations-error-state', emptyText: '割当が登録されていません' },
  dashboard: { path: '/admin/shift-budget/dashboard', errorTestId: 'shift-budget-dashboard-error-state', emptyText: '割当が登録されていません' },
  alerts: { path: '/admin/shift-budget/alerts', errorTestId: 'shift-budget-alerts-error-state', emptyText: '警告はありません' },
} as const
type PageKey = keyof typeof PAGES

const FORBIDDEN_TITLE = 'この画面をご利用いただけません'
const NETWORK_TITLE = '通信がうまくいかなかったようです'
/** 通知トーストの表示時間より十分長く待ち、「画面内の恒久表示」であることを確かめる。 */
const PERSIST_MS = 8_000

test.describe.configure({ mode: 'serial' })
test.setTimeout(180_000)

let api: APIRequestContext
let adminToken = ''
let fiscalYearId = 0
let categoryId = 0
let allocationId = 0
let memberUserId = 0
let inviteTokenId = 0
let memberJoined = false
let adminPage: Page
let memberPage: Page
let outsiderPage: Page

function bearer(token: string, withOrg = false): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
    ...(withOrg ? { 'X-Organization-Id': String(ORG_ID) } : {}),
  }
}

async function loginToken(cred: { email: string; password: string }): Promise<{ token: string; id: number }> {
  for (let attempt = 0; attempt < 6; attempt++) {
    const res = await api.post(`${BE_API}/auth/login`, { data: cred })
    if (res.status() === 429) {
      await new Promise(r => setTimeout(r, 15_000))
      continue
    }
    expect(res.status(), `login(${cred.email}) は 200`).toBe(200)
    const token = ((await res.json()) as { data: { accessToken: string } }).data.accessToken
    const me = await api.get(`${BE_API}/users/me`, { headers: bearer(token) })
    return { token, id: ((await me.json()) as { data: { id: number } }).data.id }
  }
  throw new Error(`login(${cred.email}) が 429 のまま`)
}

/** 実ログイン済み・組織 11 スコープ注入済みの Page を作る（ログイン連打を避けるためロールごとに 1 回だけ作る）。 */
async function openAs(browser: Browser, cred: { email: string; password: string }): Promise<Page> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  await page.addInitScript(
    (data) => {
      localStorage.setItem('currentScope', JSON.stringify({ type: 'organization', id: String(data.id), name: data.name }))
    },
    { id: ORG_ID, name: ORG_NAME },
  )
  for (let attempt = 0; attempt < 6; attempt++) {
    try {
      await loginViaApi(page, cred, { apiBaseUrl: API_BASE_URL })
      return page
    }
    catch (e) {
      if (!String(e).includes('429')) throw e
      await new Promise(r => setTimeout(r, 15_000))
    }
  }
  throw new Error(`UI セッション用ログイン(${cred.email}) が 429 のまま`)
}

async function gotoPage(page: Page, key: PageKey) {
  await page.goto(PAGES[key].path)
  await waitForHydration(page)
}

/** 後始末は失敗しても残りを続けるが、握りつぶさず警告に残す。 */
async function cleanup(label: string, run: () => Promise<{ status(): number }>) {
  try {
    const res = await run()
    if (![200, 204, 404].includes(res.status())) console.warn(`[後始末] ${label}: ${res.status()}`)
  }
  catch (e) {
    console.warn(`[後始末] ${label} に失敗: ${String(e)}`)
  }
}

test.beforeAll(async ({ browser }) => {
  api = await pwRequest.newContext()
  adminToken = (await loginToken(ADMIN)).token

  // 本物のデータがある状態を作るための会計年度・費目（割当は LOAD-04 で作る。それまでは組織 11 は 0 件）
  const fy = await api.post(`${BE_API}/organizations/${ORG_ID}/budget/fiscal-years`, {
    headers: bearer(adminToken),
    data: { name: `${RUN_TAG} 会計年度`, startDate: '2046-04-01', endDate: '2047-03-31' },
  })
  expect(fy.status(), `会計年度作成は 201（${await fy.text()}）`).toBe(201)
  fiscalYearId = ((await fy.json()) as { data: { id: number } }).data.id
  const cat = await api.post(
    `${BE_API}/budget/fiscal-years/${fiscalYearId}/categories?scopeId=${ORG_ID}&scopeType=ORGANIZATION`,
    { headers: bearer(adminToken), data: { fiscalYearId, name: `${RUN_TAG} 費目`, categoryType: 'EXPENSE' } },
  )
  expect(cat.status(), `費目作成は 201（${await cat.text()}）`).toBe(201)
  categoryId = ((await cat.json()) as { data: { id: number } }).data.id

  // 権限なしの視点（組織 11 の一般 MEMBER）
  const member = await loginToken(MEMBER)
  memberUserId = member.id
  const inv = await api.post(`${BE_API}/organizations/${ORG_SLUG}/invite-tokens`, {
    headers: bearer(adminToken),
    data: { roleId: 4, expiresIn: '1d', maxUses: 1 },
  })
  expect(inv.status(), `招待トークン作成は 201（${await inv.text()}）`).toBe(201)
  const invBody = ((await inv.json()) as { data: { id: number; token: string } }).data
  inviteTokenId = invBody.id
  const join = await api.post(`${BE_API}/invite/${invBody.token}/join`, { headers: bearer(member.token) })
  expect(join.ok(), `MEMBER 加入は成功（${join.status()} ${await join.text()}）`).toBe(true)
  memberJoined = true

  adminPage = await openAs(browser, ADMIN)
  memberPage = await openAs(browser, MEMBER)
  outsiderPage = await openAs(browser, OUTSIDER)
})

test.afterAll(async () => {
  // 自分が作った分だけ削除する
  if (allocationId) {
    await cleanup('割当', () => api.delete(`${BE_API}/shift-budget/allocations/${allocationId}`, { headers: bearer(adminToken, true) }))
  }
  if (categoryId) {
    await cleanup('費目', () => api.delete(`${BE_API}/budget/categories/${categoryId}?scopeId=${ORG_ID}&scopeType=ORGANIZATION`, { headers: bearer(adminToken) }))
  }
  if (fiscalYearId) {
    await cleanup('会計年度', () => api.delete(`${BE_API}/budget/fiscal-years/${fiscalYearId}?scopeId=${ORG_ID}&scopeType=ORGANIZATION`, { headers: bearer(adminToken) }))
  }
  if (memberJoined) {
    await cleanup('MEMBER 加入', () => api.delete(`${BE_API}/organizations/${ORG_SLUG}/members/${memberUserId}`, { headers: bearer(adminToken) }))
  }
  if (inviteTokenId) {
    await cleanup('招待トークン', () => api.delete(`${BE_API}/organizations/${ORG_SLUG}/invite-tokens/${inviteTokenId}`, { headers: bearer(adminToken) }))
  }
  for (const p of [adminPage, memberPage, outsiderPage]) await p?.context().close()
  await api?.dispose()
})

test('LOAD-01 (AC-2・成功して 0 件): 本当にデータが無い組織では、各画面は空状態を表示し、取得失敗の表示にはならない', async () => {
  const page = adminPage

  await gotoPage(page, 'allocations')
  await expect(page.getByText(PAGES.allocations.emptyText), '割当一覧: 0 件の空状態').toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId(PAGES.allocations.errorTestId), '取得失敗の表示は出ない').toHaveCount(0)

  await gotoPage(page, 'alerts')
  await expect(page.getByText(PAGES.alerts.emptyText), '警告履歴: 0 件の空状態').toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId(PAGES.alerts.errorTestId)).toHaveCount(0)

  await gotoPage(page, 'dashboard')
  await expect(page.getByText('警告 (80% 以上)'), 'ダッシュボード: KPI が描画される').toBeVisible({ timeout: 30_000 })
  await expect(page.getByText(PAGES.dashboard.emptyText), 'ダッシュボード: 割当 0 件の空状態').toBeVisible()
  await expect(page.getByTestId(PAGES.dashboard.errorTestId)).toHaveCount(0)
  await page.screenshot({ path: SHOT + '/load-01-empty-dashboard.png' })
})

for (const [id, label, who] of [
  ['LOAD-02', '権限なし（組織の一般 MEMBER）', () => memberPage],
  ['LOAD-03', '他テナント（どの組織にも非所属）', () => outsiderPage],
] as const) {
  test(`${id} (AC-1・実 BE の 403): ${label} が URL 直打ちで開くと、3 画面とも空状態ではなく取得失敗の表示が恒久的に出る`, async () => {
    const page = who()
    for (const key of Object.keys(PAGES) as PageKey[]) {
      await gotoPage(page, key)
      const errorState = page.getByTestId(PAGES[key].errorTestId)
      await expect(errorState, `${key}: 取得失敗の表示が出る`).toBeVisible({ timeout: 30_000 })
      await expect(errorState).toContainText(FORBIDDEN_TITLE)
      await expect(errorState.getByRole('button', { name: '再試行' }), '403 は再試行しても直らないので再試行ボタンは出さない').toHaveCount(0)
      await expect(page.getByText(PAGES[key].emptyText), `${key}: 「データが無い」に化けない`).toHaveCount(0)
      if (key === 'dashboard') {
        await expect(page.getByText('警告 (80% 以上)'), 'ダッシュボード: KPI 0 件に化けない').toHaveCount(0)
      }
      // トーストは時間で消える。消えた後も画面内の表示は残る。
      await page.waitForTimeout(PERSIST_MS)
      await expect(errorState, `${key}: トースト消滅後も表示が残る`).toBeVisible()
      await expect(errorState).toContainText(FORBIDDEN_TITLE)
      console.log(`[${id} ${label} ${key}] ${(await errorState.innerText()).replace(/\s+/g, ' ')}`)
      if (id === 'LOAD-02' && key === 'alerts') await page.screenshot({ path: SHOT + '/load-02-member-alerts.png' })
    }
  })
}

test('LOAD-04 (AC-1・再試行。障害注入の補助確認): 取得リクエストが通信失敗になると、空状態ではなく失敗表示と再試行が出て、再試行で実データが出る', async () => {
  // 注意: これは「実 BE の失敗」ではない。BE・DB には触れず、ブラウザから BE への取得リクエスト
  //       1 本だけを接続失敗にする障害注入（応答の中身は差し替えない）。実 BE の失敗（403）は LOAD-02/03 で確認済み。
  //       再試行ボタンの導線と、再試行後に実 BE のデータが出ることを確かめるために使う。
  const page = adminPage

  // 前提: 組織 11 に割当を 1 件（API）。「本当にデータがある」状態で、失敗が空状態に化けないことを見る
  const created = await api.post(`${BE_API}/shift-budget/allocations`, {
    headers: bearer(adminToken, true),
    data: {
      fiscal_year_id: fiscalYearId, budget_category_id: categoryId,
      period_start: PERIOD_START, period_end: PERIOD_END, allocated_amount: 80000, currency: 'JPY', note: RUN_TAG,
    },
  })
  expect(created.status(), `割当作成は 201（${await created.text()}）`).toBe(201)
  allocationId = ((await created.json()) as { data: { id: number } }).data.id
  const periodText = `${PERIOD_START} 〜 ${PERIOD_END}`
  const shiftBudgetApi = '**/api/v1/shift-budget/**'

  for (const key of ['allocations', 'dashboard', 'alerts'] as const) {
    await page.route(shiftBudgetApi, route => route.abort('connectionfailed'))
    await gotoPage(page, key)
    const errorState = page.getByTestId(PAGES[key].errorTestId)
    await expect(errorState, `${key}: 取得失敗の表示`).toBeVisible({ timeout: 30_000 })
    await expect(errorState).toContainText(NETWORK_TITLE)
    await expect(page.getByText(PAGES[key].emptyText), `${key}: 空状態に化けない（割当は 1 件ある）`).toHaveCount(0)
    if (key === 'dashboard') await expect(page.getByText('警告 (80% 以上)'), 'KPI 0 件に化けない').toHaveCount(0)
    await page.waitForTimeout(PERSIST_MS)
    await expect(errorState, `${key}: トースト消滅後も残る`).toBeVisible()
    if (key === 'allocations') await page.screenshot({ path: SHOT + '/load-04-allocations-network.png' })

    // 通信を戻して「再試行」→ 実 BE から取得し直せる
    await page.unroute(shiftBudgetApi)
    await errorState.getByRole('button', { name: '再試行' }).click()
    await expect(page.getByTestId(PAGES[key].errorTestId), `${key}: 再試行後は失敗表示が消える`).toHaveCount(0)
    if (key === 'alerts') {
      await expect(page.getByText(PAGES.alerts.emptyText), '再試行で成功すると、本当に 0 件なので空状態が出る').toBeVisible({ timeout: 30_000 })
    }
    else {
      await expect(page.getByText(periodText).first(), `${key}: 再試行で実データが出る`).toBeVisible({ timeout: 30_000 })
    }
  }
})
