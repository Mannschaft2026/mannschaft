/**
 * CMP-260909-0544 実機 E2E（権限・空状態の部分） — 失敗イベント画面
 *
 * 対象画面: /admin/shift-budget/failed-events
 *
 * 受け入れ条件（台帳の完了条件 + PR #4743）
 *   AC-1 画面に SQL 文・テーブル名・カラム名・Java 例外クラス名・生の enum コードが出ない  ← 実データが必要
 *   AC-2 権限あり（閲覧できる）/ 権限なし（到達できない）をロール横断で確認する
 *
 * このスペックが扱う範囲
 *   - 実行できるのは AC-2（ロール横断）と、失敗イベントが 0 件のときの空状態まで。
 *   - AC-1 は「実データ（失敗イベントの行）」が画面に出ている状態が前提だが、実 BE に失敗イベントを
 *     発生させる正当な操作が無い（後述）ため **未実施**。共有 DB への直接 DML は禁止されているので作っていない。
 *
 * 失敗イベントが発生する経路（BE を読んだ結果）
 *   失敗イベントは API から作成できない。以下の内部処理が例外を捕まえたときにだけ記録される。
 *   - シフト公開後の消化記録（ShiftBudgetConsumptionRecordListener）: 公開前に SHIFT_047 で
 *     「正数の時給が無い」公開自体が拒否されるため、時給欠落・非正数の経路には到達できない。
 *     記録処理は再公開に対して冪等（既存 PLANNED を取消して作り直す）で、通常操作では例外にならない。
 *   - 月次締めバッチ（closeAll）の組織単位の例外、100% 到達時のワークフロー起動失敗、通知配送の失敗。
 *     いずれも BE 内部の障害が前提で、画面・API から正当に起こす手段は無い。
 *
 * 前提: feature.shift-budget.enabled=true の BE（既定は OFF のため、検証用 BE を別ポートで起動して実施する）
 *
 * 実行例:
 *   BASE_URL=http://localhost:3002 API_BASE_URL=http://localhost:8081 \
 *     npx playwright test tests/e2e/real/cmp-260909-0544-failed-events-role.real.spec.ts
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
const MEMBER = { email: 'e2e-dummy-17@test.mannschaft.local', password: PASSWORD }
/** どの組織にも所属しない seed ユーザー（他テナント）。 */
const OUTSIDER = { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD }

const SHOT = process.env.EVIDENCE_DIR ?? '../docs/evidence/CMP-260909-0544'
const PATH = '/admin/shift-budget/failed-events'
const PERSIST_MS = 8_000

test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000)

let api: APIRequestContext
let adminToken = ''
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
  if (memberJoined) {
    await cleanup('MEMBER 加入', () => api.delete(`${BE_API}/organizations/${ORG_SLUG}/members/${memberUserId}`, { headers: bearer(adminToken) }))
  }
  if (inviteTokenId) {
    await cleanup('招待トークン', () => api.delete(`${BE_API}/organizations/${ORG_SLUG}/invite-tokens/${inviteTokenId}`, { headers: bearer(adminToken) }))
  }
  for (const p of [adminPage, memberPage, outsiderPage]) await p?.context().close()
  await api?.dispose()
})

test('FAILED-01 (AC-2・権限あり・失敗イベント 0 件): 組織 ADMIN は画面を開け、一覧は空状態になる', async () => {
  const page = adminPage
  const listed = page.waitForResponse(
    r => r.request().method() === 'GET' && /\/api\/v1\/shift-budget\/failed-events/.test(new URL(r.url()).pathname),
  )
  await page.goto(PATH)
  await waitForHydration(page)
  expect((await listed).status(), '一覧取得は 200（閲覧権限あり）').toBe(200)

  await expect(page.getByText('失敗イベント管理').first(), '画面タイトル').toBeVisible({ timeout: 30_000 })
  await expect(page.getByText('失敗イベントはありません'), '0 件の空状態').toBeVisible()
  await expect(page.getByText(/SELECT|INSERT|UPDATE|Exception|java\.|org\.springframework/), '空でも内部情報は出ない').toHaveCount(0)
  await page.screenshot({ path: SHOT + '/failed-01-admin-empty.png' })
})

const ROLES = [
  { key: 'member', label: '権限なし（組織の一般 MEMBER）', who: () => memberPage },
  { key: 'outsider', label: '他テナント（どの組織にも非所属）', who: () => outsiderPage },
] as const

async function openFailedEvents(page: Page) {
  const listed = page.waitForResponse(
    r => r.request().method() === 'GET' && /\/api\/v1\/shift-budget\/failed-events/.test(new URL(r.url()).pathname),
  )
  await page.goto(PATH)
  await waitForHydration(page)
  return listed
}

ROLES.forEach(({ key, label, who }, index) => {
  test(`FAILED-0${index + 2} (AC-2): ${label} が URL 直打ちで開く。BE は 403、操作の導線は出ない（画面と文言を記録）`, async () => {
    const page = who()
    const response = await openFailedEvents(page)
    expect(response.status(), 'BE は閲覧権限が無いので 403 を返す').toBe(403)

    await page.waitForTimeout(PERSIST_MS)
    const shown = (await page.locator('main').first().innerText()).replace(/\s+/g, ' ')
    console.log(`[FAILED-0${index + 2} ${label} の画面文言] ${shown}`)
    await page.screenshot({ path: SHOT + `/failed-0${index + 2}-${key}.png` })
    await expect(page.getByRole('button', { name: '再実行' }), '再実行の導線が出ない').toHaveCount(0)
    await expect(page.getByRole('button', { name: '補正済マーク' }), '補正済マークの導線が出ない').toHaveCount(0)
  })
})

// ↓ 実装欠陥の確認: failed-events.vue は取得失敗（403 など）を握りつぶして空配列のまま描画するため、
//   「失敗イベントはありません」と表示してしまう（CMP-260909-0543 が allocations / dashboard / alerts で直したのと同型）。
//   本番コードは直さず、欠陥として FAIL のまま記録する。
test('FAILED-04 (AC-2・実装欠陥の確認): 権限なし・他テナントが開いたとき、取得失敗が「失敗イベントはありません」の空状態に化けない', async () => {
  for (const { label, who } of ROLES) {
    const page = who()
    const response = await openFailedEvents(page)
    expect(response.status(), `${label}: BE は 403`).toBe(403)
    await page.waitForTimeout(PERSIST_MS)
    await expect.soft(
      page.getByText('失敗イベントはありません'),
      `${label}: 取得失敗なのに「失敗イベントはありません」と読める空状態が出てはならない`,
    ).toHaveCount(0)
  }
})
