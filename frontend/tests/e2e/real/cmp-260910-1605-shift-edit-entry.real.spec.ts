/**
 * 実機E2E: チームのシフト表一覧から枠編集画面（/shift/{id}/edit）へ到達できる（CMP-260910-1605）
 *
 * 受け入れ条件:
 *   AC1 権限あり（チーム ADMIN）: /teams/{slug}/shifts の各行（DRAFT / COLLECTING）に「枠を編集」が出て、
 *       押すと /shift/{id}/edit へ遷移し、枠の作成・編集の操作が使える。行クリック（希望ダイアログ）は開かない
 *   AC2 権限なし（MEMBER）: 一覧に「枠を編集」が出ない。/shift/{id}/edit 直打ちでも作成・編集の操作は出ず、
 *       BE も枠の作成を拒否する
 *   AC3 他チーム: 他チームの ADMIN・非所属ユーザーは対象チームの一覧に導線が無く、
 *       /shift/{id}/edit 直打ちでもシフト表も操作も出ない（BE も拒否）
 *
 * API の用途: ログイン・前提データ作成・拒否の裏取り（読み取り/拒否確認）・後始末のみ。
 * 導線のクリックと遷移・表示確認はすべて画面で行う。
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
/** 他チーム（fc-u-15）の ADMIN */
const OTHER_TEAM_ADMIN_EMAIL = 'e2e-dummy-6@test.mannschaft.local'
/** どのチームにも非所属 */
const OUTSIDER_EMAIL = 'e2e-outsider@test.mannschaft.local'

const TEAM_SLUG = 'fc-u-18'
const RUN_TAG = `R${Date.now().toString(36)}`
const TITLE_DRAFT = `枠編集導線1605_下書き_${RUN_TAG}`
const TITLE_COLLECTING = `枠編集導線1605_収集中_${RUN_TAG}`
const POSITION_NAME = `枠編集導線1605_${RUN_TAG}`

const LABEL_EDIT_ENTRY = '枠を編集'
const LABEL_CREATE_SLOT = 'スロットを作成'
const LABEL_BULK_CREATE = '一括作成'
const LABEL_REQUEST_DIALOG = 'シフト希望を提出'

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

let apiCtx: APIRequestContext
let adminToken: string
let teamNumericId: number
let positionId: number
let draftId: number
let collectingId: number
let slotDate: string

base.use({ storageState: { cookies: [], origins: [] } })
const test = base
test.describe.configure({ mode: 'serial' })
test.setTimeout(180_000)

test.beforeAll(async () => {
  apiCtx = await playwrightRequest.newContext()
  adminToken = await login(apiCtx, ADMIN_EMAIL)

  const teamRes = await apiCtx.get(`${BE_API}/teams/${TEAM_SLUG}`, { headers: authHeaders(adminToken) })
  expect(teamRes.status(), 'チーム取得').toBe(200)
  teamNumericId = ((await teamRes.json()).data as { numericId: number }).numericId

  // 役者の実効ロールを実測で裏取りする
  for (const [email, slug, expected] of [
    [TEAM_ADMIN_EMAIL, TEAM_SLUG, 'ADMIN'],
    [MEMBER_EMAIL, TEAM_SLUG, 'MEMBER'],
    [OTHER_TEAM_ADMIN_EMAIL, 'fc-u-15', 'ADMIN'],
  ] as const) {
    const ctx = await playwrightRequest.newContext()
    const token = await login(ctx, email)
    const res = await ctx.get(`${BE_API}/teams/${slug}/me/permissions`, { headers: authHeaders(token) })
    if (slug === TEAM_SLUG) {
      expect(res.status(), `${email} 権限取得`).toBe(200)
      expect(((await res.json()).data as { roleName: string }).roleName, `${email} の実効ロール`).toBe(expected)
    } else {
      // 他チームの ADMIN（対象チームには非所属）。対象チームのシフト表へは入れないことを各テストで確認する
      expect(res.status(), `${email} の ${slug} 権限取得`).toBe(200)
    }
    await ctx.dispose()
  }

  // 前回中断の残骸（本 spec の接頭辞のみ）を掃除
  const listRes = await apiCtx.get(`${BE_API}/shifts/schedules?teamId=${teamNumericId}`, {
    headers: authHeaders(adminToken),
  })
  for (const s of ((await listRes.json()).data ?? []) as Array<{ id: number; content: { title: string } }>) {
    if (s.content?.title?.startsWith('枠編集導線1605_')) {
      await apiCtx.delete(`${BE_API}/shifts/schedules/${s.id}`, { headers: authHeaders(adminToken) })
    }
  }

  const posRes = await apiCtx.post(`${BE_API}/shifts/positions?teamId=${teamNumericId}`, {
    headers: authHeaders(adminToken),
    data: { name: POSITION_NAME, displayOrder: 1 },
  })
  expect(posRes.status(), `ポジション作成: ${await posRes.text()}`).toBeLessThan(300)
  positionId = ((await posRes.json()).data as { id: number }).id

  const start = addDaysIso(todayIsoJst(), 5)
  slotDate = start
  async function createSchedule(title: string): Promise<number> {
    const res = await apiCtx.post(`${BE_API}/shifts/schedules?teamId=${teamNumericId}`, {
      headers: authHeaders(adminToken),
      data: { title, startDate: start, endDate: addDaysIso(start, 1) },
    })
    expect(res.status(), `シフト表作成(${title}): ${await res.text()}`).toBeLessThan(300)
    const id = ((await res.json()).data as { id: number }).id
    const slot = await apiCtx.post(`${BE_API}/shifts/schedules/${id}/slots`, {
      headers: authHeaders(adminToken),
      data: { slotDate: start, startTime: '09:00:00', endTime: '12:00:00', positionId, requiredCount: 2, note: null },
    })
    expect(slot.status(), `枠作成: ${await slot.text()}`).toBeLessThan(300)
    return id
  }
  draftId = await createSchedule(TITLE_DRAFT)
  collectingId = await createSchedule(TITLE_COLLECTING)
  const tr = await apiCtx.post(`${BE_API}/shifts/schedules/${collectingId}/transition?status=COLLECTING`, {
    headers: authHeaders(adminToken),
  })
  expect(tr.status(), `COLLECTING 遷移: ${await tr.text()}`).toBeLessThan(300)
  console.log(`[FIXTURE] team=${teamNumericId} draft=${draftId} collecting=${collectingId}`)
})

test.afterAll(async () => {
  for (const id of [draftId, collectingId]) {
    if (!id) continue
    const d = await apiCtx.delete(`${BE_API}/shifts/schedules/${id}`, { headers: authHeaders(adminToken) })
    console.log(`[CLEANUP] DELETE schedule ${id} -> ${d.status()}`)
  }
  if (positionId) {
    const d = await apiCtx.delete(`${BE_API}/shifts/positions/${positionId}`, { headers: authHeaders(adminToken) })
    console.log(`[CLEANUP] DELETE position ${positionId} -> ${d.status()}`)
  }
  await apiCtx?.dispose()
})

async function gotoTeamShifts(page: Page, expectTitle: string | null): Promise<void> {
  await page.goto(`/teams/${TEAM_SLUG}/shifts`)
  await waitForHydration(page)
  if (expectTitle) await expect(page.getByText(expectTitle)).toBeVisible({ timeout: 30_000 })
}

async function bodyText(page: Page): Promise<string> {
  return (await page.locator('body').innerText()).replace(/\n+/g, ' / ')
}

test('AC1-a 権限あり（ADMIN）: 一覧の各行に「枠を編集」が出て、押すと /shift/{id}/edit で枠の操作が使える', async ({ browser }) => {
  const page = await openAs(browser, TEAM_ADMIN_EMAIL)
  await gotoTeamShifts(page, TITLE_COLLECTING)
  await expect(page.getByText(TITLE_DRAFT)).toBeVisible()

  // 収集中の行 → 押下 → 編集画面。行クリックの希望ダイアログは開かない
  await page.evaluate((label) => {
    const w = window as unknown as { __dialogSeen?: boolean }
    w.__dialogSeen = false
    const check = () => {
      if (document.body.innerText.includes(label)) w.__dialogSeen = true
    }
    new MutationObserver(check).observe(document.body, { subtree: true, childList: true })
  }, LABEL_REQUEST_DIALOG)

  const collectingEntry = page.locator(`[data-testid="shift-edit-${collectingId}"]`)
  await expect(collectingEntry, 'COLLECTING 行に導線').toBeVisible()
  await expect(collectingEntry).toContainText(LABEL_EDIT_ENTRY)
  await expect(async () => {
    if (!page.url().includes('/edit')) await collectingEntry.click()
    await expect(page).toHaveURL(new RegExp(`/shift/${collectingId}/edit`), { timeout: 5_000 })
  }).toPass({ timeout: 60_000 })
  expect(
    await page.evaluate(() => (window as unknown as { __dialogSeen?: boolean }).__dialogSeen),
    '行クリックの希望ダイアログが開かない',
  ).toBe(false)

  await expect(page.getByText(TITLE_COLLECTING).first()).toBeVisible({ timeout: 30_000 })
  await expect(page.getByRole('button', { name: LABEL_CREATE_SLOT })).toBeVisible({ timeout: 20_000 })
  await expect(page.getByRole('button', { name: LABEL_BULK_CREATE })).toBeVisible()
  await expect(page.locator('button .pi-pencil').first(), '既存枠の編集ボタン').toBeVisible()
  await expect(page.getByText(POSITION_NAME).first(), '前提の枠が表示される').toBeVisible()
  await page.screenshot({ path: 'test-results/1605-admin-edit.png', fullPage: true })

  // 「スロットを作成」で作成ダイアログが開く（枠編集の入口として機能する）
  await page.getByRole('button', { name: LABEL_CREATE_SLOT }).click()
  await expect(page.getByText('日付', { exact: true }).first()).toBeVisible()
  await page.context().close()
})

test('AC1-b 権限あり（ADMIN）: 下書き行からも到達できる', async ({ browser }) => {
  const page = await openAs(browser, TEAM_ADMIN_EMAIL)
  await gotoTeamShifts(page, TITLE_DRAFT)
  const draftEntry = page.locator(`[data-testid="shift-edit-${draftId}"]`)
  await expect(draftEntry, 'DRAFT 行に導線').toBeVisible()
  await expect(async () => {
    if (!page.url().includes('/edit')) await draftEntry.click()
    await expect(page).toHaveURL(new RegExp(`/shift/${draftId}/edit`), { timeout: 5_000 })
  }).toPass({ timeout: 60_000 })
  await expect(page.getByText(TITLE_DRAFT).first()).toBeVisible({ timeout: 30_000 })
  await expect(page.getByRole('button', { name: LABEL_CREATE_SLOT })).toBeVisible({ timeout: 20_000 })
  await page.context().close()
})

test('AC2-a 権限なし（MEMBER）: 一覧に「枠を編集」が出ない', async ({ browser }) => {
  const page = await openAs(browser, MEMBER_EMAIL)
  await gotoTeamShifts(page, TITLE_COLLECTING)
  await page.waitForLoadState('networkidle')
  await expect(page.locator('[data-testid^="shift-edit-"]'), 'MEMBER に導線が出ない').toHaveCount(0)
  await expect(page.getByRole('button', { name: LABEL_EDIT_ENTRY })).toHaveCount(0)
  await page.screenshot({ path: 'test-results/1605-member-list.png', fullPage: true })
  await page.context().close()
})

test('AC2-b 権限なし（MEMBER）: /shift/{id}/edit 直打ちでも作成・編集の操作が出ず、BE も枠作成を拒否する', async ({ browser }) => {
  const page = await openAs(browser, MEMBER_EMAIL)
  await page.goto(`/shift/${collectingId}/edit`)
  await waitForHydration(page)
  await page.waitForLoadState('networkidle')
  await expect(page.getByText(TITLE_COLLECTING).first()).toBeVisible({ timeout: 30_000 })
  await expect(page.getByRole('button', { name: LABEL_CREATE_SLOT }), '作成ボタンが出ない').toHaveCount(0)
  await expect(page.getByRole('button', { name: LABEL_BULK_CREATE }), '一括作成が出ない').toHaveCount(0)
  await expect(page.locator('button .pi-pencil'), '枠の編集ボタンが出ない').toHaveCount(0)
  await expect(page.locator('button .pi-trash'), '枠の削除ボタンが出ない').toHaveCount(0)
  console.log('[MEMBER /shift/id/edit]', (await bodyText(page)).slice(0, 400))
  await page.screenshot({ path: 'test-results/1605-member-direct.png', fullPage: true })
  await page.context().close()

  const ctx = await playwrightRequest.newContext()
  const token = await login(ctx, MEMBER_EMAIL)
  const res = await ctx.post(`${BE_API}/shifts/schedules/${collectingId}/slots`, {
    headers: authHeaders(token),
    data: { slotDate, startTime: '13:00:00', endTime: '14:00:00', positionId, requiredCount: 1, note: null },
  })
  console.log(`[MEMBER 枠作成] ${res.status()} ${(await res.text()).slice(0, 160)}`)
  expect(res.status(), 'MEMBER の枠作成は拒否').toBeGreaterThanOrEqual(400)
  await ctx.dispose()
})

test('AC3-a 他チーム ADMIN: 対象チームの一覧に導線が無く、/shift/{id}/edit 直打ちでもシフト表・操作が出ない', async ({ browser }) => {
  const page = await openAs(browser, OTHER_TEAM_ADMIN_EMAIL)
  await page.goto(`/teams/${TEAM_SLUG}/shifts`)
  await waitForHydration(page)
  // 読み込み中表示（l o a d i n g）が消えて画面が確定するまで待ってから「出ない」を確認する
  await expect
    .poll(async () => (await page.locator('body').innerText()).replace(/\s+/g, ''), { timeout: 45_000 })
    .not.toMatch(/loading/)
  await expect(page.getByText(TITLE_COLLECTING)).toHaveCount(0)
  await expect(page.locator('[data-testid^="shift-edit-"]')).toHaveCount(0)
  console.log('[OTHER-TEAM-ADMIN /teams/fc-u-18/shifts]', (await bodyText(page)).slice(0, 300))

  const detail = page.waitForResponse(
    (r) => r.url().includes(`/api/v1/shifts/schedules/${collectingId}`) && r.request().method() === 'GET',
    { timeout: 30_000 },
  )
  await page.goto(`/shift/${collectingId}/edit`)
  await waitForHydration(page)
  const res = await detail
  await page.waitForLoadState('networkidle')
  console.log(`[OTHER-TEAM-ADMIN /shift/id/edit] BE=${res.status()}`, (await bodyText(page)).slice(0, 300))
  expect(res.status()).toBeGreaterThanOrEqual(403)
  await expect(page.getByText(TITLE_COLLECTING)).toHaveCount(0)
  await expect(page.getByRole('button', { name: LABEL_CREATE_SLOT })).toHaveCount(0)
  await expect(page.getByRole('button', { name: LABEL_BULK_CREATE })).toHaveCount(0)
  await page.screenshot({ path: 'test-results/1605-otherteam-direct.png', fullPage: true })
  await page.context().close()

  const ctx = await playwrightRequest.newContext()
  const token = await login(ctx, OTHER_TEAM_ADMIN_EMAIL)
  const post = await ctx.post(`${BE_API}/shifts/schedules/${collectingId}/slots`, {
    headers: authHeaders(token),
    data: { slotDate, startTime: '13:00:00', endTime: '14:00:00', requiredCount: 1, note: null },
  })
  console.log(`[OTHER-TEAM-ADMIN 枠作成] ${post.status()}`)
  expect(post.status(), '他チーム ADMIN の枠作成は拒否').toBeGreaterThanOrEqual(400)
  await ctx.dispose()
})

test('AC3-b 非所属ユーザー: /shift/{id}/edit 直打ちでシフト表も操作も出ず、BE も枠作成を拒否する', async ({ browser }) => {
  const page = await openAs(browser, OUTSIDER_EMAIL)
  const detail = page.waitForResponse(
    (r) => r.url().includes(`/api/v1/shifts/schedules/${collectingId}`) && r.request().method() === 'GET',
    { timeout: 30_000 },
  )
  await page.goto(`/shift/${collectingId}/edit`)
  await waitForHydration(page)
  const res = await detail
  await page.waitForLoadState('networkidle')
  console.log(`[OUTSIDER /shift/id/edit] BE=${res.status()}`, (await bodyText(page)).slice(0, 300))
  expect(res.status()).toBeGreaterThanOrEqual(403)
  await expect(page.getByText(TITLE_COLLECTING)).toHaveCount(0)
  await expect(page.getByRole('button', { name: LABEL_CREATE_SLOT })).toHaveCount(0)
  await expect(page.getByRole('button', { name: LABEL_BULK_CREATE })).toHaveCount(0)
  await page.screenshot({ path: 'test-results/1605-outsider-direct.png', fullPage: true })
  await page.context().close()

  const ctx = await playwrightRequest.newContext()
  const token = await login(ctx, OUTSIDER_EMAIL)
  const post = await ctx.post(`${BE_API}/shifts/schedules/${collectingId}/slots`, {
    headers: authHeaders(token),
    data: { slotDate, startTime: '13:00:00', endTime: '14:00:00', requiredCount: 1, note: null },
  })
  console.log(`[OUTSIDER 枠作成] ${post.status()}`)
  expect(post.status(), '非所属の枠作成は拒否').toBeGreaterThanOrEqual(400)
  await ctx.dispose()
})
