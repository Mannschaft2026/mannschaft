/**
 * 実機E2E: 行動メモ（F02.5）→ 公開した個人投稿への導線（CMP-261123-1201 / 1202）。
 *
 * 不具合: 行動メモ画面から、公開した個人投稿（timeline_post_id）へ辿る画面上のリンクが無かった。
 * 修正: index 画面の選択日メモ一覧に「公開した投稿を見る」リンクを置き、選択日は URL の ?date= に同期する。
 *
 * 対応 AC: AC-1 公開直後のリンク / AC-2 過去日選択でもリンク / AC-4 公開後追加メモ混在・再公開で新ID /
 *          AC-5 詳細を開いて戻ると同じ日 / AC-6 他人の直打ちは不在IDと同表示・API 404 /
 *          AC-7 投稿削除後は見つかりません表示・再公開で回復 / AC-9 375px で横はみ出しなし・タップ44px
 *
 * API の使用範囲（実機E2E規約: 対象操作は必ず画面）:
 *   - ユーザー登録・有効化・過去日メモの作成と公開（画面から過去日を選べないため前提データ作成）・投稿削除（AC-7 の前提作成）
 *   - 他人の直打ちと不在IDの API 比較（AC-6 の補強。判定は画面）
 * 実行: 検証専用 BE 8081 / FE 3001。playwright-actionmemo-split.config.ts の testMatch に含まれる。
 * 有効化だけは DB 直更新（E2E_DB_CONTAINER / E2E_DB_SCHEMA）。
 */
import { execFileSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import {
  test,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8081'
const BE_API = `${API_BASE_URL}/api/v1`
const DB_SCHEMA = process.env.E2E_DB_SCHEMA ?? 'mannschaft'
const DB_CONTAINER = process.env.E2E_DB_CONTAINER ?? 'am-split-verify-mysql'
const SHOT_DIR = process.env.E2E_SHOT_DIR ?? 'test-results/actionmemo-post-link'
const PASSWORD = 'Passw0rd!2026'
const stamp = Date.now()
const userSuffix = process.env.E2E_USER_SUFFIX ?? String(stamp)
const users = {
  A: { email: `e2e-amlink-a-${userSuffix}@test.mannschaft.local`, nick: `alA${userSuffix.slice(-5)}` },
  B: { email: `e2e-amlink-b-${userSuffix}@test.mannschaft.local`, nick: `alB${userSuffix.slice(-5)}` },
}

let tokenA = ''
let ctxApi: APIRequestContext

function headers(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

function activateAll(): void {
  const nicks = Object.values(users).map((u) => `'${u.nick}'`).join(',')
  execFileSync('wsl.exe', [
    '-e', 'docker', 'exec', DB_CONTAINER, 'mysql', '-uroot', '-proot', DB_SCHEMA, '-e',
    `UPDATE users SET status='ACTIVE' WHERE display_name IN (${nicks}) AND status='PENDING_VERIFICATION'`,
  ])
}

async function register(u: { email: string; nick: string }): Promise<void> {
  // 既存の検証ユーザー（同じ検証専用DBで作成済み）ならログインできるので再登録しない（登録APIのレート制限対策）
  const probe = await ctxApi.post(`${BE_API}/auth/login`, {
    headers: { 'Content-Type': 'application/json' },
    data: { email: u.email, password: PASSWORD },
  })
  if (probe.ok()) return
  const res = await ctxApi.post(`${BE_API}/auth/register`, {
    headers: { 'Content-Type': 'application/json' },
    data: {
      email: u.email,
      password: PASSWORD,
      lastName: '検証',
      firstName: u.nick,
      nickname: u.nick,
      birth_date: '1990-01-01',
      postalCode: '100-0001',
      privacyPolicyAccepted: true,
      privacyPolicyVersion: '1.0',
    },
  })
  if (!res.ok()) throw new Error(`登録失敗(${u.email}): ${res.status()} ${await res.text()}`)
}

async function apiLogin(email: string): Promise<string> {
  const res = await ctxApi.post(`${BE_API}/auth/login`, {
    headers: { 'Content-Type': 'application/json' },
    data: { email, password: PASSWORD },
  })
  if (!res.ok()) throw new Error(`ログイン失敗(${email}): ${res.status()} ${await res.text()}`)
  return (await res.json()).data.accessToken as string
}

async function userContext(browser: Browser, email: string): Promise<{ ctx: BrowserContext; page: Page }> {
  const ctx = await browser.newContext({ storageState: undefined })
  const page = await ctx.newPage()
  await loginViaApi(page, { email, password: PASSWORD })
  return { ctx, page }
}


const MARK_TODAY = `導線検証メモ今日_${stamp}`
const MARK_ADDED = `導線検証メモ追加_${stamp}`
const MARK_PAST = `導線検証メモ過去_${stamp}`

const LINK = '[data-testid="action-memo-published-post-link"]'

function addDays(days: number): string {
  const d = new Date(Date.now() + days * 86_400_000)
  return d.toLocaleDateString('sv-SE', { timeZone: 'Asia/Tokyo' })
}
const TODAY = addDays(0)
const PAST_DATE = addDays(-3)

async function shot(page: Page, name: string): Promise<void> {
  mkdirSync(SHOT_DIR, { recursive: true })
  await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: true })
}

async function createMemoViaUi(page: Page, text: string): Promise<void> {
  await page.goto('/action-memo')
  await waitForHydration(page)
  const textarea = page.locator('[data-testid="action-memo-input-textarea"]')
  await expect(textarea).toBeVisible({ timeout: 30_000 })
  await textarea.pressSequentially(text)
  const created = page.waitForResponse(
    (r) => r.url().includes('/api/v1/action-memos') && r.request().method() === 'POST' && r.ok(),
  )
  await page.locator('[data-testid="action-memo-input-submit"]').click()
  await created
  await expect(page.getByText(text).first()).toBeVisible()
}

/** 画面（締め画面）から1日分を公開し、公開応答の個人投稿 ID を返す。公開後は /action-memo へ戻る。 */
async function publishDailyViaUi(page: Page): Promise<number> {
  await page.goto('/action-memo/closing')
  await waitForHydration(page)
  const btn = page.locator('[data-testid="action-memo-closing-publish"]')
  await expect(btn).toBeEnabled({ timeout: 30_000 })
  const done = page.waitForResponse(
    (r) => r.url().includes('/action-memos/publish-daily') && !r.url().includes('to-team') && r.request().method() === 'POST',
  )
  await btn.click()
  const res = await done
  expect(res.ok()).toBeTruthy()
  const data = ((await res.json()) as { data: { timelinePostId?: number; timeline_post_id?: number } }).data
  const postId = (data.timelinePostId ?? data.timeline_post_id) as number
  expect(postId).toBeGreaterThan(0)
  await page.waitForURL('**/action-memo', { timeout: 30_000 })
  await waitForHydration(page)
  return postId
}

async function linkedPostId(page: Page): Promise<number> {
  const href = await page.locator(LINK).getAttribute('href')
  const m = /\/timeline\/(\d+)/.exec(href ?? '')
  expect(m, `リンク先が /timeline/{id} であること: ${href}`).toBeTruthy()
  return Number(m![1])
}

let personalPostId = 0
let republishedPostId = 0
let pastPostId = 0

test.describe.configure({ mode: 'serial' })

test.describe('行動メモ → 公開した個人投稿への導線', () => {
  test.beforeAll(async () => {
    ctxApi = await playwrightRequest.newContext()
    await register(users.A)
    await register(users.B)
    activateAll()
    tokenA = await apiLogin(users.A.email)
  })

  test.afterAll(async () => {
    await ctxApi?.dispose()
  })

  test('S1 AC-1/5: 公開直後に戻った画面にリンクが出て、開くと本文が見え、戻ると行動メモの同じ日に戻る', async ({ browser }) => {
    const { ctx, page } = await userContext(browser, users.A.email)
    try {
      await createMemoViaUi(page, MARK_TODAY)
      // 公開前はリンクが無い（AC-3 の実画面確認）
      await expect(page.locator(LINK)).toHaveCount(0)
      personalPostId = await publishDailyViaUi(page)

      const link = page.locator(LINK)
      await expect(link).toBeVisible({ timeout: 30_000 })
      await expect(link).toHaveAttribute('href', `/timeline/${personalPostId}`)
      await expect(page.locator('[data-testid="action-memo-published-post-note"]')).toBeVisible()
      await shot(page, 's1-1-link-after-publish')

      await link.click()
      await page.waitForURL(`**/timeline/${personalPostId}`, { timeout: 30_000 })
      await waitForHydration(page)
      await expect(page.getByText(MARK_TODAY).first()).toBeVisible({ timeout: 30_000 })
      await shot(page, 's1-2-detail')

      await page.getByRole('button', { name: /戻る/ }).first().click()
      await page.waitForURL('**/action-memo**', { timeout: 30_000 })
      await waitForHydration(page)
      expect(new URL(page.url()).searchParams.get('date') ?? TODAY).toBe(TODAY)
      await expect(page.locator(LINK)).toBeVisible({ timeout: 30_000 })
      await shot(page, 's1-3-back-to-action-memo')
    } finally {
      await ctx.close()
    }
  })

  test('S2 AC-2: 画面を開き直す（再訪）してもリンクが出る', async ({ browser }) => {
    const { ctx, page } = await userContext(browser, users.A.email)
    try {
      await page.goto('/action-memo')
      await waitForHydration(page)
      await expect(page.locator(LINK)).toHaveAttribute('href', `/timeline/${personalPostId}`, { timeout: 30_000 })
    } finally {
      await ctx.close()
    }
  })

  test('S3 AC-2/5: 過去日（?date=）の公開済みメモにもリンクが出て、開いて戻ると同じ過去日に戻る', async ({ browser }) => {
    // 前提（画面から過去日メモは作れないため API）: 3 日前のメモを作って 1 日分を公開する
    const created = await ctxApi.post(`${BE_API}/action-memos`, {
      headers: headers(tokenA),
      data: { content: MARK_PAST, memo_date: PAST_DATE },
    })
    expect(created.ok(), await created.text()).toBeTruthy()
    const pub = await ctxApi.post(`${BE_API}/action-memos/publish-daily`, {
      headers: headers(tokenA),
      data: { memo_date: PAST_DATE },
    })
    expect(pub.ok(), await pub.text()).toBeTruthy()
    const pubData = ((await pub.json()) as { data: { timelinePostId?: number; timeline_post_id?: number } }).data
    pastPostId = (pubData.timelinePostId ?? pubData.timeline_post_id) as number

    const { ctx, page } = await userContext(browser, users.A.email)
    try {
      await page.goto(`/action-memo?date=${PAST_DATE}`)
      await waitForHydration(page)
      await expect(page.getByText(MARK_PAST).first()).toBeVisible({ timeout: 30_000 })
      await expect(page.locator(LINK)).toHaveAttribute('href', `/timeline/${pastPostId}`)
      await shot(page, 's3-1-past-date-link')

      await page.locator(LINK).click()
      await page.waitForURL(`**/timeline/${pastPostId}`, { timeout: 30_000 })
      await waitForHydration(page)
      await expect(page.getByText(MARK_PAST).first()).toBeVisible({ timeout: 30_000 })

      await page.getByRole('button', { name: /戻る/ }).first().click()
      await page.waitForURL('**/action-memo**', { timeout: 30_000 })
      await waitForHydration(page)
      expect(new URL(page.url()).searchParams.get('date')).toBe(PAST_DATE)
      await expect(page.getByText(MARK_PAST).first()).toBeVisible({ timeout: 30_000 })
      await expect(page.locator(LINK)).toBeVisible()
      await shot(page, 's3-2-back-same-past-date')
    } finally {
      await ctx.close()
    }
  })

  test('S4 AC-4: 公開後にメモを足してもリンクは既存の投稿のまま、再公開すると新しい投稿へ変わる', async ({ browser }) => {
    const { ctx, page } = await userContext(browser, users.A.email)
    try {
      await createMemoViaUi(page, MARK_ADDED)
      await expect(page.locator(LINK)).toHaveAttribute('href', `/timeline/${personalPostId}`, { timeout: 30_000 })
      await page.reload()
      await waitForHydration(page)
      await expect(page.getByText(MARK_ADDED).first()).toBeVisible({ timeout: 30_000 })
      await expect(page.locator(LINK)).toHaveAttribute('href', `/timeline/${personalPostId}`)

      republishedPostId = await publishDailyViaUi(page)
      expect(republishedPostId).not.toBe(personalPostId)
      await expect(page.locator(LINK)).toHaveAttribute('href', `/timeline/${republishedPostId}`, { timeout: 30_000 })
      expect(await linkedPostId(page)).toBe(republishedPostId)
      await shot(page, 's4-republished-link')
    } finally {
      await ctx.close()
    }
  })

  test('S5 AC-6: 他人（B）が直打ちしても、不在 ID と同じ表示・同じ API 結果で、投稿の実在が分からない', async ({ browser }) => {
    const tokenB = await apiLogin(users.B.email)
    const real = await ctxApi.get(`${BE_API}/timeline/posts/${republishedPostId}`, { headers: headers(tokenB) })
    const ghost = await ctxApi.get(`${BE_API}/timeline/posts/2147483000`, { headers: headers(tokenB) })
    expect(real.status()).toBe(404)
    expect(ghost.status()).toBe(404)
    expect(((await real.json()) as { error?: { code?: string } }).error?.code).toBe(
      ((await ghost.json()) as { error?: { code?: string } }).error?.code,
    )

    const { ctx, page } = await userContext(browser, users.B.email)
    try {
      await page.goto(`/timeline/${republishedPostId}`)
      await waitForHydration(page)
      await expect(page.locator('[data-testid="timeline-post-not-found"]')).toBeVisible({ timeout: 30_000 })
      const realText = await page.locator('[data-testid="timeline-post-not-found"]').innerText()
      await expect(page.getByText(MARK_TODAY)).toHaveCount(0)
      await shot(page, 's5-1-other-user-real-id')

      await page.goto('/timeline/2147483000')
      await waitForHydration(page)
      await expect(page.locator('[data-testid="timeline-post-not-found"]')).toBeVisible({ timeout: 30_000 })
      expect(await page.locator('[data-testid="timeline-post-not-found"]').innerText()).toBe(realText)

      // B 自身の行動メモ画面には A の投稿へのリンクが出ない
      await page.goto('/action-memo')
      await waitForHydration(page)
      await expect(page.locator(LINK)).toHaveCount(0)
    } finally {
      await ctx.close()
    }
  })

  test('S6 AC-7: 公開した投稿が削除されても詳細は見つかりません表示で壊れず、再公開で回復する', async ({ browser }) => {
    // 前提（削除 UI の有無に依存しないため API）: 個人投稿を本人が削除する
    const del = await ctxApi.delete(`${BE_API}/timeline/posts/${republishedPostId}`, { headers: headers(tokenA) })
    expect(del.ok(), await del.text()).toBeTruthy()

    const { ctx, page } = await userContext(browser, users.A.email)
    try {
      await page.goto('/action-memo')
      await waitForHydration(page)
      // メモ側の ID は残るためリンク自体は出る。開くと壊れずに「見つかりません」になる
      await expect(page.locator(LINK)).toBeVisible({ timeout: 30_000 })
      await page.locator(LINK).click()
      await expect(page.locator('[data-testid="timeline-post-not-found"]')).toBeVisible({ timeout: 30_000 })
      await shot(page, 's6-1-deleted-post-not-found')

      // 再公開で回復する
      const recovered = await publishDailyViaUi(page)
      expect(recovered).not.toBe(republishedPostId)
      await expect(page.locator(LINK)).toHaveAttribute('href', `/timeline/${recovered}`, { timeout: 30_000 })
      await page.locator(LINK).click()
      await page.waitForURL(`**/timeline/${recovered}`, { timeout: 30_000 })
      await waitForHydration(page)
      await expect(page.getByText(MARK_TODAY).first()).toBeVisible({ timeout: 30_000 })
      await shot(page, 's6-2-recovered')
    } finally {
      await ctx.close()
    }
  })

  test('S7 AC-9: 375px 幅でリンクが画面内に収まり、横はみ出しが無く、タップ領域が 44px 以上', async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined, viewport: { width: 375, height: 800 } })
    const page = await ctx.newPage()
    try {
      await loginViaApi(page, { email: users.A.email, password: PASSWORD })
      await page.goto('/action-memo')
      await waitForHydration(page)
      const link = page.locator(LINK)
      await expect(link).toBeVisible({ timeout: 30_000 })
      const box = await link.boundingBox()
      expect(box).toBeTruthy()
      expect(box!.height).toBeGreaterThanOrEqual(44)
      expect(box!.x).toBeGreaterThanOrEqual(0)
      expect(box!.x + box!.width).toBeLessThanOrEqual(375)
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
      expect(overflow, '横スクロールが発生していない').toBeLessThanOrEqual(0)
      await shot(page, 's7-375px')
    } finally {
      await ctx.close()
    }
  })
})
