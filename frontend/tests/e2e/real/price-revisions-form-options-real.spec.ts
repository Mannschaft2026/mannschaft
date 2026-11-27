/**
 * 価格改定の作成フォーム（商品キー・税コードの選択式／税率％入力）の実機 E2E（CMP-260930-1934 / PR #4742）
 *
 * API モックなし。実 BE（既定 :8080）と実 FE（BASE_URL）を実ブラウザで操作する。
 * API の直接利用はログイン・前提の読み取り（選択肢の期待値算出）・後始末の確認・権限の素通り検査に限り、
 * 対象操作（選択・％入力・送信）はすべて画面の要素で行う。DB への直接投入は行わない。
 *
 * ロール:
 *   - 権限あり        : e2e-operator@test.mannschaft.local（SYSTEM_ADMIN）
 *   - 権限なし        : e2e-user@test.mannschaft.local（一般。組織 e2e の ADMIN でもある）
 *   - 他テナント ADMIN: e2e-dummy-1@test.mannschaft.local（チーム fc-u-18 の ADMIN）
 *
 * 共有 DB のため、作成する税コードは RUN_TAG 付きコードにし、後始末で「この実行が作った分だけ」画面から
 * 無効化・取り消しする。
 *
 * 実行方法:
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8080 \
 *     npx playwright test tests/e2e/real/price-revisions-form-options-real.spec.ts \
 *     -c playwright-real.config.ts --project chromium-real --reporter=list
 */

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  test,
  expect,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
  type TestInfo,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'default' })
test.setTimeout(240_000)

const BE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PR_API = `${BE}/api/v1/system-admin/billing/price-revisions`
const TAX_API = `${BE}/api/v1/system-admin/billing/tax-codes`
const PLANS_API = `${BE}/api/v1/system-admin/billing/plans`
const FEATURES_API = `${BE}/api/v1/system-admin/billing/features`

const ADMIN = { email: 'e2e-operator@test.mannschaft.local', password: 'TestPass2026!' }
const USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const OTHER_TENANT_ADMIN = { email: 'e2e-dummy-1@test.mannschaft.local', password: 'TestPass2026!' }

// 失敗後のワーカー再起動でも RUN_TAG と作成済み ID を引き継ぐため、状態を一時ファイルへ保存する
type RunState = { runTag: string; taxCodeIds: string[]; revisionIds: string[] }
const STATE_FILE = path.join(os.tmpdir(), 'price-revisions-form-options-real-e2e-state.json')
function loadState(): RunState {
  try {
    // 30 分より古い状態ファイルは前回実行の残りとして使わない（RUN_TAG を新しくする）
    if (Date.now() - fs.statSync(STATE_FILE).mtimeMs > 30 * 60_000) throw new Error('stale')
    return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')) as RunState
  } catch {
    // 初回実行（または古い状態ファイル）では新しい RUN_TAG で始める（失敗要因ではない）
    return { runTag: `R${Date.now().toString(36)}`.toUpperCase(), taxCodeIds: [], revisionIds: [] }
  }
}
const created = loadState()
const RUN_TAG = created.runTag
function persist(): void {
  fs.writeFileSync(STATE_FILE, JSON.stringify(created))
}
persist()
const CODE_75 = `E2E_${RUN_TAG}_75`
const CODE_ZERO = `E2E_${RUN_TAG}_ZERO`
const FUTURE_STATUSES = ['DRAFT', 'PROVISIONING', 'PROVISION_FAILED', 'READY', 'SCHEDULED']
const SLOT_CANDIDATES: [string, string][] = [
  ['BASIC', 'TEAM'], ['FULL', 'TEAM'], ['BASIC', 'ORG'], ['FULL', 'ORG'], ['BASIC', 'USER'], ['FULL', 'USER'],
]
/** system-admin ミドルウェア（CMP-260930-1933）の拒否トースト。非 SYSTEM_ADMIN は /dashboard へ戻される。 */
const DENIED_TITLE = 'アクセスできません'
const DENIED_BODY = '管理者権限が必要です'
const INPUT_AMOUNT = 3300


async function newLoggedInPage(
  browser: Browser,
  creds: { email: string; password: string },
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
  const page = await context.newPage()
  await loginViaApi(page, creds, { apiBaseUrl: BE, deferNavigation: true })
  return { context, page }
}

async function gotoAndSettle(page: Page, url: string): Promise<void> {
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  await waitForHydration(page)
  // eslint-disable-next-line no-restricted-syntax -- スピナーが初めから存在しないページでは待つ対象が無いだけ（失敗要因ではない）
  await page.locator('.p-progressspinner').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
  // eslint-disable-next-line no-restricted-syntax -- スピナーが初めから存在しないページでは待つ対象が無いだけ（失敗要因ではない）
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
}

async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const file = testInfo.outputPath(`${name}.png`)
  await page.screenshot({ path: file, fullPage: true })
  await testInfo.attach(name, { path: file, contentType: 'image/png' })
}

async function note(testInfo: TestInfo, name: string, body: unknown): Promise<void> {
  const text = typeof body === 'string' ? body : JSON.stringify(body, null, 2)
  console.log(`[${testInfo.title}] ${name}: ${text}`)
  await testInfo.attach(name, { body: text, contentType: 'text/plain' })
}

/** JST の壁時計で「今から minutes 分後」を datetime-local 形式で返す。 */
function jstLocal(minutes: number): string {
  const ms = Math.ceil((Date.now() + minutes * 60_000) / 60_000) * 60_000
  return new Date(ms + 9 * 3600_000).toISOString().slice(0, 16)
}

async function openCreateDialog(page: Page): Promise<Locator> {
  await page.getByRole('button', { name: 'create-price-revision' }).click()
  const dialog = page.getByRole('dialog', { name: '価格改定を作成' })
  await expect(dialog).toBeVisible()
  return dialog
}

async function openTaxCodeDialog(page: Page): Promise<Locator> {
  await page.getByRole('button', { name: 'tax-codes' }).click()
  const dialog = page.getByRole('dialog', { name: '税コードマスタ' })
  await expect(dialog).toBeVisible()
  return dialog
}

/** Dropdown（PrimeVue）を開き、表示されているオプション名の一覧を返す。開いたままにする。 */
async function openDropdownOptions(page: Page, id: string): Promise<string[]> {
  await page.locator(`#${id}`).click()
  // 開いている（最後の）オーバーレイの選択肢だけを読む（直前に閉じた別 Dropdown の残りを拾わない）
  const options = page.locator('.p-select-overlay').last().getByRole('option')
  // eslint-disable-next-line no-restricted-syntax -- 選択肢が 0 件（emptyMessage 表示）の場合もあるため待ちは失敗要因にしない
  await options.first().waitFor({ state: 'visible', timeout: 3_000 }).catch(() => {})
  return (await options.allInnerTexts()).map((s) => s.trim())
}

async function pickOption(page: Page, id: string, name: string | RegExp): Promise<void> {
  await page.locator(`#${id}`).click()
  await page.getByRole('option', { name }).first().click()
}

async function typeTaxRate(dialog: Locator, text: string): Promise<void> {
  const input = dialog.locator('input[aria-label="new-tax-code-rate"]')
  await input.click()
  await input.press('Control+a')
  await input.pressSequentially(text)
  await input.press('Tab')
}

async function submitTaxCode(page: Page, dialog: Locator, code: string, ratePercentText: string, testInfo: TestInfo) {
  await dialog.getByLabel('new-tax-code-code').fill(code)
  await dialog.getByLabel('new-tax-code-display-name').fill(`E2E ${RUN_TAG} ${ratePercentText}%`)
  await typeTaxRate(dialog, ratePercentText)
  await dialog.locator('input[aria-label="new-tax-code-valid-from"]').fill(jstLocal(-60 * 24))
  await dialog.getByLabel('new-tax-code-stripe-tax-code').fill('txcd_10000000')
  const [res] = await Promise.all([
    page.waitForResponse((r) => r.url() === TAX_API && r.request().method() === 'POST'),
    dialog.getByRole('button', { name: 'submit-create-tax-code' }).click(),
  ])
  const body = await res.text()
  await note(testInfo, `POST tax-codes ${code}`, { status: res.status(), request: res.request().postData(), body })
  const json = JSON.parse(body) as { id?: string; data?: { id: string } }
  const id = json.data?.id ?? json.id
  if (id) { created.taxCodeIds.push(id); persist() }
  return { res, id, requestBody: JSON.parse(res.request().postData() ?? '{}') as Record<string, unknown> }
}

/** 未来予約の無い商品枠を 1 つ選ぶ（一覧 API の読み取りのみ）。 */
async function pickFreeSlot(page: Page): Promise<[string, string]> {
  const listRes = await page.request.get(`${PR_API}?size=100`)
  const items = ((await listRes.json()) as { data: { items: { productKey: string; scopeKind: string; status: string }[] } }).data.items
  const free = SLOT_CANDIDATES.find(([k, s]) => !items.some((e) => e.productKey === k && e.scopeKind === s && FUTURE_STATUSES.includes(e.status)))
  expect(free, '未来予約の無い商品枠が残っている').toBeTruthy()
  return free!
}

// ===========================================================================
// FORM-01 商品キーは有効な選択肢からのみ選べる
// ===========================================================================
test('FORM-01 商品キー: 自由入力ではなく選択式で、選択肢は BE の有効なプラン/アドオンだけ。存在しない値（ADVANCED）は選べない', async ({ browser }, testInfo) => {
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  try {
    // 期待値の算出（読み取りのみ）
    const plans = ((await (await page.request.get(PLANS_API)).json()) as { data: { planKey: string; enabled: boolean }[] }).data
    const features = ((await (await page.request.get(FEATURES_API)).json()) as { data: { featureKey: string; enabled: boolean; addonAvailable: boolean }[] }).data
    const expectedPlans = plans.filter((p) => p.enabled).map((p) => p.planKey)
    const expectedAddons = features.filter((f) => f.enabled && f.addonAvailable).map((f) => f.featureKey)
    await note(testInfo, '期待する選択肢', { expectedPlans, expectedAddons, allPlans: plans.map((p) => `${p.planKey}:${p.enabled}`) })

    await gotoAndSettle(page, '/system-admin/price-revisions')
    const dialog = await openCreateDialog(page)

    // 自由入力欄ではない（テキスト入力ではなく Dropdown）
    await expect(dialog.locator('input#pr-product-key')).toHaveCount(0)
    await expect(dialog.locator('#pr-product-key.p-select')).toHaveCount(1)

    // PLAN の選択肢
    const planOptions = await openDropdownOptions(page, 'pr-product-key')
    await note(testInfo, 'PLAN の選択肢（画面）', planOptions)
    await shot(page, testInfo, 'product-key-plan-options')
    expect([...planOptions].sort()).toEqual([...expectedPlans].sort())
    expect(planOptions, '存在しない商品キー ADVANCED は選択肢に無い').not.toContain('ADVANCED')
    await page.keyboard.press('Escape')

    // 無効なプランは選択肢に出ない
    const disabledPlans = plans.filter((p) => !p.enabled).map((p) => p.planKey)
    for (const k of disabledPlans) expect(planOptions).not.toContain(k)

    // ADDON に切り替えると、選択肢が単体契約可のアドオンだけに変わる
    await pickOption(page, 'pr-product-kind', 'ADDON')
    const addonOptions = await openDropdownOptions(page, 'pr-product-key')
    await note(testInfo, 'ADDON の選択肢（画面）', addonOptions)
    await shot(page, testInfo, 'product-key-addon-options')
    if (expectedAddons.length > 0) {
      expect([...addonOptions].sort()).toEqual([...expectedAddons].sort())
    } else {
      await expect(page.getByRole('option', { name: '選択できる商品がありません' })).toHaveCount(1)
    }
    await page.keyboard.press('Escape')

    // 選択済みの商品キーは、商品種別を切り替えて選択肢から外れたら解除される（古い値を送らない）
    await pickOption(page, 'pr-product-kind', 'PLAN')
    await pickOption(page, 'pr-product-key', expectedPlans[0]!)
    await expect(dialog.locator('#pr-product-key')).toContainText(expectedPlans[0]!)
    await pickOption(page, 'pr-product-kind', 'ADDON')
    await expect(dialog.locator('#pr-product-key')).not.toContainText(expectedPlans[0]!)
    await expect(dialog.locator('#pr-product-key')).toContainText('商品キーを選択')
    // 商品キー未選択では作成ボタンが押せない
    await expect(dialog.getByRole('button', { name: 'submit-create-price-revision' })).toBeDisabled()
  } finally {
    await context.close()
  }
})

// ===========================================================================
// FORM-02 税コードは適用開始日時に有効なものだけ。未選択ではプレビューが出ず案内が出る
// ===========================================================================
test('FORM-02 税コード: 適用開始日時が先、有効な税コードだけが選べ、未選択では税額プレビューを出さず案内を出す', async ({ browser }, testInfo) => {
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  try {
    await gotoAndSettle(page, '/system-admin/price-revisions')

    // 前提: 税率 7.5% と 0% の税コードを画面（税コードマスタ）から登録する（FORM-03 の主題でもあるが、ここは前提）
    const taxDialog = await openTaxCodeDialog(page)
    const r75 = await submitTaxCode(page, taxDialog, CODE_75, '7.5', testInfo)
    expect(r75.res.status()).toBeLessThan(300)
    const rZero = await submitTaxCode(page, taxDialog, CODE_ZERO, '0', testInfo)
    expect(rZero.res.status()).toBeLessThan(300)
    await page.keyboard.press('Escape')
    await expect(taxDialog).toBeHidden()

    const dialog = await openCreateDialog(page)
    // 適用開始日時が未入力: 税コードは選べず、先に日時を入力する案内が出る
    await page.locator('#pr-tax-code').click()
    await expect(page.getByRole('option', { name: '先に適用開始日時を入力してください' })).toHaveCount(1)
    await page.keyboard.press('Escape')
    // 税コード未選択: プレビューは数値ではなく案内
    await expect(dialog.getByLabel('tax-preview-hint')).toHaveText('税コードを選ぶとプレビューが表示されます')
    await expect(dialog.getByText('税額:')).toHaveCount(0)
    await shot(page, testInfo, 'tax-preview-needs-tax-code')

    // 適用開始日時を入力すると、有効な税コード（今作った 2 件を含む）が「コード（税率%）」表記で選べる
    await dialog.locator('#pr-effective-from').fill(jstLocal(60 * 24 * 10))
    const options = await openDropdownOptions(page, 'pr-tax-code')
    await note(testInfo, '税コードの選択肢（画面）', options)
    await shot(page, testInfo, 'tax-code-options')
    expect(options).toContain(`${CODE_75}（7.5%）`)
    expect(options).toContain(`${CODE_ZERO}（0%）`)
    // 存在しないコードは選べない（選択肢に出ない）
    expect(options.some((o) => o.startsWith('NOT_EXISTING'))).toBe(false)
    await page.keyboard.press('Escape')

    // 金額・課税方式を入れても、税コード未選択の間はプレビューを出さず、作成も押せない
    await pickOption(page, 'pr-tax-mode', 'EXCLUSIVE')
    const amount = dialog.locator('#pr-input-amount input, input#pr-input-amount').first()
    await amount.click()
    await amount.pressSequentially('1000')
    await amount.press('Tab')
    await expect(dialog.getByLabel('tax-preview-hint')).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'submit-create-price-revision' })).toBeDisabled()

    // 税率 0% の税コード: 「税額 0」と出る（未選択の案内とは区別される）
    await pickOption(page, 'pr-tax-code', CODE_ZERO)
    await expect(dialog.getByLabel('tax-preview-hint')).toHaveCount(0)
    await expect(dialog.getByText('税額: 0', { exact: false })).toBeVisible()
    await expect(dialog.getByText('税込金額: 1000', { exact: false })).toBeVisible()
    await shot(page, testInfo, 'tax-preview-zero-rate')

    // 税率 7.5% に切り替えるとプレビューが変わる（税抜 1000 → 税 75 → 税込 1075）
    await pickOption(page, 'pr-tax-code', CODE_75)
    await expect(dialog.getByText('税額: 75', { exact: false })).toBeVisible()
    await expect(dialog.getByText('税込金額: 1075', { exact: false })).toBeVisible()

    // 適用開始日時を税コードの有効開始より前に戻すと、選択済みの税コードは解除される（古い値を送らない）
    await dialog.locator('#pr-effective-from').fill(jstLocal(-60 * 24 * 365 * 5))
    await expect(dialog.locator('#pr-tax-code')).toContainText('税コードを選択')
    await expect(dialog.getByLabel('tax-preview-hint')).toBeVisible()
  } finally {
    await context.close()
  }
})

// ===========================================================================
// FORM-03 税率は％で入力し、送信時に basis points へ変換される。範囲外は送信前に防ぐ
// ===========================================================================
test('FORM-03 税率: 7.5% が 750bp として保存され一覧に 7.5% と出る。範囲外・端数は送信前に弾かれる', async ({ browser }, testInfo) => {
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  try {
    // 保存値（bp）は FORM-02 で登録済み。画面の一覧と API の保存値を突き合わせる。
    await gotoAndSettle(page, '/system-admin/price-revisions')
    const dialog = await openTaxCodeDialog(page)
    const row75 = dialog.getByRole('row').filter({ has: page.getByRole('cell', { name: CODE_75, exact: true }) })
    await expect(row75).toContainText('7.5%')
    const rowZero = dialog.getByRole('row').filter({ has: page.getByRole('cell', { name: CODE_ZERO, exact: true }) })
    await expect(rowZero).toContainText('0%')
    await shot(page, testInfo, 'tax-code-list-percent')

    const list = (await (await page.request.get(TAX_API)).json()) as { data?: { code: string; rateBasisPoints: number }[] } | { code: string; rateBasisPoints: number }[]
    const codes = Array.isArray(list) ? list : list.data ?? []
    await note(testInfo, '保存値（bp）', codes.filter((c) => c.code.includes(RUN_TAG)))
    expect(codes.find((c) => c.code === CODE_75)?.rateBasisPoints, '7.5% は 750bp で保存される').toBe(750)
    expect(codes.find((c) => c.code === CODE_ZERO)?.rateBasisPoints, '0% は 0bp で保存される').toBe(0)

    // 範囲外・端数: 送信前に防がれる（登録ボタンが押せない、または入力が範囲内に収められる）。API へは送られない。
    let posted = 0
    page.on('request', (r) => {
      if (r.url() === TAX_API && r.method() === 'POST') posted++
    })
    await dialog.getByLabel('new-tax-code-code').fill(`E2E_${RUN_TAG}_BAD`)
    await dialog.getByLabel('new-tax-code-display-name').fill('E2E 範囲外')
    await dialog.locator('input[aria-label="new-tax-code-valid-from"]').fill(jstLocal(-60 * 24))
    await dialog.getByLabel('new-tax-code-stripe-tax-code').fill('txcd_10000000')

    const rateInput = dialog.locator('input[aria-label="new-tax-code-rate"]')
    // 100% 超: 100% に収められる（範囲外の値は入力欄に残らない）
    await typeTaxRate(dialog, '150')
    await expect(rateInput).toHaveValue('100%')
    await shot(page, testInfo, 'tax-rate-input-150')
    // 負の値: マイナスは受け付けられず、負の税率にならない
    await typeTaxRate(dialog, '-5')
    const negShown = await rateInput.inputValue()
    await note(testInfo, '入力 "-5" の結果', negShown)
    expect(negShown.includes('-'), '負の税率は入力欄に残らない').toBe(false)
    // 小数 3 桁: 2 桁（0.01%=1bp 刻み）を超える端数は入力欄に残らない（黙って別の税率に丸めて保存しない）
    await typeTaxRate(dialog, '7.555')
    const fracShown = await rateInput.inputValue()
    await note(testInfo, '入力 "7.555" の結果', fracShown)
    expect(fracShown, '小数 3 桁目は受け付けられない').toBe('7.55%')
    await shot(page, testInfo, 'tax-rate-input-7_555')
    // 範囲外・端数だった入力の後も、登録ボタンは「有効な値だけ」で押せる状態（画面が値を 0〜100 に収めている）
    expect(await dialog.getByLabel('tax-rate-format-error').count(), '収められた値なのでエラー表示は出ない').toBe(0)
    expect(posted, '範囲外の入力では税コード登録 API を呼ばない').toBe(0)
  } finally {
    await context.close()
  }
})

// ===========================================================================
// FORM-04 作成フォームで保存 → 一覧・詳細に値が正しく出る（税率 7.5% → 税込 3547）
// ===========================================================================
test('FORM-04 作成: 選択式の商品キー・税コード（7.5%）で DRAFT を保存でき、詳細に税込額が bp どおりに出る', async ({ browser }, testInfo) => {
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  try {
    const [productKey, scopeKind] = await pickFreeSlot(page)
    await gotoAndSettle(page, '/system-admin/price-revisions')
    const dialog = await openCreateDialog(page)
    await pickOption(page, 'pr-product-key', productKey)
    await pickOption(page, 'pr-scope-kind', scopeKind)
    const effective = jstLocal(60 * 24 * 10)
    await dialog.locator('#pr-effective-from').fill(effective)
    await pickOption(page, 'pr-tax-mode', 'EXCLUSIVE')
    const amount = dialog.locator('#pr-input-amount input, input#pr-input-amount').first()
    await amount.click()
    await amount.pressSequentially(String(INPUT_AMOUNT))
    await amount.press('Tab')
    await pickOption(page, 'pr-tax-code', CODE_75)
    // プレビュー: 3300 × 7.5% = 247.5
    await expect(dialog.getByLabel('tax-preview-hint')).toHaveCount(0)
    await shot(page, testInfo, 'create-form-filled-75')
    const taxPreview = await dialog.getByText('税額:', { exact: false }).innerText()
    await note(testInfo, '税額プレビュー（画面）', taxPreview)

    const [res] = await Promise.all([
      page.waitForResponse((r) => r.url() === PR_API && r.request().method() === 'POST'),
      dialog.getByRole('button', { name: 'submit-create-price-revision' }).click(),
    ])
    const body = await res.text()
    await note(testInfo, 'POST price-revisions', { status: res.status(), request: res.request().postData(), body })
    expect(res.status()).toBe(201)
    const sent = JSON.parse(res.request().postData() ?? '{}') as { productKey: string; bands: { taxCode: string }[] }
    expect(sent.productKey).toBe(productKey)
    expect(sent.bands[0]?.taxCode).toBe(CODE_75)
    const id = (JSON.parse(body) as { data: { id: string } }).data.id
    created.revisionIds.push(id)
    persist()

    // 詳細: DRAFT・保存値。税抜 3300 + 7.5%（750bp）→ 税込 3547（BE の整数除算・切り捨て）
    await page.waitForURL(new RegExp(`/system-admin/price-revisions/${id}$`))
    await expect(page.getByRole('heading', { name: '価格改定の詳細' })).toBeVisible()
    const section = page.locator('section').first()
    await expect(section).toContainText(productKey)
    await expect(section).toContainText(scopeKind)
    await expect(section).toContainText('DRAFT')
    const detail = (await (await page.request.get(`${PR_API}/${id}`)).json()) as { data: { bands: { taxCode?: string; inputAmount?: number; taxIncludedAmount?: number; amountIncludingTax?: number }[] } }
    await note(testInfo, '詳細 API の band（保存値）', detail.data.bands)
    await shot(page, testInfo, 'detail-after-create-75')
    const band = detail.data.bands[0]!
    const stored = band.taxIncludedAmount ?? band.amountIncludingTax
    const expected = Math.floor(INPUT_AMOUNT * (10000 + 750) / 10000)
    expect(stored, `保存値の税込額は 750bp で換算された ${expected}`).toBe(expected)
    await expect(page.getByRole('cell', { name: String(expected), exact: true })).toBeVisible()

    // 一覧にも出る
    await page.getByRole('button', { name: 'back-to-list' }).click()
    await page.waitForURL(/\/system-admin\/price-revisions$/)
    const row = page.getByRole('row').filter({ has: page.getByRole('button', { name: `detail-${id}` }) })
    await expect(row).toBeVisible()
    await expect(row).toContainText('DRAFT')
    await expect(row).toContainText(productKey)
    await shot(page, testInfo, 'list-after-create-75')

    // 同じ商品枠でもう一度作成ダイアログを開くと、選択値は初期化されている（前回の値を引きずらない）
    const dialog2 = await openCreateDialog(page)
    await expect(dialog2.locator('#pr-product-key')).toContainText('商品キーを選択')
    await expect(dialog2.locator('#pr-tax-code')).toContainText('税コードを選択')
    await expect(dialog2.getByLabel('tax-preview-hint')).toBeVisible()
  } finally {
    await context.close()
  }
})

// ===========================================================================
// FORM-05 ロール横断（権限なし・他テナント）: 作成フォームに到達できない
// ===========================================================================
for (const [label, creds] of [['一般ユーザー（組織 e2e の ADMIN）', USER], ['他テナント ADMIN（チーム fc-u-18）', OTHER_TENANT_ADMIN]] as const) {
  test(`FORM-05 ${label}: 価格改定の画面に導線が無く、直打ちは権限なし表示で、作成・税コード API も 401/403`, async ({ browser }, testInfo) => {
    const { context, page } = await newLoggedInPage(browser, creds)
    try {
      await gotoAndSettle(page, '/dashboard')
      await expect(page.locator('a[href="/system-admin"], a[href*="/system-admin/price-revisions"]')).toHaveCount(0)
      await shot(page, testInfo, `nav-${creds.email.split('@')[0]}`)

      // 直打ち: ミドルウェアが /dashboard へ戻し、拒否トースト（5 秒で消える）を出す。toast の出現を先に待つ。
      await page.goto('/system-admin/price-revisions', { waitUntil: 'domcontentloaded', timeout: 180_000 })
      const toast = page.locator('.p-toast-message').filter({ hasText: DENIED_TITLE })
      await expect(toast).toContainText(DENIED_BODY, { timeout: 60_000 })
      await shot(page, testInfo, `denied-${creds.email.split('@')[0]}`)
      await page.waitForURL(/\/dashboard/, { timeout: 30_000 })
      await expect(page.getByRole('button', { name: 'create-price-revision' })).toHaveCount(0)
      await expect(page.getByRole('button', { name: 'tax-codes' })).toHaveCount(0)
      await note(testInfo, '直打ち後の URL と拒否トースト', { url: page.url(), title: DENIED_TITLE, body: DENIED_BODY })

      const hdr = { 'Idempotency-Key': crypto.randomUUID(), 'Content-Type': 'application/json' }
      const results = [
        (await page.request.get(PLANS_API)).status(),
        (await page.request.get(FEATURES_API)).status(),
        (await page.request.get(TAX_API)).status(),
        (await page.request.post(TAX_API, { headers: hdr, data: { code: 'E2E_DENY', displayName: 'x', rateBasisPoints: 750, stripeTaxCode: 'txcd_10000000', validFrom: new Date().toISOString(), validUntil: null, enabled: true } })).status(),
      ]
      await note(testInfo, '選択肢取得・税コード登録 API のステータス', results)
      for (const s of results) expect([401, 403]).toContain(s)
    } finally {
      await context.close()
    }
  })
}

// ===========================================================================
// FORM-99 後始末: この実行が作った改定と税コードだけを画面から取り消し・無効化する
// ===========================================================================
test('FORM-99 後始末: 自テストが作成した価格改定と税コードを画面で取り消し・無効化する', async ({ browser }, testInfo) => {
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  try {
    for (const id of created.revisionIds) {
      await gotoAndSettle(page, `/system-admin/price-revisions/${id}`)
      await page.getByRole('button', { name: 'cancel-price-revision', exact: true }).click()
      const confirm = page.locator('.p-confirmdialog, [role="alertdialog"]').first()
      await expect(confirm).toBeVisible()
      const [res] = await Promise.all([
        page.waitForResponse((r) => r.url() === `${PR_API}/${id}/cancel`),
        confirm.locator('.p-button-danger, button.p-confirmdialog-accept-button').last().click(),
      ])
      expect(res.status(), '作成した価格改定の取り消しは 200').toBe(200)
    }
    await gotoAndSettle(page, '/system-admin/price-revisions')
    const dialog = await openTaxCodeDialog(page)
    for (const id of created.taxCodeIds) {
      const [del] = await Promise.all([
        page.waitForResponse((r) => r.url() === `${TAX_API}/${id}` && r.request().method() === 'DELETE'),
        dialog.getByRole('button', { name: `deactivate-tax-code-${id}` }).click(),
      ])
      expect(del.status(), '作成した税コードの無効化は 2xx').toBeLessThan(300)
    }
    // この実行の税コードが一覧に残っていない
    await expect(dialog.getByRole('cell', { name: new RegExp(RUN_TAG) })).toHaveCount(0)
    const rev = (await (await page.request.get(`${PR_API}?size=100`)).json()) as { data: { items: { id: string; status: string }[] } }
    const own = rev.data.items.filter((i) => created.revisionIds.includes(i.id))
    await note(testInfo, '自テストの改定の最終状態', own)
    for (const i of own) expect(i.status).toBe('CANCELLED')
  } finally {
    await context.close()
  }
})
