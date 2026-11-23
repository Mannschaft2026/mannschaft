/**
 * 実機E2E: 古い応答が新しい表示を上書きしない（CMP-260925-0903 / PR #4741）
 *
 * 対象
 *   - 求人一覧            /jobs                                                    ページ送りで取得が重なる
 *   - 募集検索            /recruitment-listings                                    検索条件の変更・再検索で取得が重なる
 *   - 訪問履歴レビュー    /organizations/[slug]/residence-status/monitoring-visits-review
 *   - 点呼                /teams/[slug]/events/[eventId]/roll-call                 履歴ドロワー（useRollCall.loadSessions）
 *
 * 競合の作り方（共有 BE/DB は壊さない）
 *   「遅い応答 A のあとに速い応答 B が返る」状況を、ブラウザ側で**特定の 1 本のリクエストだけ**を遅らせて作る
 *   （page.route → route.fetch() で実 BE の応答をその場で取得 → 一定時間待ってから**同じ応答をそのまま返す**。
 *   応答の中身は差し替えていない）。A の応答は A を発行した時点の実 BE の内容（古い内容）である。
 *   操作は実画面のクリック・Enter・入力で踏む。API は前提データの作成・後始末・ログインだけに使う。
 *   失敗の古い応答（A が遅れて失敗）は、遅らせたあとに接続失敗（abort）で返して作る（補助確認。実 BE の失敗ではない）。
 *
 * ロール横断: e2e-user=チーム MEMBER / e2e-dummy-1=fc-u-18 の ADMIN かつ組織 ADMIN / e2e-outsider=無所属。
 *   ログインはロールごとに 1 回（429・トークンローテーション回避）。
 */
import {
  test,
  expect,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type Route,
  type Request,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const BE_API = `${API_BASE_URL}/api/v1`
const PASSWORD = 'TestPass2026!'
const TEAM_SLUG = 'fc-u-18'
const RUN_TAG = `R${Date.now().toString(36)}`
const CATEGORY_PRACTICE_MATCH = 9

type Actor = 'user' | 'teamAdmin' | 'outsider'
const ACTOR_EMAIL: Record<Actor, string> = {
  user: 'e2e-user@test.mannschaft.local',
  teamAdmin: 'e2e-dummy-1@test.mannschaft.local',
  outsider: 'e2e-outsider@test.mannschaft.local',
}

interface Session { ctx: BrowserContext, page: Page, token: string, userId: number }
const sessions = {} as Record<Actor, Session>

async function openSession(browser: Browser, actor: Actor): Promise<Session> {
  const ctx = await browser.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost:3001' })
  const page = await ctx.newPage()
  const creds = { email: ACTOR_EMAIL[actor], password: PASSWORD }
  try {
    await loginViaApi(page, creds, { apiBaseUrl: API_BASE_URL })
  } catch (e) {
    if (!String(e).includes('429')) throw e
    await page.waitForTimeout(65_000)
    await loginViaApi(page, creds, { apiBaseUrl: API_BASE_URL })
  }
  const token = (await ctx.cookies()).find(c => c.name === 'access_token')?.value
  if (!token) throw new Error(`access_token Cookie が無い: ${actor}`)
  const me = await (await page.request.get(`${BE_API}/users/me`)).json()
  return { ctx, page, token, userId: me.data.id as number }
}

type Method = 'post' | 'get' | 'delete' | 'patch' | 'put'
async function api(s: Session, method: Method, path: string, data?: unknown) {
  const req: APIRequestContext = s.ctx.request
  return req[method](`${BE_API}${path}`, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${s.token}` },
    data,
  })
}
async function must<T>(res: Awaited<ReturnType<typeof api>>, what: string): Promise<T> {
  if (!res.ok()) throw new Error(`${what} 失敗: ${res.status()} ${await res.text()}`)
  if (res.status() === 204) return undefined as T
  return (await res.json()).data as T
}

function jstIso(offsetMs: number): string {
  return new Date(Date.now() + offsetMs + 9 * 3600_000).toISOString().slice(0, 19)
}
const DAY = 86_400_000

const EVIDENCE_DIR = '../docs/evidence/CMP-260925-0903'
async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${EVIDENCE_DIR}/${name}.png` })
}

/**
 * 条件に合う「最初の 1 本」を、実 BE へ即座に投げて応答を受け取ったうえで delayMs 待ってから返す。
 * 応答の中身は変えない（失敗させたい場合は fail=true で、待ってから接続失敗にする）。
 * 条件に合う 2 本目以降は何もせず素通しする。
 */
async function delayFirstMatching(
  page: Page,
  match: (url: URL, req: Request) => boolean,
  delayMs: number,
  opts: { fail?: boolean } = {},
) {
  let consumed = false
  const state = { started: 0, settled: 0, body: undefined as unknown }
  const matcher = (u: URL) => match(u, undefined as unknown as Request)
  const handler = async (route: Route) => {
    if (consumed) { await route.continue(); return }
    consumed = true
    state.started = Date.now()
    if (opts.fail) {
      await new Promise(r => setTimeout(r, delayMs))
      state.settled = Date.now()
      await route.abort('failed')
      return
    }
    const resp = await route.fetch()
    try { state.body = await resp.json() } catch { /* 本文を持たない応答 */ }
    await new Promise(r => setTimeout(r, delayMs))
    state.settled = Date.now()
    await route.fulfill({ response: resp })
  }
  await page.route(matcher, handler)
  return {
    state,
    waitSettled: async (timeout = 60_000) => {
      const t0 = Date.now()
      while (!state.settled) {
        if (Date.now() - t0 > timeout) throw new Error('遅延させた応答が時間内に返らなかった')
        await page.waitForTimeout(200)
      }
    },
    dispose: async () => { await page.unroute(matcher, handler) },
  }
}

// ---- 作成データ ----
const created = {
  jobIds: [] as number[],
  listingIds: [] as number[],
  scheduleId: 0,
  eventId: 0,
  teamId: 0,
  orgSlug: '',
  committeeA: 0,
  committeeB: 0,
}
const JOB_COUNT = 21
const jobTitle = (i: number) => `E2E求人_${RUN_TAG}_${String(i).padStart(2, '0')}`
const LISTING_A = `E2E募集_${RUN_TAG}-A`
const LISTING_B = `E2E募集_${RUN_TAG}-B`

test.describe.configure({ mode: 'serial' })

test.beforeAll(async ({ browser }) => {
  test.setTimeout(900_000)
  for (const a of ['user', 'teamAdmin', 'outsider'] as Actor[]) sessions[a] = await openSession(browser, a)
  const admin = sessions.teamAdmin

  const team = await must<{ numericId: number }>(await api(admin, 'get', `/teams/${TEAM_SLUG}`), 'チーム取得')
  created.teamId = team.numericId
  const roleOf = async (a: Actor) =>
    (await must<{ roleName: string | null }>(await api(sessions[a], 'get', `/teams/${TEAM_SLUG}/me/permissions`), `権限(${a})`)).roleName
  expect(await roleOf('teamAdmin')).toBe('ADMIN')
  expect(await roleOf('user')).toBe('MEMBER')
  expect(await roleOf('outsider')).toBeNull()

  // --- 求人 21 件（2 ページ分）。公開済み・チーム内公開
  for (let i = 1; i <= JOB_COUNT; i++) {
    const j = await must<{ id: number }>(await api(admin, 'post', '/jobs', {
      teamId: team.numericId,
      title: jobTitle(i),
      description: 'CMP-260925-0903 実機E2E用（自動削除）',
      workLocationType: 'ONSITE',
      workAddress: 'E2E',
      workStartAt: jstIso(10 * DAY),
      workEndAt: jstIso(10 * DAY + 3 * 3600_000),
      rewardType: 'LUMP_SUM',
      baseRewardJpy: 1000,
      capacity: 1,
      applicationDeadlineAt: jstIso(5 * DAY),
      visibilityScope: 'TEAM_MEMBERS',
    }), `求人作成${i}`)
    created.jobIds.push(j.id)
    await must(await api(admin, 'post', `/jobs/${j.id}/publish`, {}), `求人公開${i}`)
  }

  // --- 募集枠 2 件（無料・公開）
  for (const title of [LISTING_A, LISTING_B]) {
    const l = await must<{ id: number }>(await api(admin, 'post', `/teams/${team.numericId}/recruitment-listings`, {
      title,
      categoryId: CATEGORY_PRACTICE_MATCH,
      participationType: 'INDIVIDUAL',
      startAt: jstIso(7 * DAY),
      endAt: jstIso(7 * DAY + 3600_000),
      applicationDeadline: jstIso(6 * DAY),
      autoCancelAt: jstIso(5 * DAY),
      capacity: 5,
      minCapacity: 1,
      paymentEnabled: false,
      location: 'E2E会場',
      visibility: 'PUBLIC',
    }), `募集作成(${title})`)
    created.listingIds.push(l.id)
    await must(await api(admin, 'put', `/recruitment-listings/${l.id}/distribution-targets`, { targetTypes: ['PUBLIC_FEED'] }), '配信対象')
    await must(await api(admin, 'post', `/recruitment-listings/${l.id}/publish`), `募集公開(${title})`)
  }

  // --- 訪問履歴: 委員会 A に 1 件だけ作る（B は 0 件）。API に削除が無いため 1 行が残る（証拠文書に明記）
  const orgs = await must<Array<{ id: number, slug: string, role: string }>>(await api(admin, 'get', '/me/organizations'), '所属組織')
  const org = orgs.find(o => o.role === 'ADMIN')
  if (!org) throw new Error('組織 ADMIN の所属が無い')
  created.orgSlug = org.slug
  created.committeeA = 900_000_000 + Math.floor(Math.random() * 99_999_999)
  created.committeeB = created.committeeA + 1
  await must(await api(admin, 'post', `/organizations/${org.id}/residence-status/monitoring-visits`, {
    committeeId: created.committeeA,
    residentRegistryId: created.committeeA,
    dwellingUnitId: created.committeeA,
    subjectUserId: sessions.user.userId,
    visitorUserId: admin.userId,
    visitedAt: jstIso(-3600_000),
    contactResult: 'MET',
    considerationMemo: `E2E_${RUN_TAG}`,
  }), '訪問記録作成')

  // --- 点呼: RSVP モードのチームイベント + e2e-user が出席回答
  const sch = await must<{ id: number }>(await api(admin, 'post', `/teams/${team.numericId}/schedules`, {
    title: `E2E点呼_${RUN_TAG}`,
    startAt: `${jstIso(2 * 3600_000)}+09:00`,
    endAt: `${jstIso(4 * 3600_000)}+09:00`,
    allDay: false,
    eventType: 'EVENT',
    attendanceRequired: false,
  }), '点呼用スケジュール作成')
  created.scheduleId = sch.id
  const ev = await must<{ id: number }>(await api(admin, 'post', `/teams/${team.numericId}/events`, {
    scheduleId: sch.id, slug: `e2e-${RUN_TAG.toLowerCase()}`, visibility: 'MEMBERS_ONLY', attendanceMode: 'RSVP',
  }), 'イベント作成')
  created.eventId = ev.id
  await must(await api(admin, 'post', `/teams/${team.numericId}/events/${ev.id}/publish`), 'イベント公開')
  await must(await api(sessions.user, 'post', `/teams/${team.numericId}/events/${ev.id}/rsvp-responses`, { response: 'ATTENDING' }), 'RSVP')

  // dev サーバーの初回コンパイルを温める
  for (const p of ['/jobs', '/recruitment-listings',
    `/organizations/${org.slug}/residence-status/monitoring-visits-review`,
    `/teams/${TEAM_SLUG}/events/${ev.id}/roll-call`]) {
    await admin.page.goto(p, { waitUntil: 'domcontentloaded', timeout: 240_000 })
  }
})

test.afterAll(async () => {
  const log = (what: string, res: { status(): number }) => console.log(`[CLEANUP] ${what} -> ${res.status()}`)
  try {
    const admin = sessions.teamAdmin
    for (const id of created.jobIds) log(`求人${id}`, await api(admin, 'delete', `/jobs/${id}`))
    for (const id of created.listingIds) log(`募集${id}`, await api(admin, 'post', `/recruitment-listings/${id}/cancel`, { reason: 'e2e cleanup' }))
    if (created.eventId) log('イベント', await api(admin, 'delete', `/teams/${created.teamId}/events/${created.eventId}`))
    if (created.scheduleId) log('スケジュール', await api(admin, 'delete', `/teams/${created.teamId}/schedules/${created.scheduleId}`))
  } finally {
    for (const s of Object.values(sessions)) await s?.ctx.close()
  }
})

// =============================================================================
// 1. 求人一覧 /jobs
// =============================================================================
test.describe('JOBS: /jobs', () => {
  const pageBtn = (page: Page, n: number) => page.locator('.p-paginator-page', { hasText: new RegExp(`^${n}$`) })
  const isJobsPage = (n: number) => (u: URL) => u.pathname === '/api/v1/jobs' && u.searchParams.get('page') === String(n)

  test('JOBS-OK: 成功時は求人が出て、2 ページ分ある（権限あり: 所属チームの MEMBER）', async () => {
    const { page } = sessions.user
    await page.goto('/jobs', { waitUntil: 'domcontentloaded' })
    await expect(page.getByText(new RegExp(`E2E求人_${RUN_TAG}_`)).first()).toBeVisible({ timeout: 60_000 })
    await expect(pageBtn(page, 2)).toBeVisible()
    await expect(page.getByTestId('jobs-list-error-state')).toHaveCount(0)
  })

  test('JOBS-RACE: ページ 2（遅い A）→ すぐページ 1（速い B）。A が遅れて届いても最終表示は B のまま', async () => {
    const { page } = sessions.user
    await page.goto('/jobs', { waitUntil: 'domcontentloaded' })
    await expect(pageBtn(page, 2)).toBeVisible({ timeout: 60_000 })
    const firstOnPage0 = await page.locator('div.container div.cursor-pointer').first().innerText()

    const slow = await delayFirstMatching(page, isJobsPage(1), 8_000)
    await pageBtn(page, 2).click() // A（ページ 2 = page=1）を発行。8 秒保留
    await expect.poll(() => slow.state.started, { timeout: 10_000 }).toBeGreaterThan(0)
    await pageBtn(page, 1).click() // B（ページ 1 = page=0）。速い
    await expect(page.locator('div.container div.cursor-pointer').first()).toBeVisible({ timeout: 20_000 })
    const countB = await page.locator('div.container div.cursor-pointer').count()
    const body = slow.state.body as { data: Array<{ title: string }> }
    const pageTwoTitle = body.data[0]!.title
    expect(countB).toBe(20)
    expect(await page.locator('div.container div.cursor-pointer').first().innerText()).toBe(firstOnPage0)
    await expect(page.getByText(pageTwoTitle)).toHaveCount(0)

    await slow.waitSettled() // A の応答が画面に届く
    await page.waitForTimeout(1_500)
    // 最終表示は B（ページ 1）のまま。A（ページ 2 の内容）に戻っていない
    expect(await page.locator('div.container div.cursor-pointer').count()).toBe(20)
    expect(await page.locator('div.container div.cursor-pointer').first().innerText()).toBe(firstOnPage0)
    await expect(page.getByText(pageTwoTitle)).toHaveCount(0)
    await expect(page.getByTestId('jobs-list-error-state')).toHaveCount(0)
    await shot(page, 'jobs-race-final')
    await slow.dispose()
  })

  test('JOBS-RACE-FAIL: 古い応答 A が遅れて「失敗」しても、新しい成功表示を失敗表示で上書きしない（障害注入）', async () => {
    const { page } = sessions.user
    await page.goto('/jobs', { waitUntil: 'domcontentloaded' })
    await expect(pageBtn(page, 2)).toBeVisible({ timeout: 60_000 })
    const slow = await delayFirstMatching(page, isJobsPage(1), 6_000, { fail: true })
    await pageBtn(page, 2).click()
    await expect.poll(() => slow.state.started, { timeout: 10_000 }).toBeGreaterThan(0)
    await pageBtn(page, 1).click()
    await expect(page.locator('div.container div.cursor-pointer').first()).toBeVisible({ timeout: 20_000 })
    await slow.waitSettled()
    await page.waitForTimeout(1_500)
    await expect(page.getByTestId('jobs-list-error-state')).toHaveCount(0)
    expect(await page.locator('div.container div.cursor-pointer').count()).toBe(20)
    await slow.dispose()
  })

  test('JOBS-ROLE-OUTSIDER: 無所属（他テナント）には求人が出ず、「所属チームなし」の案内になる', async () => {
    const { page } = sessions.outsider
    await page.goto('/jobs', { waitUntil: 'domcontentloaded' })
    await expect(page.getByText(/チームに所属していません|所属しているチームがありません|所属チーム/).first()).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(new RegExp(`E2E求人_${RUN_TAG}_`))).toHaveCount(0)
  })
})

// =============================================================================
// 2. 募集検索 /recruitment-listings
// =============================================================================
test.describe('RECRUIT: /recruitment-listings', () => {
  const isSearchWith = (kw: string) => (u: URL) =>
    u.pathname === '/api/v1/recruitment-listings/search' && u.searchParams.get('keyword') === kw
  const keywordInput = (page: Page) => page.locator('input[type="text"]').first()

  test('RECRUIT-OK: キーワード A で検索 → A だけが出る（正常系）', async () => {
    const { page } = sessions.user
    await page.goto('/recruitment-listings', { waitUntil: 'domcontentloaded' })
    await keywordInput(page).fill(`${RUN_TAG}-A`)
    await keywordInput(page).press('Enter')
    await expect(page.getByText(LISTING_A)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(LISTING_B)).toHaveCount(0)
  })

  test('RECRUIT-RACE: 検索 A（遅い）→ 条件を B に変えて再検索（速い）。A が遅れて届いても最終表示は B', async () => {
    const { page } = sessions.user
    await page.goto('/recruitment-listings', { waitUntil: 'domcontentloaded' })
    const kw = keywordInput(page)
    await expect(kw).toBeVisible({ timeout: 60_000 })
    const slow = await delayFirstMatching(page, isSearchWith(`${RUN_TAG}-A`), 8_000)
    await kw.fill(`${RUN_TAG}-A`)
    await kw.press('Enter') // A を発行。8 秒保留
    await expect.poll(() => slow.state.started, { timeout: 10_000 }).toBeGreaterThan(0)
    await kw.fill(`${RUN_TAG}-B`)
    await kw.press('Enter') // B。速い
    await expect(page.getByText(LISTING_B)).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText(LISTING_A)).toHaveCount(0)

    await slow.waitSettled()
    await page.waitForTimeout(1_500)
    // 最終表示は B のまま。A（古い検索）に戻っていない
    await expect(page.getByText(LISTING_B)).toBeVisible()
    await expect(page.getByText(LISTING_A)).toHaveCount(0)
    await shot(page, 'recruitment-race-final')
    await slow.dispose()
  })

  test('RECRUIT-RACE-FAIL: 古い検索 A が遅れて失敗しても、B の成功表示を失敗表示で上書きしない（障害注入）', async () => {
    const { page } = sessions.user
    await page.goto('/recruitment-listings', { waitUntil: 'domcontentloaded' })
    const kw = keywordInput(page)
    await expect(kw).toBeVisible({ timeout: 60_000 })
    const slow = await delayFirstMatching(page, isSearchWith(`${RUN_TAG}-A`), 6_000, { fail: true })
    await kw.fill(`${RUN_TAG}-A`)
    await kw.press('Enter')
    await expect.poll(() => slow.state.started, { timeout: 10_000 }).toBeGreaterThan(0)
    await kw.fill(`${RUN_TAG}-B`)
    await kw.press('Enter')
    await expect(page.getByText(LISTING_B)).toBeVisible({ timeout: 30_000 })
    await slow.waitSettled()
    await page.waitForTimeout(1_500)
    await expect(page.getByTestId('recruitment-listings-error-state')).toHaveCount(0)
    await expect(page.getByText(LISTING_B)).toBeVisible()
    await slow.dispose()
  })

  test('RECRUIT-ROLE-OUTSIDER: 公開検索は無所属ユーザーにも同じ結果（公開枠）。非公開の概念は無い', async () => {
    const { page } = sessions.outsider
    await page.goto('/recruitment-listings', { waitUntil: 'domcontentloaded' })
    const kw = keywordInput(page)
    await kw.fill(`${RUN_TAG}-B`)
    await kw.press('Enter')
    await expect(page.getByText(LISTING_B)).toBeVisible({ timeout: 60_000 })
  })
})

// =============================================================================
// 3. 訪問履歴レビュー
// =============================================================================
test.describe('VISITS: monitoring-visits-review', () => {
  const url = () => `/organizations/${created.orgSlug}/residence-status/monitoring-visits-review`
  const isVisitsFor = (id: number) => (u: URL) =>
    u.pathname.endsWith('/residence-status/monitoring-visits') && u.searchParams.get('committeeId') === String(id)
  const committeeInput = (page: Page) => page.getByPlaceholder('委員会IDを入力')

  test('VISITS-OK: 委員会 A で検索 → 訪問記録が 1 件出る（権限あり: 組織 ADMIN）', async () => {
    const { page } = sessions.teamAdmin
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    await committeeInput(page).fill(String(created.committeeA))
    await committeeInput(page).press('Enter')
    await expect(page.getByRole('cell', { name: String(created.committeeA), exact: true }).first()).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText('対面確認').first()).toBeVisible()
  })

  test('VISITS-RACE: A（データあり・遅い）→ B（0 件・速い）。A が遅れて届いても最終表示は B の「該当なし」', async () => {
    const { page } = sessions.teamAdmin
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    const input = committeeInput(page)
    await expect(input).toBeVisible({ timeout: 60_000 })
    const slow = await delayFirstMatching(page, isVisitsFor(created.committeeA), 8_000)
    await input.fill(String(created.committeeA))
    await input.press('Enter') // A を発行。8 秒保留
    await expect.poll(() => slow.state.started, { timeout: 10_000 }).toBeGreaterThan(0)
    await input.fill(String(created.committeeB))
    await input.press('Enter') // B。速い（0 件）
    await expect(page.getByText('該当する訪問記録がありません')).toBeVisible({ timeout: 30_000 })

    await slow.waitSettled()
    await page.waitForTimeout(1_500)
    // 最終表示は B（0 件）のまま。A の訪問記録（委員会 A の行）に戻っていない
    await expect(page.getByText('該当する訪問記録がありません')).toBeVisible()
    await expect(page.getByRole('cell', { name: String(created.committeeA), exact: true })).toHaveCount(0)
    await shot(page, 'visits-race-final')
    await slow.dispose()
  })

  test('VISITS-RACE-FAIL: 古い検索 A が遅れて失敗しても、B の表示を失敗表示で上書きしない（障害注入）', async () => {
    const { page } = sessions.teamAdmin
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    const input = committeeInput(page)
    await expect(input).toBeVisible({ timeout: 60_000 })
    const slow = await delayFirstMatching(page, isVisitsFor(created.committeeA), 6_000, { fail: true })
    await input.fill(String(created.committeeA))
    await input.press('Enter')
    await expect.poll(() => slow.state.started, { timeout: 10_000 }).toBeGreaterThan(0)
    await input.fill(String(created.committeeB))
    await input.press('Enter')
    await expect(page.getByText('該当する訪問記録がありません')).toBeVisible({ timeout: 30_000 })
    await slow.waitSettled()
    await page.waitForTimeout(1_500)
    await expect(page.getByTestId('monitoring-visits-review-error-state')).toHaveCount(0)
    await expect(page.getByText('該当する訪問記録がありません')).toBeVisible()
    await slow.dispose()
  })

  test('VISITS-ROLE-OUTSIDER: 無所属（他テナント）は検索しても訪問記録を見られず、実 BE の拒否で失敗表示になる', async () => {
    const { page } = sessions.outsider
    const statuses: number[] = []
    page.on('response', (r) => {
      if (r.url().includes('/residence-status/monitoring-visits')) statuses.push(r.status())
    })
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    const input = committeeInput(page)
    await input.fill(String(created.committeeA))
    await input.press('Enter')
    await expect(page.getByTestId('monitoring-visits-review-error-state')).toBeVisible({ timeout: 60_000 })
    expect(statuses.some(s => s === 403 || s === 404)).toBe(true)
    await expect(page.getByRole('cell', { name: String(created.committeeA), exact: true })).toHaveCount(0)
  })
})

// =============================================================================
// 4. 点呼
// =============================================================================
test.describe('ROLLCALL: /teams/[slug]/events/[eventId]/roll-call', () => {
  const url = () => `/teams/${TEAM_SLUG}/events/${created.eventId}/roll-call`
  const isSessions = (u: URL) => u.pathname.endsWith('/roll-call/sessions')

  test('ROLL-OK: 管理者に点呼候補（RSVP 出席の e2e-user）が出る', async () => {
    const { page } = sessions.teamAdmin
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId(`roll-call-row-${sessions.user.userId}`)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('roll-call-error-state')).toHaveCount(0)
  })

  test('ROLL-RACE-SESSIONS: 履歴取得 A（遅い・点呼前の古い内容）→ 点呼を確定 → 履歴を再取得 B。A が遅れて届いても最終表示は B（点呼 1 件）', async () => {
    const { page } = sessions.teamAdmin
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId(`roll-call-row-${sessions.user.userId}`)).toBeVisible({ timeout: 60_000 })

    // 1) 履歴を開く。この 1 本目（点呼前の内容＝0 件）の応答を 25 秒保留する
    const slow = await delayFirstMatching(page, isSessions, 25_000)
    await page.getByTestId('roll-call-open-history').click()
    await expect.poll(() => slow.state.started, { timeout: 10_000 }).toBeGreaterThan(0)
    await expect(page.getByTestId('roll-call-history-drawer')).toBeVisible()
    // 2) ドロワーを閉じて、点呼を UI で確定する
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('roll-call-history-drawer')).toBeHidden()
    await page.getByTestId('roll-call-bulk-present').click()
    await page.getByTestId('roll-call-open-submit').click()
    await page.getByTestId('roll-call-submit-confirm').click()
    await expect(page.getByTestId('roll-call-submit-dialog')).toBeHidden({ timeout: 30_000 })
    // 3) 履歴を開き直す（B。点呼 1 件の内容を速く返す）
    await page.getByTestId('roll-call-open-history').click()
    await expect(page.getByTestId('roll-call-history-item-0')).toBeVisible({ timeout: 20_000 })
    expect(slow.state.settled, 'A はまだ保留中（B が先に返った）であること').toBe(0)
    const sessionId = await page.getByTestId('roll-call-history-item-0').locator('code').innerText()

    // 4) A（古い応答: 0 件）が遅れて届く
    await slow.waitSettled(60_000)
    await page.waitForTimeout(1_500)
    await expect(page.getByTestId('roll-call-history-item-0')).toBeVisible()
    await expect(page.getByTestId('roll-call-history-empty')).toHaveCount(0)
    expect(await page.getByTestId('roll-call-history-item-0').locator('code').innerText()).toBe(sessionId)
    await shot(page, 'rollcall-history-final')
    await slow.dispose()
  })

  test('ROLL-FAIL-INJ: 候補取得の失敗（障害注入）→ 失敗表示。再試行で候補が出る', async () => {
    const { page } = sessions.teamAdmin
    const handler = (route: Route) => route.abort('failed')
    const glob = '**/roll-call/candidates*'
    await page.route(glob, handler)
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('roll-call-error-state')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('roll-call-sheet')).toHaveCount(0)
    await page.unroute(glob, handler)
    await page.getByTestId('roll-call-error-state-retry').click()
    await expect(page.getByTestId(`roll-call-row-${sessions.user.userId}`)).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('roll-call-error-state')).toHaveCount(0)
  })

  test('ROLL-ROLE-MEMBER: 一般メンバーが URL 直打ち → 実 BE の 403 で失敗表示（点呼シートは出ない）', async () => {
    const { page } = sessions.user
    const statuses: number[] = []
    page.on('response', (r) => { if (r.url().includes('/roll-call/candidates')) statuses.push(r.status()) })
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('roll-call-error-state')).toBeVisible({ timeout: 60_000 })
    expect(statuses).toContain(403)
    await expect(page.getByTestId('roll-call-sheet')).toHaveCount(0)
  })

  test('ROLL-ROLE-OUTSIDER: 無所属（他テナント）が URL 直打ち → 実 BE の拒否で失敗表示（点呼シートは出ない）', async () => {
    const { page } = sessions.outsider
    const statuses: number[] = []
    page.on('response', (r) => { if (r.url().includes('/roll-call/candidates')) statuses.push(r.status()) })
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('roll-call-error-state')).toBeVisible({ timeout: 60_000 })
    expect(statuses.some(s => s === 403 || s === 404)).toBe(true)
    await expect(page.getByTestId('roll-call-sheet')).toHaveCount(0)
  })
})
