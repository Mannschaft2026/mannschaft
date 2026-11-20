/**
 * 実機E2E: 行動メモ（F02.5）チーム投稿と個人1日分投稿の共存（列分離 V263）。
 *
 * 不具合: チーム投稿後に publishDaily（個人1日分）を行うと、チームのタイムライン投稿が論理削除されていた。
 * 修正: timeline_post_id＝個人投稿 / team_timeline_post_id＝チーム投稿に列を分け、
 *       publishDaily は自分の PERSONAL 投稿だけを差し替える。
 *
 * API の使用範囲（実機E2E規約: 対象操作は必ず画面）:
 *   - ユーザー登録・有効化・チーム作成・招待参加・行動メモ設定（既定カテゴリ=WORK／既定チーム）= 前提データ作成
 *   - 投稿ID／メモID取得（URL直打ち用）・他人メモ参照の IDOR 補助確認 = 画面確認の補強（結果判定は画面）
 *
 * 有効化だけは DB 直更新が必要（メール認証トークンが SHA-256 ハッシュ保存で復元不可）。
 * 環境変数 E2E_DB_CONTAINER / E2E_DB_SCHEMA（検証専用コンテナ）に対し WSL の docker exec 経由で実行する。
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
const SHOT_DIR = process.env.E2E_SHOT_DIR ?? 'test-results/actionmemo-split'
const PASSWORD = 'Passw0rd!2026'
const ROLE_ID_MEMBER = 4

const stamp = Date.now()
const userSuffix = process.env.E2E_USER_SUFFIX ?? String(stamp)
const users = {
  A: { email: `e2e-am-a-${userSuffix}@test.mannschaft.local`, nick: `amA${userSuffix.slice(-5)}` },
  B: { email: `e2e-am-b-${userSuffix}@test.mannschaft.local`, nick: `amB${userSuffix.slice(-5)}` },
  C: { email: `e2e-am-c-${userSuffix}@test.mannschaft.local`, nick: `amC${userSuffix.slice(-5)}` },
}
const MARK1 = `分離検証メモ1_${stamp}`
const MARK2 = `分離検証メモ2_${stamp}`

let teamSlug = ''
let teamId = 0
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

async function shot(page: Page, name: string): Promise<void> {
  mkdirSync(SHOT_DIR, { recursive: true })
  await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: true })
}

async function bodyText(page: Page): Promise<string> {
  return (await page.locator('body').innerText()).slice(0, 300).replace(/\n/g, ' / ')
}

/** 画面から行動メモを1件作る（既定カテゴリ=WORK）。 */
async function createMemoViaUi(page: Page, text: string): Promise<void> {
  console.log('STEP createMemo', text)
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

/** チーム投稿のIDを検証専用DBから引く（URL直打ち用の前提取得。判定は画面）。 */
function teamPostIdOf(marker: string): number | undefined {
  const out = execFileSync('wsl.exe', [
    '-e', 'docker', 'exec', DB_CONTAINER, 'mysql', '-uroot', '-proot', DB_SCHEMA, '-N', '-e',
    `SELECT id FROM timeline_posts WHERE scope_type='TEAM' AND scope_id=${teamId} AND content LIKE '%${marker.replace(/[^0-9_A-Za-z]/g, '')}%' AND deleted_at IS NULL ORDER BY id DESC LIMIT 1`,
  ]).toString().trim()
  return out ? Number(out) : undefined
}

/** 画面（締め画面）から1日分を個人タイムラインへ投稿する。 */
async function publishDailyViaUi(page: Page): Promise<number> {
  console.log('STEP publishDaily')
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
  // BE の命名は snake_case / camelCase のいずれでも受けられるよう両対応で読む
  const body = ((await res.json()) as { data: { timelinePostId?: number; timeline_post_id?: number } }).data
  const postId = (body.timelinePostId ?? body.timeline_post_id) as number
  expect(postId).toBeGreaterThan(0)
  await page.waitForURL('**/action-memo', { timeout: 30_000 })
  return postId
}

/** 検証用 DB の timeline_posts 状態を記録する（判定は画面。DB は補強の証跡）。 */
function dumpPosts(label: string): void {
  const out = execFileSync('wsl.exe', [
    '-e', 'docker', 'exec', DB_CONTAINER, 'mysql', '-uroot', '-proot', DB_SCHEMA, '-N', '-e',
    `SELECT id, scope_type, scope_id, IF(deleted_at IS NULL,'alive','DELETED') FROM timeline_posts ORDER BY id`,
  ]).toString().split('\n').join(' | ')
  const memos = execFileSync('wsl.exe', [
    '-e', 'docker', 'exec', DB_CONTAINER, 'mysql', '-uroot', '-proot', DB_SCHEMA, '-N', '-e',
    `SELECT id, timeline_post_id, team_timeline_post_id, posted_team_id FROM action_memos ORDER BY id`,
  ]).toString().split('\n').join(' | ')
  console.log(`DB[${label}] posts(id,scope,scopeId,state)= ${out} ;; memos(id,personal,team,postedTeam)= ${memos}`)
}

/** 画面（締め画面）から当日の WORK メモをチームへ投稿する。 */
async function publishToTeamViaUi(page: Page): Promise<void> {
  console.log('STEP publishToTeam')
  await page.goto('/action-memo/closing')
  await waitForHydration(page)
  const btn = page.locator('[data-testid="action-memo-closing-publish-to-team"]')
  await expect(btn).toBeEnabled({ timeout: 30_000 })
  const done = page.waitForResponse(
    (r) => r.url().includes('/action-memos/publish-daily-to-team') && r.request().method() === 'POST',
  )
  await btn.click()
  const res = await done
  expect(res.ok()).toBeTruthy()
}

async function openTeamTimeline(page: Page): Promise<void> {
  await page.goto(`/teams/${teamSlug}/timeline`)
  await waitForHydration(page)
}

test.describe.configure({ mode: 'serial' })

test.describe('行動メモ チーム投稿と個人1日分投稿の共存（列分離）', () => {
  test.beforeAll(async () => {
    ctxApi = await playwrightRequest.newContext()
    await register(users.A)
    await register(users.B)
    await register(users.C)
    activateAll()
    tokenA = await apiLogin(users.A.email)
    const tRes = await ctxApi.post(`${BE_API}/teams`, {
      headers: headers(tokenA),
      data: { name: `AmSplit_${stamp}` },
    })
    if (!tRes.ok()) throw new Error(`チーム作成失敗: ${tRes.status()} ${await tRes.text()}`)
    const tBody = await tRes.text()
    console.log(`チーム作成レスポンス: ${tBody.slice(0, 300)}`)
    const team = (JSON.parse(tBody) as { data: { numericId: number; slug: string } }).data
    teamSlug = team.slug
    teamId = team.numericId
    const invRes = await ctxApi.post(`${BE_API}/teams/${teamSlug}/invite-tokens`, {
      headers: headers(tokenA),
      data: { roleId: ROLE_ID_MEMBER },
    })
    if (!invRes.ok()) throw new Error(`招待トークン失敗: ${invRes.status()} ${await invRes.text()}`)
    const inviteToken = ((await invRes.json()).data as { token: string }).token
    const tokenB = await apiLogin(users.B.email)
    const joinRes = await ctxApi.post(`${BE_API}/invite/${inviteToken}/join`, { headers: headers(tokenB) })
    if (!joinRes.ok()) throw new Error(`参加失敗: ${joinRes.status()} ${await joinRes.text()}`)
    const setRes = await ctxApi.patch(`${BE_API}/action-memo-settings`, {
      headers: headers(tokenA),
      data: { default_category: 'WORK', default_post_team_id: teamId },
    })
    if (!setRes.ok()) throw new Error(`設定失敗: ${setRes.status()} ${await setRes.text()}`)
  })

  test.afterAll(async () => {
    await ctxApi?.dispose()
  })

  test('S1 正の視点: チーム投稿 → 個人1日分投稿でもチーム投稿が残り、個人投稿は新しい1件だけ', async ({ browser }) => {
    const { ctx, page } = await userContext(browser, users.A.email)
    try {
      await createMemoViaUi(page, MARK1)
      const p1 = await publishDailyViaUi(page) // 個人 1 回目
      dumpPosts('S1-after-first-daily')
      {
        const probe = await ctxApi.get(`${BE_API}/timeline/posts/${p1}`, { headers: headers(tokenA) })
        console.log(`S1 補助(API) GET /timeline/posts/${p1} status=${probe.status()} body=${(await probe.text()).slice(0, 300)}`)
      }
      // 個人投稿には一覧画面が無いため、投稿詳細（/timeline/{id}）で本人の個人投稿を確認する
      await page.goto(`/timeline/${p1}`)
      await waitForHydration(page)
      await expect(page.getByText(MARK1).first()).toBeVisible({ timeout: 30_000 })
      await shot(page, 's1-1-personal-post1-visible')

      await publishToTeamViaUi(page) // チーム投稿
      dumpPosts('S1-after-team-post')
      const p2 = await publishDailyViaUi(page) // 個人 2 回目（バグ再現点）
      dumpPosts('S1-after-second-daily')
      expect(p2).not.toBe(p1)

      // 新しい個人投稿が見える
      await page.goto(`/timeline/${p2}`)
      await waitForHydration(page)
      await expect(page.getByText(MARK1).first()).toBeVisible({ timeout: 30_000 })
      await shot(page, 's1-2-personal-post2-visible')
      // 古い個人投稿は消えている
      await page.goto(`/timeline/${p1}`)
      await waitForHydration(page)
      await page.waitForTimeout(5000)
      await expect(page.getByText(MARK1)).toHaveCount(0)
      console.log(`S1 古い個人投稿 /timeline/${p1} 本文=${await bodyText(page)}`)
      await shot(page, 's1-2b-personal-post1-gone')

      await openTeamTimeline(page)
      await expect(page.getByText(MARK1).first()).toBeVisible({ timeout: 30_000 })
      await shot(page, 's1-3-team-timeline-keeps-team-post')
    } finally {
      await ctx.close()
    }
  })

  test('S2 別メンバーB: チームタイムラインにAのチーム投稿が見える', async ({ browser }) => {
    const { ctx, page } = await userContext(browser, users.B.email)
    try {
      await openTeamTimeline(page)
      await expect(page.getByText(MARK1).first()).toBeVisible({ timeout: 30_000 })
      await shot(page, 's2-member-b-team-timeline')
      const pid = teamPostIdOf(MARK1)
      expect(pid).toBeTruthy()
      await page.goto(`/timeline/${pid}`)
      await waitForHydration(page)
      await expect(page.getByText(MARK1).first()).toBeVisible({ timeout: 30_000 })
      await shot(page, 's2-member-b-team-post-detail')
    } finally {
      await ctx.close()
    }
  })

  test('S4 逆順: 新しいメモで チーム投稿 → 個人1日分投稿 でもチーム投稿が残る', async ({ browser }) => {
    const { ctx, page } = await userContext(browser, users.A.email)
    try {
      await createMemoViaUi(page, MARK2)
      await publishToTeamViaUi(page)
      await publishDailyViaUi(page)
      dumpPosts('S4-after-daily')
      await openTeamTimeline(page)
      await expect(page.getByText(MARK2).first()).toBeVisible({ timeout: 30_000 })
      await expect(page.getByText(MARK1).first()).toBeVisible()
      await shot(page, 's4-team-timeline-both-posts')
    } finally {
      await ctx.close()
    }
  })

  test('S3 負の視点・他テナントC: チームタイムライン・投稿詳細・メモURL直打ちが弾かれる', async ({ browser }) => {
    // 前提: URL直打ち用に A のチーム投稿IDと行動メモIDを取得する（判定は画面）
    const postId = teamPostIdOf(MARK1)
    const today = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Tokyo' })
    const memosRes = await ctxApi.get(`${BE_API}/action-memos?date=${today}`, { headers: headers(tokenA) })
    let memoId: number | undefined
    if (memosRes.ok()) {
      const mb = (await memosRes.json()) as { data?: { id: number }[] | { content?: { id: number }[] } }
      const arr = Array.isArray(mb.data) ? mb.data : (mb.data?.content ?? [])
      memoId = arr[0]?.id
    }
    console.log(`S3 前提 postId=${postId} memoId=${memoId}`)

    const { ctx, page } = await userContext(browser, users.C.email)
    try {
      await openTeamTimeline(page)
      await page.waitForTimeout(5000)
      await expect(page.getByText(MARK1)).toHaveCount(0)
      await expect(page.getByText(MARK2)).toHaveCount(0)
      await shot(page, 's3-1-outsider-team-timeline')
      console.log(`S3 チームタイムライン直打ち URL=${page.url()} 本文=${await bodyText(page)}`)

      if (postId) {
        await page.goto(`/timeline/${postId}`)
        await waitForHydration(page)
        await page.waitForTimeout(5000)
        await expect(page.getByText(MARK1)).toHaveCount(0)
        await shot(page, 's3-2-outsider-post-detail')
        console.log(`S3 投稿詳細直打ち URL=${page.url()} 本文=${await bodyText(page)}`)
      }

      await page.goto(`/action-memo/team/${teamId}`)
      await waitForHydration(page)
      await page.waitForTimeout(5000)
      await expect(page.getByText(MARK1)).toHaveCount(0)
      await shot(page, 's3-3-outsider-action-memo-team')
      console.log(`S3 行動メモ(チーム)直打ち URL=${page.url()} 本文=${await bodyText(page)}`)

      await page.goto('/action-memo')
      await waitForHydration(page)
      await page.waitForTimeout(3000)
      await expect(page.getByText(MARK1)).toHaveCount(0)
      await shot(page, 's3-4-outsider-own-action-memo')

      if (memoId) {
        const tokenC = await apiLogin(users.C.email)
        const r = await ctxApi.get(`${BE_API}/action-memos/${memoId}`, { headers: headers(tokenC) })
        console.log(`S3 補助(API) 他人メモ直取得 memoId=${memoId} status=${r.status()}`)
        expect([403, 404]).toContain(r.status())
      }
    } finally {
      await ctx.close()
    }
  })
})
