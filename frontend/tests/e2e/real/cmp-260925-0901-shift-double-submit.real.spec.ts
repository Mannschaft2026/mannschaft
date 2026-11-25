/**
 * 実機E2E: shift 配下の作成・保存ボタンの連打で二重作成されない（CMP-260925-0901）
 *
 * 受け入れ条件:
 *   AC1 /shift の「新規作成」ダイアログで「作成」を素早く2回押しても、シフト表は1件だけ作られる
 *       （画面の一覧と API の一覧件数の両方で確認）
 *   AC2 /shift/{id}/edit の「一括作成」ダイアログで「作成」を素早く2回押しても、枠は1回分だけ作られる
 *   AC3 チームの「ポジション管理」で「保存」を素早く2回押しても、ポジションは1件だけ作られる
 *   AC4 権限なし（MEMBER）には作成ボタン・一括作成・ポジション管理が出ない。他チーム非所属ユーザーにはチームが出ない
 *
 * 連打は「同じ JS タスク内で 2 回 click を発火する」rapidDoubleClick で再現する。
 * 再描画（ボタンの無効化・ローディング表示）より前に 2 回目が届く、最悪の連打を表す。
 * Playwright の dblclick は 2 回の click の間に再描画が挟まり、ガードの無い実装でも 1 件に見えて検出力が足りないため使わない
 * （ガードの無いチーム画面のシフト表作成で POST 2 回・2 件作成になることを、この方式で確認済み。証拠文書参照）。
 * API の用途: ログイン・前提データ作成・件数の読み取り検証・後始末のみ。作成操作そのものは画面で行う。
 */
import {
  test as base,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const BE_API = `${API_BASE_URL}/api/v1`
const PASSWORD = 'TestPass2026!'

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
/** fc-u-18 の素の ADMIN */
const TEAM_ADMIN_EMAIL = 'e2e-dummy-1@test.mannschaft.local'
/** fc-u-18 の MEMBER */
const MEMBER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
/** どのチームにも非所属 */
const OUTSIDER_EMAIL = 'e2e-outsider@test.mannschaft.local'

const TEAM_SLUG = 'fc-u-18'
const RUN_TAG = `R${Date.now().toString(36)}`


function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

// ログインは email+IP で 1 分 10 回までのレート制限があり、他の実機E2Eとも共有されるため、
// トークンは email ごとに 1 回だけ取得して使い回し、429 のときは待って再試行する。
const tokenCache = new Map<string, string>()
async function login(ctx: APIRequestContext, email: string): Promise<string> {
  const cached = tokenCache.get(email)
  if (cached) return cached
  for (let attempt = 0; attempt < 8; attempt++) {
    const res = await ctx.post(`${BE_API}/auth/login`, {
      headers: { 'Content-Type': 'application/json' },
      data: { email, password: PASSWORD },
    })
    if (res.status() === 429) {
      await new Promise((r) => setTimeout(r, 15_000))
      continue
    }
    if (!res.ok()) throw new Error(`ログイン失敗(${email}): ${res.status()} ${await res.text()}`)
    const token = (await res.json()).data.accessToken as string
    tokenCache.set(email, token)
    return token
  }
  throw new Error(`ログインがレート制限で通らない(${email})`)
}

const sessionCache = new Map<string, Awaited<ReturnType<BrowserContext['storageState']>>>()
async function openAs(browser: Browser, email: string): Promise<Page> {
  const saved = sessionCache.get(email)
  const context = await browser.newContext({
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    ...(saved ? { storageState: saved } : {}),
  })
  const page = await context.newPage()
  if (!saved) {
    for (let attempt = 0; attempt < 8; attempt++) {
      try {
        await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE_URL })
        break
      } catch (e) {
        if (attempt === 7 || !String(e).includes('429')) throw e
        await new Promise((r) => setTimeout(r, 15_000))
      }
    }
    sessionCache.set(email, await context.storageState())
  }
  return page
}

function todayIsoJst(): string {
  return new Date(Date.now() + 9 * 60 * 60 * 1000).toISOString().slice(0, 10)
}
function addDaysIso(baseIso: string, days: number): string {
  const [y, m, d] = baseIso.split('-').map(Number)
  const dt = new Date(Date.UTC(y!, m! - 1, d!))
  dt.setUTCDate(dt.getUTCDate() + days)
  return dt.toISOString().slice(0, 10)
}

const TITLE_SCHEDULE = `連打0901_表_${RUN_TAG}`
const TITLE_BULK_SCHEDULE = `連打0901_枠_${RUN_TAG}`
const POSITION_NAME = `連打0901_役_${RUN_TAG}`

let apiCtx: APIRequestContext
let adminToken: string
let teamName: string
let teamNumericId: number
let bulkScheduleId: number

base.use({ storageState: { cookies: [], origins: [] } })
const test = base
test.describe.configure({ mode: 'serial' })
test.setTimeout(180_000)

async function listSchedulesByTag(): Promise<Array<{ id: number; content: { title: string } }>> {
  const res = await apiCtx.get(`${BE_API}/shifts/schedules?teamId=${teamNumericId}`, {
    headers: authHeaders(adminToken),
  })
  expect(res.status()).toBe(200)
  return ((await res.json()).data as Array<{ id: number; content: { title: string } }>).filter((s) =>
    s.content?.title?.includes(RUN_TAG),
  )
}
async function listPositionsByTag(): Promise<Array<{ id: number; name: string }>> {
  const res = await apiCtx.get(`${BE_API}/shifts/positions?teamId=${teamNumericId}`, {
    headers: authHeaders(adminToken),
  })
  expect(res.status()).toBe(200)
  return ((await res.json()).data as Array<{ id: number; name: string }>).filter((p) => p.name?.includes(RUN_TAG))
}

test.beforeAll(async () => {
  apiCtx = await playwrightRequest.newContext()
  adminToken = await login(apiCtx, ADMIN_EMAIL)
  const teamRes = await apiCtx.get(`${BE_API}/teams/${TEAM_SLUG}`, { headers: authHeaders(adminToken) })
  expect(teamRes.status(), 'チーム取得').toBe(200)
  const team = (await teamRes.json()).data as { numericId: number; basicInfo?: { name?: string }; name?: string }
  teamNumericId = team.numericId
  teamName = team.basicInfo?.name ?? team.name!

  // 役者の実効ロールを実測で裏取りする
  for (const [email, expected] of [
    [TEAM_ADMIN_EMAIL, 'ADMIN'],
    [MEMBER_EMAIL, 'MEMBER'],
  ] as const) {
    const ctx = await playwrightRequest.newContext()
    const token = await login(ctx, email)
    const res = await ctx.get(`${BE_API}/teams/${TEAM_SLUG}/me/permissions`, { headers: authHeaders(token) })
    expect(res.status(), `${email} 権限取得`).toBe(200)
    expect(((await res.json()).data as { roleName: string }).roleName, `${email} の実効ロール`).toBe(expected)
    await ctx.dispose()
  }

  // 前回中断の残骸（本 spec の接頭辞のみ）を掃除
  const listRes = await apiCtx.get(`${BE_API}/shifts/schedules?teamId=${teamNumericId}`, {
    headers: authHeaders(adminToken),
  })
  for (const s of ((await listRes.json()).data ?? []) as Array<{ id: number; content: { title: string } }>) {
    if (s.content?.title?.startsWith('連打0901_')) {
      await apiCtx.delete(`${BE_API}/shifts/schedules/${s.id}`, { headers: authHeaders(adminToken) })
    }
  }

  // AC2 の前提: 一括作成の対象シフト表（枠は空）
  const start = addDaysIso(todayIsoJst(), 10)
  const res = await apiCtx.post(`${BE_API}/shifts/schedules?teamId=${teamNumericId}`, {
    headers: authHeaders(adminToken),
    data: { title: TITLE_BULK_SCHEDULE, startDate: start, endDate: addDaysIso(start, 2) },
  })
  expect(res.status(), `シフト表作成: ${await res.text()}`).toBeLessThan(300)
  bulkScheduleId = ((await res.json()).data as { id: number }).id
  console.log(`[FIXTURE] team=${teamNumericId} bulkSchedule=${bulkScheduleId}`)
})

test.afterAll(async () => {
  // 本 run の RUN_TAG が付いたものだけを削除（二重作成が起きていた場合の重複分も含めて自分が作ったもの）
  for (const s of await listSchedulesByTag()) {
    const d = await apiCtx.delete(`${BE_API}/shifts/schedules/${s.id}`, { headers: authHeaders(adminToken) })
    console.log(`[CLEANUP] DELETE schedule ${s.id} (${s.content.title}) -> ${d.status()}`)
  }
  for (const p of await listPositionsByTag()) {
    const d = await apiCtx.delete(`${BE_API}/shifts/positions/${p.id}`, { headers: authHeaders(adminToken) })
    console.log(`[CLEANUP] DELETE position ${p.id} (${p.name}) -> ${d.status()}`)
  }
  await apiCtx?.dispose()
})

/** 再描画の前に 2 回目の click が届く連打を再現する（同一タスク内で click を 2 回発火）。 */
async function rapidDoubleClick(locator: ReturnType<Page['locator']>): Promise<void> {
  await locator.evaluate((el) => {
    ;(el as HTMLButtonElement).click()
    ;(el as HTMLButtonElement).click()
  })
}

async function openDialogByClicking(page: Page, trigger: ReturnType<Page['locator']>): Promise<void> {
  await expect(async () => {
    await trigger.click()
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 3_000 })
  }).toPass({ timeout: 60_000 })
}

test('AC1 /shift の「新規作成」→「作成」を連打してもシフト表は1件だけ（画面と API の両方）', async ({ browser }) => {
  const page = await openAs(browser, TEAM_ADMIN_EMAIL)
  await page.goto('/shift')
  await waitForHydration(page)
  const createNew = page.getByRole('button', { name: '新規作成' })
  await expect(createNew, '権限ありには作成導線が出る').toBeVisible({ timeout: 30_000 })
  await openDialogByClicking(page, createNew)

  const dialog = page.getByRole('dialog')
  await dialog.locator('input[type="text"]').first().fill(TITLE_SCHEDULE)
  const start = addDaysIso(todayIsoJst(), 20)
  const dateInputs = dialog.locator('input[type="date"]')
  await dateInputs.nth(0).fill(start)
  await dateInputs.nth(1).fill(addDaysIso(start, 6))

  const posts: string[] = []
  page.on('request', (r) => {
    if (r.method() === 'POST' && /\/api\/v1\/shifts\/schedules(\?|$)/.test(r.url())) posts.push(r.url())
  })
  await rapidDoubleClick(dialog.getByRole('button', { name: '作成', exact: true }))
  await expect(page.getByText('シフトを作成しました').first()).toBeVisible({ timeout: 20_000 })
  await expect(dialog).toBeHidden({ timeout: 10_000 })
  await page.waitForLoadState('networkidle')

  const created = (await listSchedulesByTag()).filter((s) => s.content.title === TITLE_SCHEDULE)
  console.log(`[AC1] 作成 POST 回数=${posts.length}, API 一覧の件数=${created.length}`)
  expect(posts.length, '作成リクエストは1回だけ送られる').toBe(1)
  expect(created.length, 'API の一覧に1件だけ').toBe(1)

  // 画面の一覧件数（再読み込みして永続化後の表示を確認）
  await page.reload()
  await waitForHydration(page)
  await expect(page.getByText(TITLE_SCHEDULE).first()).toBeVisible({ timeout: 30_000 })
  await expect(page.getByText(TITLE_SCHEDULE), '画面の一覧にも1件だけ').toHaveCount(1)
  await page.screenshot({ path: 'test-results/0901-schedule-once.png', fullPage: true })
  await page.context().close()
})

test('AC2 /shift/{id}/edit の「一括作成」→「作成」を連打しても枠は1回分（3日=3枠）だけ', async ({ browser }) => {
  const page = await openAs(browser, TEAM_ADMIN_EMAIL)
  await page.goto(`/shift/${bulkScheduleId}/edit`)
  await waitForHydration(page)
  await expect(page.getByText(TITLE_BULK_SCHEDULE).first()).toBeVisible({ timeout: 30_000 })
  const bulk = page.getByRole('button', { name: '一括作成' })
  await expect(bulk).toBeVisible({ timeout: 20_000 })
  await openDialogByClicking(page, bulk)

  const dialog = page.getByRole('dialog')
  const start = addDaysIso(todayIsoJst(), 10)
  const dateInputs = dialog.locator('input[type="date"]')
  await dateInputs.nth(0).fill(start)
  await dateInputs.nth(1).fill(addDaysIso(start, 2))

  const posts: string[] = []
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().includes(`/shifts/schedules/${bulkScheduleId}/slots`)) posts.push(r.url())
  })
  await rapidDoubleClick(dialog.getByRole('button', { name: '作成', exact: true }))
  await expect(page.getByText('3 件のスロットを作成しました').first()).toBeVisible({ timeout: 20_000 })
  await page.waitForLoadState('networkidle')

  const res = await apiCtx.get(`${BE_API}/shifts/schedules/${bulkScheduleId}/slots`, {
    headers: authHeaders(adminToken),
  })
  const slots = (await res.json()).data as unknown[]
  console.log(`[AC2] 一括作成 POST 回数=${posts.length}, API の枠数=${slots.length}`)
  expect(posts.length, '一括作成リクエストは1回だけ').toBe(1)
  expect(slots.length, '枠は3日分=3件だけ').toBe(3)
  // 画面の枠数（スロット行）
  await page.reload()
  await waitForHydration(page)
  await expect(page.locator('button .pi-pencil')).toHaveCount(3, { timeout: 30_000 })
  await page.screenshot({ path: 'test-results/0901-bulk-once.png', fullPage: true })
  await page.context().close()
})

test('AC3 チームの「ポジション管理」で「保存」を連打してもポジションは1件だけ', async ({ browser }) => {
  const page = await openAs(browser, TEAM_ADMIN_EMAIL)
  await page.goto(`/teams/${TEAM_SLUG}/shifts`)
  await waitForHydration(page)
  const tab = page.getByRole('tab', { name: 'ポジション管理' })
  await expect(tab, '管理者にはポジション管理タブが出る').toBeVisible({ timeout: 30_000 })
  await tab.click()
  await openDialogByClicking(page, page.getByRole('button', { name: '追加' }))
  const dialog = page.getByRole('dialog')
  await dialog.getByPlaceholder('例: レジ担当').fill(POSITION_NAME)

  const posts: string[] = []
  page.on('request', (r) => {
    if (r.method() === 'POST' && /\/api\/v1\/shifts\/positions/.test(r.url())) posts.push(r.url())
  })
  await rapidDoubleClick(dialog.getByRole('button', { name: '保存' }))
  await expect(page.getByText(POSITION_NAME).first()).toBeVisible({ timeout: 20_000 })
  await page.waitForLoadState('networkidle')

  const found = await listPositionsByTag()
  console.log(`[AC3] 作成 POST 回数=${posts.length}, API のポジション件数=${found.length}`)
  expect(posts.length, '作成リクエストは1回だけ').toBe(1)
  expect(found.length, 'API に1件だけ').toBe(1)
  await expect(page.getByText(POSITION_NAME), '画面にも1件だけ').toHaveCount(1)
  await page.screenshot({ path: 'test-results/0901-position-once.png', fullPage: true })
  await page.context().close()
})

test('AC4-a 権限なし（MEMBER）: /shift に作成ボタンが出ない、編集画面に一括作成が出ない、ポジション追加の導線が出ない', async ({ browser }) => {
  const page = await openAs(browser, MEMBER_EMAIL)
  await page.goto('/shift')
  await waitForHydration(page)
  await expect(page.getByText(teamName).first()).toBeVisible({ timeout: 30_000 })
  await page.waitForLoadState('networkidle')
  await expect(page.getByRole('button', { name: '新規作成' }), 'MEMBER に作成ボタンが出ない').toHaveCount(0)

  await page.goto(`/shift/${bulkScheduleId}/edit`)
  await waitForHydration(page)
  await page.waitForLoadState('networkidle')
  console.log('[MEMBER /shift/id/edit (DRAFT)]', (await page.locator('body').innerText()).replace(/\n+/g, ' / ').slice(-200))
  await expect(page.getByRole('button', { name: '一括作成' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'スロットを作成' })).toHaveCount(0)

  await page.goto(`/teams/${TEAM_SLUG}/shifts`)
  await waitForHydration(page)
  await expect(page.getByRole('tab', { name: 'シフト表' })).toBeVisible({ timeout: 30_000 })
  // ポジション管理のパネル（追加ボタン）は管理者にしか描画されない。
  // タブの見出し自体は MEMBER にも出る（観測: 本 PR の対象外。証拠文書に記録）ので、押しても作成導線が出ないことを確認する。
  const positionTab = page.getByRole('tab', { name: 'ポジション管理' })
  console.log(`[MEMBER ポジション管理タブの見出し] ${await positionTab.count()} 件`)
  if ((await positionTab.count()) > 0) await positionTab.click()
  await page.waitForLoadState('networkidle')
  await expect(page.getByRole('button', { name: '追加' }), 'MEMBER にポジション追加ボタンが出ない').toHaveCount(0)
  await expect(page.getByText('ポジションはまだありません')).toHaveCount(0)
  await page.screenshot({ path: 'test-results/0901-member.png', fullPage: true })
  await page.context().close()
})

test('AC4-b 他チーム（非所属）: /shift に対象チームが出ず作成導線も無い、編集 URL 直打ちは見つからない', async ({ browser }) => {
  const page = await openAs(browser, OUTSIDER_EMAIL)
  await page.goto('/shift')
  await waitForHydration(page)
  await page.waitForLoadState('networkidle')
  await expect(page.getByText(teamName)).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新規作成' })).toHaveCount(0)

  const detail = page.waitForResponse(
    (r) => r.url().includes(`/api/v1/shifts/schedules/${bulkScheduleId}`) && r.request().method() === 'GET',
    { timeout: 30_000 },
  )
  await page.goto(`/shift/${bulkScheduleId}/edit`)
  await waitForHydration(page)
  const res = await detail
  await page.waitForLoadState('networkidle')
  console.log(`[OUTSIDER /shift/id/edit] BE=${res.status()}`)
  expect(res.status()).toBeGreaterThanOrEqual(403)
  await expect(page.getByRole('button', { name: '一括作成' })).toHaveCount(0)
  await page.context().close()
})
