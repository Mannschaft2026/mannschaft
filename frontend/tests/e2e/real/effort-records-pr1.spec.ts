import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { test, expect, type Browser, type Page } from '@playwright/test'
import { API, FE, createActor, openAs, gotoAndHydrate, type Actor } from './helpers/n1p1-world'

/**
 * F06.4.1 PR-1 実機E2E: 個人の頑張り記録 `/my/effort-records`（URL 直打ち・導線なし）。
 * - API を使うのはログイン・前提データ（ページ送り用の大量記録）・後始末・他アカウント直打ちの確認補助だけ。
 *   対象操作（作成・編集・削除・振り返り）は必ず画面で行う。
 * - 利用者は毎回新規登録（A: CRUD/i18n/導線, P: ページ送り+振り返り, B: 他アカウント）。afterAll で記録を削除して退会させる。
 * - 専用設定 playwright-effort-pr1.config.ts（検証用 BE 8081 / 検証用 FE 3001）から実行する。
 */
const EVIDENCE = process.env.EFFORT_EVIDENCE_DIR ?? path.resolve('test-results', 'effort-evidence')
mkdirSync(EVIDENCE, { recursive: true })

const PAGE_PATH = '/my/effort-records'
const STUDY = '01938a00-0000-7000-8000-000000000001'
const EXERCISE = '01938a00-0000-7000-8000-000000000002'
const WEIGHT = '01938a00-0000-7000-8000-000000000003'
const LIST_API = /\/api\/v1\/effort-records\/me$/
const STATS_API = /\/api\/v1\/effort-records\/me\/stats$/

interface Rec {
  id: string
  version: number
  recordedOn: string
  values: Array<{ fieldKey: string; value: string | null }>
}

let A: Actor
let P: Actor
let B: Actor
const actors: Actor[] = []
const tokens = new Map<string, string>()

async function token(actor: Actor): Promise<string> {
  const cached = tokens.get(actor.email)
  if (cached) return cached
  const res = await fetch(`${API}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: actor.email, password: actor.password }),
  })
  if (!res.ok) throw new Error(`login ${actor.email}: ${res.status}`)
  const t = (await res.json()).data.accessToken as string
  tokens.set(actor.email, t)
  return t
}

async function effortApi(actor: Actor, method: string, p: string, body?: unknown, extra: Record<string, string> = {}) {
  const res = await fetch(`${API}/api/v1/effort-records/me${p}`, {
    method,
    headers: { 'content-type': 'application/json', authorization: `Bearer ${await token(actor)}`, ...extra },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json: unknown = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    json = text
  }
  return { status: res.status, body: json as { data?: unknown; meta?: { hasNext?: boolean; nextCursor?: string } } }
}

/** 前提データ（ページ送り用）の作成。対象操作ではない。 */
async function seed(actor: Actor, definitionUuid: string, recordedOn: string, values: Array<[string, string]>) {
  const res = await effortApi(
    actor,
    'POST',
    '',
    {
      recordedOn,
      recordTimezone: 'Asia/Tokyo',
      definitionUuid,
      definitionRevision: 1,
      values: values.map(([fieldKey, value]) => ({ fieldKey, value })),
      privateNote: null,
    },
    { 'Idempotency-Key': crypto.randomUUID() },
  )
  if (res.status !== 201 && res.status !== 200) throw new Error(`seed ${recordedOn}: ${res.status} ${JSON.stringify(res.body)}`)
  return res.body.data as Rec
}

function jstToday(): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Tokyo' }).format(new Date())
}
function shiftDay(ymd: string, delta: number): string {
  const [y, m, d] = ymd.split('-').map(Number)
  return new Date(Date.UTC(y, m - 1, d + delta)).toISOString().slice(0, 10)
}

async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(EVIDENCE, `${name}.png`) })
}

async function openPage(browser: Browser, actor: Actor) {
  const r = await openAs(browser, actor, PAGE_PATH)
  await expect(r.page.getByTestId('effort-save')).toBeVisible({ timeout: 120_000 })
  return r
}

async function pickPreset(page: Page, name: string) {
  await page.getByTestId('effort-preset').click()
  await page.getByRole('option', { name, exact: true }).click()
}

async function save(page: Page) {
  const [res] = await Promise.all([
    page.waitForResponse((r) => r.request().method() !== 'GET' && LIST_API.test(new URL(r.url()).pathname) || /\/effort-records\/me\/[^/]+$/.test(new URL(r.url()).pathname) && r.request().method() === 'PUT', { timeout: 60_000 }),
    page.getByTestId('effort-save').click(),
  ])
  return res.status()
}

const list = (page: Page) => page.getByTestId('effort-list').locator('li')

test.describe.configure({ mode: 'serial' })

test.beforeAll(async () => {
  test.setTimeout(300_000)
  A = await createActor('effa')
  P = await createActor('effp')
  B = await createActor('effb')
  actors.push(A, P, B)
})

test.afterAll(async () => {
  const failures: string[] = []
  for (const actor of actors) {
    // 作成した記録を全件削除（利用者は本 spec が作った使い捨てなので全件が自分の作成物）
    let guard = 0
    for (;;) {
      const res = await effortApi(actor, 'GET', '?limit=100')
      const rows = ((res.body as { data?: Rec[] }).data ?? []) as Rec[]
      if (res.status !== 200 || rows.length === 0 || guard++ > 20) break
      for (const r of rows) {
        const d = await effortApi(actor, 'DELETE', `/${r.id}?version=${r.version}`, undefined, { 'Idempotency-Key': crypto.randomUUID() })
        if (d.status !== 200 && d.status !== 204 && d.status !== 404) failures.push(`delete ${r.id}:${d.status}`)
      }
    }
    const w = await fetch(`${API}/api/v1/users/me`, {
      method: 'DELETE',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${await token(actor)}` },
      body: JSON.stringify({ currentPassword: actor.password }),
    })
    console.log(`[effort] cleanup withdraw ${actor.label} -> ${w.status}`)
    if (![200, 204, 404].includes(w.status)) failures.push(`withdraw ${actor.label}:${w.status}`)
  }
  if (failures.length) throw new Error(`後始末に失敗: ${failures.join(' ')}`)
})

// ---------------------------------------------------------------------------
test('E1 勉強: 作成→一覧→同データ保持で編集→再読込で永続化→削除', async ({ browser }) => {
  test.setTimeout(300_000)
  const { page, context } = await openPage(browser, A)
  await expect(page.getByTestId('effort-list')).toHaveCount(0) // 新規利用者なので空
  await pickPreset(page, '勉強')
  await page.locator('#activity-field-subject').fill('数学E2E')
  await page.locator('#activity-field-duration').fill('45')
  await page.locator('#effort-private-note').fill('メモ初版')
  expect(await save(page)).toBeLessThan(300)
  await expect(page.getByTestId('effort-saved-notice')).toBeVisible()
  await expect(list(page)).toHaveCount(1)
  await expect(list(page).first()).toContainText('数学E2E')
  await expect(list(page).first()).toContainText('45')
  await shot(page, 'e1-1-created')

  // 再読込→編集（科目は触らず保持したまま時間とメモだけ変える）
  await gotoAndHydrate(page, PAGE_PATH)
  await expect(list(page)).toHaveCount(1)
  await list(page).first().getByRole('button', { name: '編集' }).click()
  await expect(page.locator('#activity-field-subject')).toHaveValue('数学E2E')
  await expect(page.locator('#effort-private-note')).toHaveValue('メモ初版')
  await page.locator('#activity-field-duration').fill('52')
  expect(await save(page)).toBeLessThan(300)
  await expect(page.getByTestId('effort-saved-notice')).toBeVisible()
  await expect(list(page).first()).toContainText('52')
  await expect(list(page).first()).toContainText('数学E2E')

  // 再読込で永続化確認（科目が消えていないこと = 同データ保持の更新）
  await gotoAndHydrate(page, PAGE_PATH)
  await expect(list(page)).toHaveCount(1)
  await expect(list(page).first()).toContainText('数学E2E')
  await expect(list(page).first()).toContainText('52')
  await list(page).first().getByRole('button', { name: '編集' }).click()
  await expect(page.locator('#activity-field-subject')).toHaveValue('数学E2E')
  await expect(page.locator('#effort-private-note')).toHaveValue('メモ初版')
  await shot(page, 'e1-2-edited-persisted')

  // 削除
  await list(page).first().getByRole('button', { name: '削除' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '削除' }).click()
  await expect(page.getByText('まだ記録がありません')).toBeVisible()
  await gotoAndHydrate(page, PAGE_PATH)
  await expect(page.getByText('まだ記録がありません')).toBeVisible()
  await shot(page, 'e1-3-deleted')
  await context.close()
})

test('E2 運動: 必須(時間)未入力はエラー→入力して作成→編集→削除', async ({ browser }) => {
  test.setTimeout(300_000)
  const { page, context } = await openPage(browser, A)
  await pickPreset(page, '運動')
  await page.getByTestId('effort-save').click()
  await expect(page.getByTestId('effort-form-error')).toContainText('必須')
  await expect(page.getByTestId('effort-save')).toBeEnabled()
  await page.locator('#activity-field-duration').fill('30')
  await page.locator('#activity-field-intensity').fill('7')
  await page.locator('#activity-field-menu').fill('ランニングE2E')
  expect(await save(page)).toBeLessThan(300)
  await expect(list(page)).toHaveCount(1)
  await gotoAndHydrate(page, PAGE_PATH)
  await expect(list(page).first()).toContainText('ランニングE2E')
  await list(page).first().getByRole('button', { name: '編集' }).click()
  await expect(page.locator('#activity-field-menu')).toHaveValue('ランニングE2E')
  await expect(page.locator('#activity-field-intensity')).toHaveValue('7')
  await page.locator('#activity-field-duration').fill('35')
  expect(await save(page)).toBeLessThan(300)
  await gotoAndHydrate(page, PAGE_PATH)
  await expect(list(page).first()).toContainText('35')
  await expect(list(page).first()).toContainText('ランニングE2E')
  await shot(page, 'e2-exercise')
  await list(page).first().getByRole('button', { name: '削除' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '削除' }).click()
  await expect(page.getByText('まだ記録がありません')).toBeVisible()
  await context.close()
})

test('E3 体重 60.25 を保存でき表示も 60.25（作成→編集→削除）', async ({ browser }) => {
  test.setTimeout(300_000)
  const { page, context } = await openPage(browser, A)
  await pickPreset(page, '体重')
  await page.locator('#activity-field-weight').fill('60.25')
  await page.locator('#activity-field-weight').press('Tab')
  expect(await save(page)).toBeLessThan(300)
  await expect(list(page)).toHaveCount(1)
  await expect(list(page).first()).toContainText('60.25')
  await gotoAndHydrate(page, PAGE_PATH)
  await expect(list(page).first()).toContainText('60.25')
  await list(page).first().getByRole('button', { name: '編集' }).click()
  await expect(page.locator('#activity-field-weight')).toHaveValue('60.25')
  await shot(page, 'e3-weight-6025')
  await page.locator('#activity-field-weight').fill('61.75')
  await page.locator('#activity-field-weight').press('Tab')
  expect(await save(page)).toBeLessThan(300)
  await gotoAndHydrate(page, PAGE_PATH)
  await expect(list(page).first()).toContainText('61.75')
  await list(page).first().getByRole('button', { name: '削除' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '削除' }).click()
  await expect(page.getByText('まだ記録がありません')).toBeVisible()
  await context.close()
})

test('E4 記録日を空にして保存→エラー表示・保存ボタン復帰・修正して保存できる', async ({ browser }) => {
  test.setTimeout(300_000)
  const { page, context } = await openPage(browser, A)
  await pickPreset(page, '勉強')
  await page.locator('#activity-field-duration').fill('20')
  let posted = 0
  page.on('request', (r) => {
    if (r.method() === 'POST' && LIST_API.test(new URL(r.url()).pathname)) posted++
  })
  await page.locator('#effort-recorded-on').fill('')
  await page.locator('#effort-recorded-on').press('Tab')
  await page.getByTestId('effort-save').click()
  await expect(page.getByTestId('effort-form-error')).toHaveText('記録日を入力してください')
  await expect(page.getByTestId('effort-save')).toBeEnabled()
  expect(posted).toBe(0)
  await shot(page, 'e4-1-date-error')
  // 復帰確認: 日付を入れ直して保存できる
  await page.locator('#effort-recorded-on').fill(jstToday().replace(/-/g, '/'))
  await page.locator('#effort-recorded-on').press('Tab')
  expect(await save(page)).toBeLessThan(300)
  await expect(page.getByTestId('effort-form-error')).toHaveCount(0)
  await expect(list(page)).toHaveCount(1)
  await list(page).first().getByRole('button', { name: '削除' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '削除' }).click()
  await expect(page.getByText('まだ記録がありません')).toBeVisible()
  await context.close()
})

// ---------------------------------------------------------------------------
const today = jstToday()
const seededStudy: Array<{ date: string; minutes: number }> = []
let seededTotal = 0

test('E5 続き読込: 49件を作り、さらに表示で重複なく全件(20+20+9)', async ({ browser }) => {
  test.setTimeout(600_000)
  // 前提データ（API）: 勉強46件（今日から過去へ・時間は全て別値・科目は交互）+ 運動1 + 体重1 + 勉強(合計0)1
  for (let i = 0; i < 46; i++) {
    const date = shiftDay(today, -i)
    const minutes = 300 + i
    await seed(P, STUDY, date, [['subject', i % 2 === 0 ? '数学' : '英語'], ['duration', String(minutes)]])
    seededStudy.push({ date, minutes })
  }
  await seed(P, EXERCISE, today, [['duration', '25'], ['menu', '筋トレ']])
  await seed(P, WEIGHT, today, [['weight', '60.25']])
  await seed(P, STUDY, '2020-03-10', [['subject', '歴史'], ['duration', '0']])
  seededTotal = 46 + 3

  const { page, context } = await openAs(browser, P)
  const ids: string[] = []
  page.on('response', async (r) => {
    if (r.request().method() === 'GET' && LIST_API.test(new URL(r.url()).pathname)) {
      try {
        const j = (await r.json()) as { data?: Rec[] }
        for (const x of j.data ?? []) ids.push(x.id)
      } catch { /* 本文なし */ }
    }
  })
  await gotoAndHydrate(page, PAGE_PATH)
  await expect(list(page)).toHaveCount(20, { timeout: 120_000 })
  const more = page.getByRole('button', { name: 'さらに表示' })
  await expect(more).toBeVisible()
  await more.click()
  await expect(list(page)).toHaveCount(40)
  await more.click()
  await expect(list(page)).toHaveCount(seededTotal)
  await expect(more).toHaveCount(0)
  const texts = await list(page).allInnerTexts()
  expect(new Set(texts.map((t) => t.replace(/\s+/g, ' '))).size).toBe(texts.length)
  expect(new Set(ids).size).toBe(ids.length)
  expect(ids.length).toBe(seededTotal)
  await shot(page, 'e5-pagination-all')
  await context.close()
})

test('E6 振り返り: 週/月/年/任意・棒/折線/円の切替（許可表外は出ない）・円は分母0で描かない', async ({ browser }) => {
  test.setTimeout(600_000)
  const { page, context } = await openAs(browser, P, PAGE_PATH)
  await expect(page.getByTestId('effort-save')).toBeVisible({ timeout: 120_000 })
  await page.getByTestId('effort-tab-review').click()

  const options = async () => {
    await page.locator('#effort-review-chart').click()
    const t = (await page.locator('[role=listbox]:visible').last().getByRole('option').allInnerTexts()).map((x) => x.trim())
    await page.keyboard.press('Escape')
    return t
  }
  const pickReviewDef = async (name: string) => {
    await page.locator('#effort-review-def').click()
    await page.getByRole('option', { name, exact: true }).click()
  }
  const pickChart = async (name: string) => {
    await page.locator('#effort-review-chart').click()
    await page.getByRole('option', { name }).click()
  }
  const statsView = page.getByTestId('effort-stats-view')

  // --- 勉強: 棒/円のみ（折線は出ない）。月 = 暦月
  await pickReviewDef('勉強')
  expect(await options()).toEqual(['棒グラフ (時間)', '円グラフ (時間)'])
  await page.getByTestId('effort-period-month').click()
  const [y, m] = today.split('-')
  const lastDay = new Date(Date.UTC(Number(y), Number(m), 0)).getUTCDate()
  await expect(page.getByTestId('effort-range')).toHaveText(`${y}-${m}-01 - ${y}-${m}-${String(lastDay).padStart(2, '0')}`)
  await expect(statsView.locator('canvas')).toBeVisible({ timeout: 60_000 })
  await shot(page, 'e6-1-study-bar-month')

  await pickChart('円グラフ')
  await expect(statsView.locator('canvas')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByTestId('effort-stats-row')).toHaveCount(2) // 数学 / 英語
  const monthPrefix = `${y}-${m}`
  const expectedTotal = seededStudy.filter((s) => s.date.startsWith(monthPrefix)).reduce((a, s) => a + s.minutes, 0)
  const totalText = (await page.getByTestId('effort-stats-total').innerText()).trim()
  console.log(`[effort] pie total(month) expected=${expectedTotal} shown=${JSON.stringify(totalText)}`)
  expect(Number(totalText.split(/\s/)[0])).toBe(expectedTotal)
  await shot(page, 'e6-2-study-pie-month')

  // --- 週 / 年 / 任意
  await page.getByTestId('effort-period-week').click()
  await expect(page.getByTestId('effort-range')).toContainText(' - ')
  await expect(statsView).toBeVisible()
  await page.getByTestId('effort-period-year').click()
  await expect(page.getByTestId('effort-range')).toHaveText(`${y}-01-01 - ${y}-12-31`)
  await expect(statsView).toBeVisible()
  await page.getByTestId('effort-period-custom').click()
  await page.locator('#effort-from').fill(`${y}/${m}/01`)
  await page.locator('#effort-from').press('Tab')
  await page.locator('#effort-to').fill(today.replace(/-/g, '/'))
  await page.locator('#effort-to').press('Tab')
  await expect(statsView.locator('canvas')).toBeVisible({ timeout: 60_000 })
  await shot(page, 'e6-3-custom')
  // 任意期間の不正（終了<開始）はエラー表示
  await page.locator('#effort-to').fill(`${y}/${m}/01`)
  await page.locator('#effort-from').fill(today.replace(/-/g, '/'))
  await page.locator('#effort-from').press('Tab')
  if (today !== `${y}-${m}-01`) await expect(page.getByText('期間は開始日から終了日まで')).toBeVisible()

  // --- 運動: 棒のみ
  await page.getByTestId('effort-period-month').click()
  await pickReviewDef('運動')
  expect(await options()).toEqual(['棒グラフ (時間)'])
  await expect(statsView.locator('canvas')).toBeVisible({ timeout: 60_000 })

  // --- 体重: 折線のみ。最新値 60.25
  await pickReviewDef('体重')
  expect(await options()).toEqual(['折れ線グラフ (体重)'])
  await expect(statsView.locator('canvas')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByTestId('effort-stats-table')).toContainText('60.25')
  await shot(page, 'e6-4-weight-line')

  // --- 0件: 過去の空の期間（年 -3）は円も棒も描かない
  await pickReviewDef('勉強')
  await pickChart('円グラフ')
  await page.getByTestId('effort-period-year').click()
  for (let i = 0; i < 3; i++) await page.getByRole('button', { name: '前の期間' }).click()
  await expect(page.getByTestId('effort-stats-nodata')).toBeVisible({ timeout: 60_000 })
  await expect(statsView.locator('canvas')).toHaveCount(0)
  await shot(page, 'e6-5-nodata')

  // --- 合計0(ZERO_TOTAL): 2020 年（歴史 0 分のみ）は円を描かず説明を出す。棒は描ける
  for (let i = 0; i < Number(y) - 2020 - 3; i++) await page.getByRole('button', { name: '前の期間' }).click()
  await expect(page.getByTestId('effort-range')).toHaveText('2020-01-01 - 2020-12-31')
  await expect(page.getByTestId('effort-stats-zerototal')).toBeVisible({ timeout: 60_000 })
  await expect(statsView.locator('canvas')).toHaveCount(0)
  await shot(page, 'e6-6-zerototal-pie')
  await pickChart('棒グラフ')
  await expect(statsView.locator('canvas')).toBeVisible({ timeout: 60_000 })
  await context.close()
})

test('E7 未認証で URL 直打ち → ログインへリダイレクト', async ({ browser }) => {
  const context = await browser.newContext({ baseURL: FE, storageState: { cookies: [], origins: [] }, locale: 'ja-JP' })
  const page = await context.newPage()
  await page.goto(`${FE}${PAGE_PATH}`, { waitUntil: 'commit' })
  await page.waitForURL(/\/login/, { timeout: 180_000 })
  expect(page.url()).toContain('/login')
  await shot(page, 'e7-unauth-login')
  await context.close()
})

test('E8 他アカウント B: P の記録が一覧・集計に出ず、P の記録IDは直打ちで404、更新・削除も不可', async ({ browser }) => {
  test.setTimeout(300_000)
  const pRows = ((await effortApi(P, 'GET', '?limit=1')).body.data ?? []) as Rec[]
  expect(pRows.length).toBe(1)
  const pid = pRows[0].id
  // P 自身は同 ID を取得できる（対照）
  expect((await effortApi(P, 'GET', `/${pid}`)).status).toBe(200)

  const { page, context } = await openPage(browser, B)
  await expect(page.getByText('まだ記録がありません')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByTestId('effort-list')).toHaveCount(0)
  await shot(page, 'e8-1-B-list-empty')
  await page.getByTestId('effort-tab-review').click()
  await pickReview(page, '勉強')
  await page.getByTestId('effort-period-month').click()
  await expect(page.getByTestId('effort-stats-nodata')).toBeVisible({ timeout: 60_000 })
  await page.getByTestId('effort-period-year').click()
  await expect(page.getByTestId('effort-stats-nodata')).toBeVisible({ timeout: 60_000 })
  await shot(page, 'e8-2-B-review-nodata')
  await context.close()

  // 確認補助（API 直叩き）: P の記録 UUID を B で
  const get = await effortApi(B, 'GET', `/${pid}`)
  const put = await effortApi(B, 'PUT', `/${pid}`, { version: 0, recordedOn: today, values: [{ fieldKey: 'duration', value: '1' }], privateNote: null })
  const del = await effortApi(B, 'DELETE', `/${pid}?version=${pRows[0].version}`, undefined, { 'Idempotency-Key': crypto.randomUUID() })
  console.log(`[effort] B -> P record: GET ${get.status} PUT ${put.status} DELETE ${del.status}`)
  expect(get.status).toBe(404)
  expect(put.status).toBe(404)
  expect(del.status).toBe(404)
  // P の記録は無傷
  expect((await effortApi(P, 'GET', `/${pid}`)).status).toBe(200)
})

async function pickReview(page: Page, name: string) {
  await page.locator('#effort-review-def').click()
  await page.getByRole('option', { name, exact: true }).click()
}

test('E9 導線: 個人メニュー・ダッシュボード等に /my/effort-records へのリンクが出ていない', async ({ browser }) => {
  test.setTimeout(300_000)
  const { page, context } = await openAs(browser, A)
  for (const p of ['/', '/dashboard', '/my', '/settings', '/notifications']) {
    await gotoAndHydrate(page, p)
    await page.waitForTimeout(3_000)
    const links = await page.locator('a[href*="effort-records"]').count()
    const text = await page.getByText('頑張り記録').count()
    console.log(`[effort] nav ${p}: final=${new URL(page.url()).pathname} links=${links} text=${text}`)
    expect(links, `${p} にリンクがある`).toBe(0)
    expect(text, `${p} に文言がある`).toBe(0)
  }
  // URL 直打ちでは到達できる（PR-4 まで隠すだけで画面は有効）
  await gotoAndHydrate(page, PAGE_PATH)
  await expect(page.getByTestId('effort-save')).toBeVisible({ timeout: 60_000 })
  // ヘッダーのメニュー類を開いた状態も確認（ログアウトは除外）
  await gotoAndHydrate(page, '/my')
  await page.waitForTimeout(2_000)
  const menuButtons = page.locator('header button:not([aria-label*="ログアウト"]):not([aria-label*="Logout"])')
  const n = await menuButtons.count()
  for (let i = 0; i < Math.min(n, 6); i++) {
    await menuButtons.nth(i).click({ timeout: 3_000 }).catch(() => undefined)
    await page.waitForTimeout(300)
    expect(await page.locator('a[href*="effort-records"]').count()).toBe(0)
    await page.keyboard.press('Escape')
  }
  await shot(page, 'e9-no-entry-link')
  await context.close()
})

test('E10 en に切替: 画面の文言が英語で、日本語が残らない', async ({ browser }) => {
  test.setTimeout(300_000)
  const first = await openAs(browser, A, '/settings/account')
  let page = first.page
  let context = first.context
  // 設定画面で表示言語を English に切替（実際のアカウント設定の操作）
  await page.getByText('表示言語', { exact: true }).locator('xpath=following-sibling::*[1]').click()
  await page.getByRole('option', { name: 'English' }).click()
  await page.locator('div').filter({ has: page.getByText('表示言語', { exact: true }) }).filter({ has: page.getByRole('button', { name: '保存' }) }).last().getByRole('button', { name: '保存' }).click()
  await expect(page.getByText('Display Language').first()).toBeVisible({ timeout: 60_000 })
  // 保存後に再ログイン（アカウント言語は users.locale が正。同一セッションのリロードは localStorage の旧値へ戻る既知挙動を避ける）
  await shot(page, 'e10-0-language-saved')
  await context.close()
  const second = await openAs(browser, A, PAGE_PATH)
  page = second.page
  context = second.context
  await expect(page.getByTestId('effort-save')).toBeVisible({ timeout: 120_000 })
  const jp = /[぀-ヿ㐀-鿿]/
  const pageText = async () => (await page.locator('main, #__nuxt').first().innerText())
  await expect(page.getByRole('heading', { name: /Effort records/i }).or(page.getByText('Effort records').first())).toBeVisible()
  await expect(page.getByTestId('effort-save')).toHaveText(/Save for myself only/)
  // フォームにエラー文言も英語（日付を空にして保存）
  await page.locator('#effort-recorded-on').fill('')
  await page.locator('#effort-recorded-on').press('Tab')
  await page.getByTestId('effort-save').click()
  await expect(page.getByTestId('effort-form-error')).toHaveText('Please enter the record date')
  // 作成して一覧の文言（編集/削除/ダイアログ）も確認
  await page.locator('#effort-recorded-on').fill(jstToday().replace(/-/g, '/'))
  await page.locator('#effort-recorded-on').press('Tab')
  await pickPreset(page, 'Study')
  await page.locator('#activity-field-duration').fill('10')
  expect(await save(page)).toBeLessThan(300)
  await expect(list(page)).toHaveCount(1)
  await expect(list(page).first().getByRole('button', { name: 'Edit' })).toBeVisible()
  let body = await pageText()
  const resid1 = body.split('\n').filter((l) => jp.test(l))
  console.log(`[effort] en record-tab Japanese residue lines: ${JSON.stringify(resid1)}`)
  await shot(page, 'e10-1-en-record')
  await page.getByTestId('effort-tab-review').click()
  await expect(page.getByTestId('effort-period-week')).toHaveText('Week')
  await expect(page.getByTestId('effort-stats-view')).toBeVisible({ timeout: 60_000 })
  body = await pageText()
  const resid2 = body.split('\n').filter((l) => jp.test(l))
  console.log(`[effort] en review-tab Japanese residue lines: ${JSON.stringify(resid2)}`)
  await shot(page, 'e10-2-en-review')
  await page.locator('#effort-review-chart').click()
  const opts = await page.locator('[role=listbox]:visible').last().getByRole('option').allInnerTexts()
  console.log(`[effort] en chart options: ${JSON.stringify(opts)}`)
  await page.keyboard.press('Escape')
  expect(opts.filter((o) => jp.test(o))).toEqual([])
  expect([...resid1, ...resid2]).toEqual([])
  // 削除ダイアログ
  await page.getByTestId('effort-tab-record').click()
  await list(page).first().getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByRole('alertdialog')).toContainText('This record will be deleted')
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByText('No records yet')).toBeVisible()
  await context.close()
})
