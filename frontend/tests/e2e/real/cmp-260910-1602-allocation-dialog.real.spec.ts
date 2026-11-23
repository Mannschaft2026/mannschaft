/**
 * CMP-260910-1602 実機 E2E — 予算割当ダイアログの必須検証と、保存失敗理由（BE の理由）の表示
 *
 * 対象画面: /admin/shift-budget/allocations（ダイアログ: AllocationFormModal）
 *
 * 受け入れ条件（台帳の完了条件 + PR #4736 の修正内容）
 *   AC-a 必須項目（期間など）が未入力なら送信されず、項目にエラーが出る
 *   AC-b BE が保存を拒否したとき、BE が返した理由（message）が画面に表示される
 *        - 通貨欄を空にして送信 → BE 400 COMMON_001「入力内容に不備があります」（fieldErrors: currency）
 *        - 同一スコープ・同一期間を重複作成 → BE 409 SHIFT_BUDGET_011「同一スコープのシフト予算割当が既に存在します」
 *   AC-c 終了日 < 開始日は送信前に止まる
 *   AC-d 正常な入力では保存でき、一覧に出る（作成 → 備考を保持したまま金額を更新）
 *   ロール横断: 権限あり（組織 ADMIN）/ 権限なし（組織の一般 MEMBER）/ 他テナント（どの組織にも非所属）
 *
 * 方針: 対象操作（ダイアログの入力・保存・一覧確認）は全て画面で行う。API はログイン・前提データ
 *       （会計年度・費目・MEMBER 加入）の作成・結果の裏取り・後始末にだけ使う。
 *       page.route などによる API 横取りは使わない。
 *
 * 前提: feature.shift-budget.enabled=true の BE（既定は OFF のため、検証用 BE を別ポートで起動して実施する）
 *
 * 実行例:
 *   BASE_URL=http://localhost:3002 API_BASE_URL=http://localhost:8081 \
 *     npx playwright test tests/e2e/real/cmp-260910-1602-allocation-dialog.real.spec.ts
 */

import {
  test,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type Browser,
  type Locator,
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
const MEMBER = { email: 'e2e-dummy-20@test.mannschaft.local', password: PASSWORD }
/** どの組織にも所属しない seed ユーザー（他テナント）。 */
const OUTSIDER = { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD }

const SHOT = process.env.EVIDENCE_DIR ?? '../docs/evidence/CMP-260910-1602'
const RUN_TAG = `R${Date.now().toString(36)}`
const NOTE = `${RUN_TAG} 備考(更新後も保持されること)`
const PERIOD_START = '2045-04-01'
const PERIOD_END = '2045-04-30'
const AMOUNT = 120000
const AMOUNT_UPDATED = 150000

test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000)

let api: APIRequestContext
let adminToken = ''
let fiscalYearId = 0
let categoryId = 0
let memberUserId = 0
let inviteTokenId = 0
let memberJoined = false
let adminPage: Page
let memberPage: Page
let outsiderPage: Page
const createdAllocationIds = new Set<number>()

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

async function gotoAllocations(page: Page) {
  await page.goto('/admin/shift-budget/allocations')
  await waitForHydration(page)
}

/** ダイアログ内で、ラベル文言を含む項目の入力欄を返す。 */
function field(dialog: Locator, label: string): Locator {
  return dialog
    .locator(`xpath=.//label[contains(normalize-space(.),"${label}")]/following-sibling::*[1]`)
    .locator('xpath=self::input | .//input | .//textarea | self::textarea')
    .first()
}

/** InputNumber に入力する。null は欄を空にする（min=1 の欄は 0 を打つと 1 に丸められるため、未入力は空で表す）。 */
async function setNumber(input: Locator, value: number | null) {
  await input.click()
  await input.fill(value === null ? '' : String(value))
  await input.press('Tab')
}

/** 作成ダイアログに入力する。undefined の項目は触らない。 */
async function fillCreate(
  dialog: Locator,
  v: { fiscal?: number; category?: number; start?: string; end?: string; amount?: number; currency?: string; note?: string },
) {
  if (v.fiscal !== undefined) await setNumber(field(dialog, '会計年度'), v.fiscal)
  if (v.category !== undefined) await setNumber(field(dialog, '費目'), v.category)
  if (v.start !== undefined) await field(dialog, '開始日').fill(v.start)
  if (v.end !== undefined) await field(dialog, '終了日').fill(v.end)
  if (v.amount !== undefined) await setNumber(field(dialog, '配分額'), v.amount)
  if (v.currency !== undefined) await field(dialog, '通貨').fill(v.currency)
  if (v.note !== undefined) await field(dialog, '備考').fill(v.note)
}

/** 保存ボタンを押し、その間に飛んだ割当作成 POST（無ければ null）を返す。 */
async function clickSaveAndCapturePost(page: Page, dialog: Locator): Promise<{ status: number; body: string } | null> {
  let captured: { status: number; body: string } | null = null
  const waiter = (async () => {
    try {
      const res = await page.waitForResponse(
        r => r.request().method() === 'POST' && /\/shift-budget\/allocations$/.test(new URL(r.url()).pathname),
        { timeout: 4_000 },
      )
      captured = { status: res.status(), body: await res.text() }
    }
    catch {
      // 送信されなかった（= クライアント検証で止まった）場合はタイムアウトする。captured は null のまま返す。
    }
  })()
  await dialog.getByRole('button', { name: '保存' }).click()
  await waiter
  return captured
}

test.beforeAll(async ({ browser }) => {
  api = await pwRequest.newContext()
  adminToken = (await loginToken(ADMIN)).token

  // --- 前提データ: 会計年度・費目（BE は存在を FK で見ないが、実運用に近い実在 ID を使う） ---
  const fy = await api.post(`${BE_API}/organizations/${ORG_ID}/budget/fiscal-years`, {
    headers: bearer(adminToken),
    data: { name: `${RUN_TAG} 会計年度`, startDate: '2045-04-01', endDate: '2046-03-31' },
  })
  expect(fy.status(), `会計年度作成は 201（${await fy.text()}）`).toBe(201)
  fiscalYearId = ((await fy.json()) as { data: { id: number } }).data.id

  const cat = await api.post(
    `${BE_API}/budget/fiscal-years/${fiscalYearId}/categories?scopeId=${ORG_ID}&scopeType=ORGANIZATION`,
    {
      headers: bearer(adminToken),
      data: { fiscalYearId, name: `${RUN_TAG} 費目`, categoryType: 'EXPENSE' },
    },
  )
  expect(cat.status(), `費目作成は 201（${await cat.text()}）`).toBe(201)
  categoryId = ((await cat.json()) as { data: { id: number } }).data.id

  // --- 前提データ: 権限なしの視点（組織 11 の一般 MEMBER）---
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

test.afterAll(async () => {
  // 自分が作った分だけ削除する
  for (const id of createdAllocationIds) {
    await cleanup('delete', () => api.delete(`${BE_API}/shift-budget/allocations/${id}`, { headers: bearer(adminToken, true) }))
  }
  if (categoryId) {
    await cleanup('delete', () => api.delete(`${BE_API}/budget/categories/${categoryId}?scopeId=${ORG_ID}&scopeType=ORGANIZATION`, { headers: bearer(adminToken) }))
  }
  if (fiscalYearId) {
    await cleanup('delete', () => api.delete(`${BE_API}/budget/fiscal-years/${fiscalYearId}?scopeId=${ORG_ID}&scopeType=ORGANIZATION`, { headers: bearer(adminToken) }))
  }
  if (memberJoined) {
    await cleanup('delete', () => api.delete(`${BE_API}/organizations/${ORG_SLUG}/members/${memberUserId}`, { headers: bearer(adminToken) }))
  }
  if (inviteTokenId) {
    await cleanup('delete', () => api.delete(`${BE_API}/organizations/${ORG_SLUG}/invite-tokens/${inviteTokenId}`, { headers: bearer(adminToken) }))
  }
  for (const p of [adminPage, memberPage, outsiderPage]) await p?.context().close()
  await api?.dispose()
})

test('ALLOC-01 (AC-a・権限あり): 期間が未入力のまま保存しても送信されず、開始日・終了日の項目にエラーが出る', async () => {
  const page = adminPage
  await gotoAllocations(page)

  const create = page.getByRole('button', { name: '新規割当' })
  await expect(create, '組織 ADMIN には「新規割当」ボタンが見える').toBeVisible({ timeout: 30_000 })
  await create.click()

  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  // 年度・費目だけ入れ、期間は空のまま保存する
  await fillCreate(dialog, { fiscal: fiscalYearId, category: categoryId, amount: AMOUNT })
  const posted = await clickSaveAndCapturePost(page, dialog)

  expect(posted, '必須項目が欠けているので割当作成 POST は送信されない').toBeNull()
  await expect(dialog, 'ダイアログは開いたまま').toBeVisible()
  await expect(page.getByTestId('allocation-error-period_start')).toHaveText('開始日を入力してください')
  await expect(page.getByTestId('allocation-error-period_end')).toHaveText('終了日を入力してください')
  await page.screenshot({ path: SHOT + '/alloc-01-required.png', fullPage: false })

  // 年度・費目を空にしても止まる
  await setNumber(field(dialog, '会計年度'), null)
  await setNumber(field(dialog, '費目'), null)
  const posted2 = await clickSaveAndCapturePost(page, dialog)
  expect(posted2).toBeNull()
  await expect(page.getByTestId('allocation-error-fiscal_year_id')).toHaveText('年度IDを入力してください')
  await expect(page.getByTestId('allocation-error-budget_category_id')).toHaveText('費目IDを入力してください')
})

test('ALLOC-02 (AC-c): 終了日が開始日より前なら送信前に止まり、終了日の項目にエラーが出る', async () => {
  const page = adminPage
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await fillCreate(dialog, { fiscal: fiscalYearId, category: categoryId, start: '2045-04-30', end: '2045-04-01' })
  const posted = await clickSaveAndCapturePost(page, dialog)

  expect(posted, '期間が逆転しているので POST は送信されない').toBeNull()
  await expect(dialog).toBeVisible()
  await expect(page.getByTestId('allocation-error-period_end')).toHaveText('終了日は開始日以降にしてください')
  await expect(page.getByTestId('allocation-error-period_start'), '開始日欄は入力済みなのでエラーなし').toHaveCount(0)
})

test('ALLOC-03 (AC-b): BE が項目エラー(400)で拒否したら、BE の理由がダイアログに表示される（通貨欄を空にした送信）', async () => {
  const page = adminPage
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  // 画面の検証を通る正当な入力で、BE の検証(@Size(min=3) の通貨)だけが拒否する
  await fillCreate(dialog, {
    fiscal: fiscalYearId, category: categoryId, start: PERIOD_START, end: PERIOD_END,
    amount: AMOUNT, currency: '', note: NOTE,
  })
  const posted = await clickSaveAndCapturePost(page, dialog)

  expect(posted, '割当作成 POST が BE へ送信される').not.toBeNull()
  expect(posted!.status, 'BE は 400 で拒否する').toBe(400)
  const beMessage = (JSON.parse(posted!.body) as { error: { code: string; message: string } }).error
  expect(beMessage.code).toBe('COMMON_001')

  const formError = page.getByTestId('allocation-form-error')
  await expect(formError, 'BE が返した message がダイアログ内に出る').toHaveText(beMessage.message)
  await expect(formError).toHaveText('入力内容に不備があります')
  await expect(dialog, '拒否されたのでダイアログは開いたまま（入力は保持）').toBeVisible()
  await expect(field(dialog, '備考')).toHaveValue(NOTE)
  await formError.scrollIntoViewIfNeeded()
  await page.screenshot({ path: SHOT + '/alloc-03-be-400.png' })
})

test('ALLOC-04 (AC-d): 正常な入力で保存でき、ダイアログが閉じて一覧に出る（永続化は API でも裏取り）', async () => {
  const page = adminPage
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await fillCreate(dialog, { currency: 'JPY' })
  const posted = await clickSaveAndCapturePost(page, dialog)

  expect(posted?.status, '作成は 201').toBe(201)
  const id = (JSON.parse(posted!.body) as { data: { id: number } }).data.id
  createdAllocationIds.add(id)

  await expect(page.getByText('割当を作成しました').first(), '成功通知').toBeVisible()
  await expect(dialog, '保存後にダイアログが閉じる').toBeHidden()
  const row = page.locator('tbody tr', { hasText: `${PERIOD_START} 〜 ${PERIOD_END}` })
  await expect(row, '一覧に新しい割当が 1 行出る').toHaveCount(1)
  await expect(row).toContainText(`${AMOUNT.toLocaleString('en-US')} JPY`)
  await page.screenshot({ path: SHOT + '/alloc-04-created.png' })

  // 裏取り（結果確認のみ）: BE に保存されている
  const get = await api.get(`${BE_API}/shift-budget/allocations/${id}`, { headers: bearer(adminToken, true) })
  const saved = ((await get.json()) as { data: { allocated_amount: number; note: string; fiscal_year_id: number; budget_category_id: number } }).data
  expect(saved.allocated_amount).toBe(AMOUNT)
  expect(saved.note).toBe(NOTE)
  expect(saved.fiscal_year_id).toBe(fiscalYearId)
  expect(saved.budget_category_id).toBe(categoryId)
})

test('ALLOC-05 (AC-b): 同一スコープ・同一期間の重複作成を BE が 409 で拒否し、その理由がダイアログに表示される', async () => {
  const page = adminPage
  await page.getByRole('button', { name: '新規割当' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await fillCreate(dialog, {
    fiscal: fiscalYearId, category: categoryId, start: PERIOD_START, end: PERIOD_END, amount: AMOUNT, currency: 'JPY',
  })
  const posted = await clickSaveAndCapturePost(page, dialog)

  expect(posted?.status, 'BE は重複を 409 で拒否する').toBe(409)
  const err = (JSON.parse(posted!.body) as { error: { code: string; message: string } }).error
  expect(err.code).toBe('SHIFT_BUDGET_011')

  const formError = page.getByTestId('allocation-form-error')
  await expect(formError, 'BE が返した理由がダイアログ内に残る').toHaveText(err.message)
  await expect(formError).toHaveText('同一スコープのシフト予算割当が既に存在します')
  await expect(dialog, 'ダイアログは開いたまま').toBeVisible()
  await formError.scrollIntoViewIfNeeded()
  await page.screenshot({ path: SHOT + '/alloc-05-be-409.png' })

  // 閉じた後も一覧は 1 行のまま（二重作成されていない）
  await dialog.getByRole('button', { name: 'キャンセル' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.locator('tbody tr', { hasText: `${PERIOD_START} 〜 ${PERIOD_END}` })).toHaveCount(1)
})

test('ALLOC-06 (AC-d): 作成した割当を、備考を保持したまま金額だけ更新でき、一覧に反映される', async () => {
  const page = adminPage
  const row = page.locator('tbody tr', { hasText: `${PERIOD_START} 〜 ${PERIOD_END}` })
  await row.getByRole('button', { name: '割当を編集' }).click()

  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await expect(field(dialog, '備考'), '編集ダイアログに既存の備考が保持されている').toHaveValue(NOTE)
  await setNumber(field(dialog, '配分額'), AMOUNT_UPDATED)

  let captured: { status: number } | null = null
  const waiter = page
    .waitForResponse(r => r.request().method() === 'PUT' && /\/shift-budget\/allocations\/\d+$/.test(new URL(r.url()).pathname))
    .then((r) => { captured = { status: r.status() } })
  await dialog.getByRole('button', { name: '保存' }).click()
  await waiter
  expect(captured!.status, '更新は 200').toBe(200)

  await expect(page.getByText('割当を更新しました').first()).toBeVisible()
  await expect(dialog).toBeHidden()
  await expect(
    page.locator('tbody tr', { hasText: `${PERIOD_START} 〜 ${PERIOD_END}` }),
    '一覧の金額が更新後の値になる',
  ).toContainText(`${AMOUNT_UPDATED.toLocaleString('en-US')} JPY`)

  const [id] = [...createdAllocationIds]
  const get = await api.get(`${BE_API}/shift-budget/allocations/${id}`, { headers: bearer(adminToken, true) })
  const saved = ((await get.json()) as { data: { allocated_amount: number; note: string; version: number } }).data
  expect(saved.allocated_amount).toBe(AMOUNT_UPDATED)
  expect(saved.note, '更新後も備考が保持されている').toBe(NOTE)
  expect(saved.version, '楽観ロックの version が進んでいる').toBeGreaterThan(0)
})

test('ALLOC-07 (権限なし・組織の一般 MEMBER): 新規割当の導線が無く、URL 直打ちでも一覧は取得できず他人のデータが見えない', async () => {
  const page = memberPage
  await gotoAllocations(page)

  // 一覧は BUDGET_VIEW が無く 403。「登録されていません」の空状態に化けず、エラー表示になる
  const errorState = page.getByTestId('shift-budget-allocations-error-state')
  await expect(errorState, '取得失敗の表示になる').toBeVisible({ timeout: 30_000 })
  const text = (await errorState.innerText()).replace(/\s+/g, ' ')
  await page.screenshot({ path: SHOT + '/alloc-07-member.png' })
  console.log(`[ALLOC-07 MEMBER の画面文言] ${text}`)
  await expect(page.getByText('割当が登録されていません')).toHaveCount(0)
  await expect(page.getByText(`${PERIOD_START} 〜 ${PERIOD_END}`), '組織の割当は見えない').toHaveCount(0)
  await expect(page.getByRole('button', { name: '新規割当' }), '「新規割当」の導線が出ない').toHaveCount(0)
})

test('ALLOC-08 (他テナント・どの組織にも非所属): 導線が無く、組織 11 のスコープで直打ちしても一覧を取得できない', async () => {
  const page = outsiderPage
  await gotoAllocations(page)

  const errorState = page.getByTestId('shift-budget-allocations-error-state')
  const scopeWarning = page.getByText('シフト予算管理は組織スコープでのみ利用できます')
  await expect(errorState.or(scopeWarning), '取得失敗またはスコープ誘導のどちらかになる').toBeVisible({ timeout: 30_000 })
  const shown = (await errorState.or(scopeWarning).first().innerText()).replace(/\s+/g, ' ')
  await page.screenshot({ path: SHOT + '/alloc-08-outsider.png' })
  console.log(`[ALLOC-08 他テナントの画面文言] ${shown}`)
  await expect(page.getByText('割当が登録されていません')).toHaveCount(0)
  await expect(page.getByText(`${PERIOD_START} 〜 ${PERIOD_END}`), '他テナントに組織 11 の割当は見えない').toHaveCount(0)
  await expect(page.getByRole('button', { name: '新規割当' }), '「新規割当」の導線が出ない').toHaveCount(0)
})
