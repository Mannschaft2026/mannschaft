/**
 * 実機E2E: 取得失敗が空状態に化けない（CMP-260925-0904 / PR #4740）
 *
 * 対象 3 画面
 *   - /calendar                                    予定の取得失敗 → 空のカレンダーに見せず、失敗表示＋再試行
 *   - /teams/[slug]/shifts/[scheduleId]/board      権限取得の失敗 → 権限不明のままボードを描画しない
 *   - /villages/[id]/join-request                  「自分の申請」取得の失敗 → 申請フォームを出さない（二重申請防止）
 *
 * 失敗の起こし方（共有 BE/DB は壊さない）
 *   - 実 BE の失敗（403/404）で起こせるもの: board の他テナント直打ち（BOARD-FAIL-REAL）
 *   - 実 BE に正当な失敗手段が無いもの（予定一覧・権限取得・自分の申請一覧の 500/通信断）は、
 *     ブラウザ側で「その 1 本のリクエストだけ」を接続失敗にする障害注入（page.route → abort）で補助確認する。
 *     応答の中身は差し替えない。これは「実 BE が失敗を返した」証拠ではなく、FE が失敗を受けたときの
 *     画面の振る舞いを実ブラウザ・実 FE で確かめるもの。再試行時は注入を外し、実 BE の成功応答で回復することを見る。
 *     （再試行中の読み込み表示を見るために、再試行のリクエストは 1.5 秒だけ保留してから素通しする。内容は不変。）
 *
 * 前提データは API で作る（ログイン・前提作成・後始末のみ）。対象操作（画面表示・再試行クリック・申請フォーム送信・
 * 承認）はすべて実 UI で踏む。作ったものは RUN_TAG を付け、afterAll で自分の分だけ削除する。
 *
 * ロール横断（e2e-user=チーム MEMBER / e2e-dummy-1=fc-u-18 の ADMIN / e2e-outsider=無所属 /
 *            e2e-dummy-5=村の作成者・HEADMAN）。ログインはロールごとに 1 回だけ（429 回避・トークンローテーション回避）。
 */
import {
  test,
  expect,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type Route,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const BE_API = `${API_BASE_URL}/api/v1`
const PASSWORD = 'TestPass2026!'
const TEAM_SLUG = 'fc-u-18'

const RUN_TAG = `R${Date.now().toString(36)}`
const LOWER_TAG = RUN_TAG.toLowerCase()

type Actor = 'user' | 'teamAdmin' | 'outsider' | 'villageHead'
const ACTOR_EMAIL: Record<Actor, string> = {
  user: 'e2e-user@test.mannschaft.local',
  teamAdmin: 'e2e-dummy-1@test.mannschaft.local',
  outsider: 'e2e-outsider@test.mannschaft.local',
  // 村の作成者（HEADMAN）。村作成申請は 1 ユーザー 24 時間 3 件までの上限があるため、使う seed ユーザーは環境変数で切り替えられる。
  villageHead: process.env.E2E_VILLAGE_HEAD ?? 'e2e-dummy-5@test.mannschaft.local',
}

interface Session {
  ctx: BrowserContext
  page: Page
  token: string
  userId: number
}
const sessions = {} as Record<Actor, Session>

async function openSession(browser: Browser, actor: Actor): Promise<Session> {
  const ctx = await browser.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost:3001' })
  const page = await ctx.newPage()
  // 429（AUTH_044）は待って 1 回だけ再試行する
  try {
    await loginViaApi(page, { email: ACTOR_EMAIL[actor], password: PASSWORD }, { apiBaseUrl: API_BASE_URL })
  } catch (e) {
    if (!String(e).includes('429')) throw e
    await page.waitForTimeout(65_000)
    await loginViaApi(page, { email: ACTOR_EMAIL[actor], password: PASSWORD }, { apiBaseUrl: API_BASE_URL })
  }
  const cookies = await ctx.cookies()
  const token = cookies.find(c => c.name === 'access_token')?.value
  if (!token) throw new Error(`access_token Cookie が無い: ${actor}`)
  const me = await (await page.request.get(`${BE_API}/users/me`)).json()
  return { ctx, page, token, userId: me.data.id as number }
}

const auth = (s: Session) => ({ 'Content-Type': 'application/json', Authorization: `Bearer ${s.token}` })

async function api(s: Session, method: 'post' | 'get' | 'delete' | 'patch', path: string, data?: unknown) {
  const req: APIRequestContext = s.ctx.request
  const res = await req[method](`${BE_API}${path}`, { headers: auth(s), data })
  return res
}

async function must<T>(res: Awaited<ReturnType<typeof api>>, what: string): Promise<T> {
  if (!res.ok()) throw new Error(`${what} 失敗: ${res.status()} ${await res.text()}`)
  if (res.status() === 204) return undefined as T
  return (await res.json()).data as T
}

function todayIso(): string {
  return new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 10)
}
function addDays(iso: string, d: number): string {
  const dt = new Date(`${iso}T00:00:00Z`)
  dt.setUTCDate(dt.getUTCDate() + d)
  return dt.toISOString().slice(0, 10)
}

/** 指定 URL パターンの「次の 1 本」だけを接続失敗にする（応答の中身は差し替えない）。 */
function failRequests(page: Page, urlGlob: string): { release: () => Promise<void>, hits: () => number } {
  let count = 0
  const handler = (route: Route) => {
    count++
    return route.abort('failed')
  }
  void page.route(urlGlob, handler)
  return {
    hits: () => count,
    release: async () => {
      await page.unroute(urlGlob, handler)
    },
  }
}

/** 注入を外した後の再試行リクエストを 1.5 秒保留してから素通しする（読み込み表示を観測するため。内容は不変）。 */
async function holdThenPass(page: Page, urlGlob: string) {
  const handler = async (route: Route) => {
    await new Promise(r => setTimeout(r, 1500))
    await route.continue()
  }
  await page.route(urlGlob, handler)
  return async () => { await page.unroute(urlGlob, handler) }
}

const EVIDENCE_DIR = '../docs/evidence/CMP-260925-0904'
async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${EVIDENCE_DIR}/${name}.png` })
}

// ---- 作成データ（afterAll で自分の分だけ消す） ----
const created = {
  personalScheduleId: 0,
  positionId: 0,
  scheduleIds: [] as number[],
  villageId: '',
  teamId: 0,
}
const TITLES = {
  personal: `E2E個人予定_${RUN_TAG}`,
  boardFull: `E2Eボード_${RUN_TAG}`,
  boardEmpty: `E2E空ボード_${RUN_TAG}`,
  position: `E2E受付_${RUN_TAG}`,
}

test.describe.configure({ mode: 'serial' })

test.beforeAll(async ({ browser }) => {
  test.setTimeout(600_000)
  for (const actor of ['user', 'teamAdmin', 'outsider', 'villageHead'] as Actor[]) {
    sessions[actor] = await openSession(browser, actor)
  }

  // 役者の裏取り（ロールが期待と違うと負の視点が静かに無効化されるため落とす）
  const roleOf = async (a: Actor) => {
    const d = await must<{ roleName: string | null }>(await api(sessions[a], 'get', `/teams/${TEAM_SLUG}/me/permissions`), `権限取得(${a})`)
    return d.roleName
  }
  expect(await roleOf('teamAdmin')).toBe('ADMIN')
  expect(await roleOf('user')).toBe('MEMBER')
  expect(await roleOf('outsider')).toBeNull()

  // --- calendar: e2e-user の個人予定（今日 10:00-11:00）
  const today = todayIso()
  const sch = await must<{ id: number }>(await api(sessions.user, 'post', '/me/schedules', {
    title: TITLES.personal,
    startAt: `${today}T10:00:00+09:00`,
    endAt: `${today}T11:00:00+09:00`,
    allDay: false,
  }), '個人予定作成')
  created.personalScheduleId = sch.id

  // --- board: fc-u-18 にポジション・シフト表（枠あり／枠なし）。MEMBER も開けるよう PUBLISHED にする
  const team = await must<{ numericId: number }>(await api(sessions.teamAdmin, 'get', `/teams/${TEAM_SLUG}`), 'チーム取得')
  created.teamId = team.numericId
  const pos = await must<{ id: number }>(await api(sessions.teamAdmin, 'post', `/shifts/positions?teamId=${team.numericId}`, {
    name: TITLES.position, displayOrder: 1,
  }), 'ポジション作成')
  created.positionId = pos.id
  const start = addDays(today, 7)
  const end = addDays(start, 2)
  for (const title of [TITLES.boardFull, TITLES.boardEmpty]) {
    const s = await must<{ id: number }>(await api(sessions.teamAdmin, 'post', `/shifts/schedules?teamId=${team.numericId}`, {
      title, startDate: start, endDate: end,
    }), `シフト表作成(${title})`)
    created.scheduleIds.push(s.id)
    if (title === TITLES.boardFull) {
      await must(await api(sessions.teamAdmin, 'post', `/shifts/schedules/${s.id}/slots`, {
        slotDate: start, startTime: '09:00:00', endTime: '12:00:00', positionId: pos.id, requiredCount: 2, note: null,
      }), '枠作成')
    }
    for (const st of ['COLLECTING', 'ADJUSTING', 'PUBLISHED']) {
      await must(await api(sessions.teamAdmin, 'post', `/shifts/schedules/${s.id}/transition?status=${st}`), `遷移 ${st}`)
    }
  }

  // --- village: APPROVAL 村。seed の村は全て FREE のため自前で作る。
  //   SYSTEM_ADMIN の直接作成 API は 1 日 3 件の上限があり共有されるため、一般ユーザーの「村作成申請」
  //   （即時承認・申請者が HEADMAN）で作り、HEADMAN が参加方式を APPROVAL に更新する。
  const cr = await must<{ createdVillageId: string }>(await api(sessions.villageHead, 'post', '/villages/creation-requests', {
    name: `E2E村_${RUN_TAG}`,
    slug: `e2e-${LOWER_TAG}`,
    category: 'E2E',
    purpose: 'CMP-260925-0904 実機E2E用（自動削除）',
    guidelineAgreedAt: new Date().toISOString(),
    type: 'COMMUNITY',
  }), '村作成申請')
  created.villageId = cr.createdVillageId
  await must(await api(sessions.villageHead, 'patch', `/villages/${created.villageId}`, { joinPolicy: 'APPROVAL' }), '参加方式を APPROVAL へ')
  const vv = await must<{ joinPolicy: string }>(await api(sessions.villageHead, 'get', `/villages/${created.villageId}`), '村取得')
  expect(vv.joinPolicy).toBe('APPROVAL')
  const v = { id: created.villageId }

  // dev サーバーの初回コンパイルを温める（テスト本体のタイムアウトを食わないため）
  for (const path of ['/calendar', `/villages/${v.id}/join-request`]) {
    await sessions.user.page.goto(path, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  }
})

test.afterAll(async () => {
  // 自分が作ったものだけ消す。失敗は握りつぶさずログに出す（残存の確認用）。
  const log = (what: string, res: { status(): number }) => console.log(`[CLEANUP] ${what} -> ${res.status()}`)
  try {
    if (created.personalScheduleId) log('個人予定', await api(sessions.user, 'delete', `/me/schedules/${created.personalScheduleId}`))
    for (const id of created.scheduleIds) log(`シフト表${id}`, await api(sessions.teamAdmin, 'delete', `/shifts/schedules/${id}`))
    if (created.positionId) log('ポジション', await api(sessions.teamAdmin, 'delete', `/shifts/positions/${created.positionId}`))
    if (created.villageId) {
      // 承認で村人になった e2e-user は先に退出させる（共有 seed ユーザーに所属を残さない）
      const mem = await api(sessions.villageHead, 'get', `/villages/${created.villageId}/memberships?size=100`)
      if (mem.ok()) {
        const list = ((await mem.json()).data?.content ?? []) as Array<{ id: string, subjectId: number }>
        for (const m of list) {
          if (m.subjectId === sessions.user.userId) log('e2e-user 退出', await api(sessions.user, 'delete', `/villages/${created.villageId}/memberships/${m.id}`))
        }
      }
      log('村', await api(sessions.villageHead, 'delete', `/villages/${created.villageId}`))
    }
  } finally {
    for (const s of Object.values(sessions)) await s?.ctx.close()
  }
})

// =============================================================================
// 1. /calendar
// =============================================================================
test.describe('CAL: /calendar', () => {
  test('CAL-OK: 成功時は本体が出て、実データの予定が表示される（正の視点: 本人）', async () => {
    const { page } = sessions.user
    await page.goto('/calendar', { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('calendar-body')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('calendar-load-error-state')).toHaveCount(0)
    await expect(page.getByTestId('calendar-retrying')).toHaveCount(0)
    await expect(page.getByText(TITLES.personal).locator('visible=true').first()).toBeVisible()
  })

  test('CAL-EMPTY: 0 件の月（予定なし）は失敗表示にならず空のカレンダーを出す', async () => {
    const { page } = sessions.user
    await page.goto('/calendar', { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('calendar-body')).toBeVisible({ timeout: 60_000 })
    // 「次月」を 14 回押して予定の無い遠い月へ（実操作）。月移動の取得が成功して本体が出続けること。
    const next = page.locator('button:has(.pi-chevron-right)').locator('visible=true').first()
    await expect(next).toBeVisible()
    for (let i = 0; i < 14; i++) await next.click()
    await expect(page.getByTestId('calendar-load-error-state')).toHaveCount(0)
    await expect(page.getByTestId('calendar-body')).toBeVisible()
    await expect(page.getByText(TITLES.personal).locator('visible=true')).toHaveCount(0)
  })

  test('CAL-FAIL: 予定取得の失敗 → 空カレンダーではなく失敗表示。再試行中は読み込み表示、成功で本体と予定が戻る', async () => {
    const { page } = sessions.user
    const inject = failRequests(page, '**/api/v1/me/schedules?*')
    await page.goto('/calendar', { waitUntil: 'domcontentloaded' })

    // 失敗表示が出て、本体（予定の無いカレンダー）は見えない
    await expect(page.getByTestId('calendar-load-error-state')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('calendar-body')).toBeHidden()
    await expect(page.getByText(TITLES.personal).locator('visible=true')).toHaveCount(0)
    expect(inject.hits()).toBeGreaterThan(0)
    await shot(page, 'calendar-load-error')

    // 再試行を押すが、まだ失敗する → 失敗表示のまま（空カレンダーに化けない）
    await page.getByTestId('calendar-load-error-state-retry').click()
    await expect(page.getByTestId('calendar-load-error-state')).toBeVisible()
    await expect(page.getByTestId('calendar-body')).toBeHidden()

    // 注入を外して再試行（取得は 1.5 秒保留）→ 読み込み表示 → 成功で本体
    await inject.release()
    const unhold = await holdThenPass(page, '**/api/v1/me/schedules?*')
    await page.getByTestId('calendar-load-error-state-retry').click()
    await expect(page.getByTestId('calendar-retrying')).toBeVisible()
    await expect(page.getByTestId('calendar-body')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('calendar-load-error-state')).toHaveCount(0)
    await expect(page.getByTestId('calendar-retrying')).toHaveCount(0)
    await expect(page.getByText(TITLES.personal).locator('visible=true').first()).toBeVisible()
    await unhold()
  })

  test('CAL-GANTT-FAIL: ガント取得の失敗 → 空のガントではなく失敗表示、再試行で回復', async () => {
    const { page } = sessions.user
    const inject = failRequests(page, '**/api/v1/todos/gantt?*')
    await page.goto('/calendar?tab=gantt', { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('calendar-gantt-error-state')).toBeVisible({ timeout: 60_000 })
    await inject.release()
    await page.getByTestId('calendar-gantt-error-state-retry').click()
    await expect(page.getByTestId('calendar-gantt-error-state')).toHaveCount(0)
  })

  test('CAL-ROLE: 他アカウント（無所属）の /calendar に本人の予定は出ない', async () => {
    const { page } = sessions.outsider
    await page.goto('/calendar', { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('calendar-body')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(TITLES.personal).locator('visible=true')).toHaveCount(0)
    await expect(page.getByTestId('calendar-load-error-state')).toHaveCount(0)
  })

  test('CAL-AUTHZ: 未ログインで /calendar を直打ちするとログインへ誘導される', async ({ browser }) => {
    const ctx = await browser.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost:3001' })
    const page = await ctx.newPage()
    await page.goto('/calendar', { waitUntil: 'domcontentloaded' })
    await expect(page).toHaveURL(/\/login/, { timeout: 60_000 })
    await ctx.close()
  })
})

// =============================================================================
// 2. シフトボード
// =============================================================================
test.describe('BOARD: /teams/[slug]/shifts/[scheduleId]/board', () => {
  const boardUrl = (id: number) => `/teams/${TEAM_SLUG}/shifts/${id}/board`

  /** 「メンバーの権限を初期設定」ダイアログが出たら「あとで決める」で閉じる。 */
  async function dismissSetupDialog(page: Page) {
    const later = page.getByRole('button', { name: 'あとで決める' })
    if (await later.isVisible().catch(() => false)) await later.click()
  }

  test('BOARD-OK: 管理者（ADMIN）で成功時はボードが出る（枠あり・ポジション名が見える）', async () => {
    const { page } = sessions.teamAdmin
    await page.goto(boardUrl(created.scheduleIds[0]!), { waitUntil: 'domcontentloaded' })
    await dismissSetupDialog(page)
    await expect(page.getByText(TITLES.boardFull)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(TITLES.position).first()).toBeVisible()
    await expect(page.getByTestId('shift-board-load-error')).toHaveCount(0)
  })

  test('BOARD-EMPTY: 枠 0 件のシフト表は失敗表示にならず、空のボードとして出る', async () => {
    const { page } = sessions.teamAdmin
    await page.goto(boardUrl(created.scheduleIds[1]!), { waitUntil: 'domcontentloaded' })
    await dismissSetupDialog(page)
    await expect(page.getByText(TITLES.boardEmpty)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('shift-board-load-error')).toHaveCount(0)
  })

  test('BOARD-FAIL-PERM: 権限取得の失敗 → 権限不明のままボードを描画せず、失敗表示。再試行で回復', async () => {
    const { page } = sessions.teamAdmin
    const inject = failRequests(page, `**/api/v1/teams/${TEAM_SLUG}/me/permissions*`)
    await page.goto(boardUrl(created.scheduleIds[0]!), { waitUntil: 'domcontentloaded' })
    await dismissSetupDialog(page)
    await expect(page.getByTestId('shift-board-load-error')).toBeVisible({ timeout: 60_000 })
    expect(inject.hits()).toBeGreaterThan(0)
    await shot(page, 'board-permission-load-error')
    // ボード本体（ポジション名の行・メンバープール）は描画されない
    await expect(page.getByText(TITLES.position)).toHaveCount(0)
    // 管理者専用の自動割当は、権限不明のあいだ出ない（死んだ導線・誤った権限表示を出さない）。
    // 注: ヘッダの「変更依頼」リンク等はシフト表自体が取得できているため残る（設計どおり。証拠文書に観察として記録）。
    await expect(page.getByTestId('auto-assign-button')).toHaveCount(0)

    // 注入を外して再試行 → ボードが出る
    await inject.release()
    await page.getByTestId('load-error-state-retry').click()
    await expect(page.getByText(TITLES.position).first()).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('shift-board-load-error')).toHaveCount(0)
  })

  test('BOARD-ROLE-MEMBER: 一般メンバー（MEMBER）でも公開済みのボードは失敗表示にならず出る', async () => {
    const { page } = sessions.user
    await page.goto(boardUrl(created.scheduleIds[0]!), { waitUntil: 'domcontentloaded' })
    await dismissSetupDialog(page)
    await expect(page.getByText(TITLES.boardFull)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('shift-board-load-error')).toHaveCount(0)
    // 自動割当は管理者専用。MEMBER には導線が出ない
    await expect(page.getByTestId('auto-assign-button')).toHaveCount(0)
  })

  test('BOARD-FAIL-REAL: 他テナント（無所属）が URL 直打ち → 実 BE の 403/404 で失敗表示。ボードは出ない', async () => {
    const { page } = sessions.outsider
    const statuses: number[] = []
    page.on('response', (r) => {
      if (r.url().includes('/api/v1/shifts/schedules/') && r.request().method() === 'GET') statuses.push(r.status())
    })
    await page.goto(boardUrl(created.scheduleIds[0]!), { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('shift-board-load-error')).toBeVisible({ timeout: 60_000 })
    expect(statuses.some(s => s === 403 || s === 404)).toBe(true)
    await expect(page.getByText(TITLES.position)).toHaveCount(0)
    await expect(page.getByTestId('auto-assign-button')).toHaveCount(0)
    // 所属外には再試行ボタンを出さない（403/404 の畳み表示）
    await expect(page.getByTestId('load-error-state-retry')).toHaveCount(0)
  })
})

// =============================================================================
// 3. 村の参加申請
// =============================================================================
test.describe('JOIN: /villages/[id]/join-request', () => {
  const url = () => `/villages/${created.villageId}/join-request`
  const MINE = '**/api/v1/villages/*/join-requests/me*'

  test('JOIN-FORM: 申請が 0 件の非メンバーには申請フォームが出る（成功・0 件の正常系）', async () => {
    const { page } = sessions.user
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('join-request-message')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('join-request-mine-error-state')).toHaveCount(0)
    await expect(page.getByTestId('join-request-withdraw')).toHaveCount(0)
  })

  test('JOIN-FAIL-ZERO: 申請 0 件でも取得失敗のときはフォームを出さず失敗表示 → 再試行で 0 件としてフォームが出る', async () => {
    const { page } = sessions.outsider
    const inject = failRequests(page, MINE)
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('join-request-mine-error-state')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('join-request-message')).toHaveCount(0)
    await expect(page.getByTestId('join-request-submit')).toHaveCount(0)

    await inject.release()
    await page.getByTestId('join-request-mine-error-state-retry').click()
    await expect(page.getByTestId('join-request-message')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('join-request-mine-error-state')).toHaveCount(0)
  })

  test('JOIN-SUBMIT: 申請フォームから送信 → 審査待ちパネル（永続化。リロードしても残る）', async () => {
    const { page } = sessions.user
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    await page.getByTestId('join-request-message').fill(`E2E申請メッセージ_${RUN_TAG}`)
    await page.getByTestId('join-request-submit').click()
    await expect(page.getByTestId('join-request-withdraw')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText(`E2E申請メッセージ_${RUN_TAG}`)).toBeVisible()

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('join-request-withdraw')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('join-request-message')).toHaveCount(0)
  })

  test('JOIN-FAIL-PENDING: 審査待ちの申請があるのに取得失敗 → フォームを出さない（二重申請の防止）。再試行で審査待ちが戻る', async () => {
    const { page } = sessions.user
    const inject = failRequests(page, MINE)
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('join-request-mine-error-state')).toBeVisible({ timeout: 60_000 })
    await shot(page, 'join-request-mine-load-error')
    // 申請フォームも送信ボタンも描画されない。審査待ちパネルの断定表示もしない
    await expect(page.getByTestId('join-request-message')).toHaveCount(0)
    await expect(page.getByTestId('join-request-submit')).toHaveCount(0)
    await expect(page.getByTestId('join-request-withdraw')).toHaveCount(0)

    // 再試行しても失敗の間はフォームを出さない
    await page.getByTestId('join-request-mine-error-state-retry').click()
    await expect(page.getByTestId('join-request-mine-error-state')).toBeVisible()
    await expect(page.getByTestId('join-request-submit')).toHaveCount(0)

    await inject.release()
    await page.getByTestId('join-request-mine-error-state-retry').click()
    await expect(page.getByTestId('join-request-withdraw')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('join-request-mine-error-state')).toHaveCount(0)
    await expect(page.getByTestId('join-request-message')).toHaveCount(0)
  })

  test('JOIN-ROLE-OTHER: 他アカウントには本人の審査待ち申請が出ない（自分の申請のみ）', async () => {
    const { page } = sessions.outsider
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('join-request-message')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('join-request-withdraw')).toHaveCount(0)
    await expect(page.getByText(`E2E申請メッセージ_${RUN_TAG}`)).toHaveCount(0)
  })

  test('JOIN-ROLE-REVIEWER: 審査権限あり（村長 HEADMAN）は審査一覧が出て、承認できる。承認後の本人は「すでに村人」案内になる', async () => {
    const admin = sessions.villageHead.page
    await admin.goto(url(), { waitUntil: 'domcontentloaded' })
    await expect(admin.getByTestId('join-request-review-table')).toBeVisible({ timeout: 60_000 })
    // 審査権限者には申請フォームは出ない
    await expect(admin.getByTestId('join-request-message')).toHaveCount(0)
    await expect(admin.getByText(`E2E申請メッセージ_${RUN_TAG}`)).toBeVisible()
    await admin.getByRole('button', { name: '承認' }).first().click()
    await admin.getByRole('dialog').getByRole('button', { name: '承認' }).click()
    await expect(admin.getByRole('dialog')).toBeHidden({ timeout: 30_000 })
    // 既定の絞り込みは PENDING。承認済みの申請は一覧から消える
    await expect(admin.getByTestId('join-request-review-table').getByText(`E2E申請メッセージ_${RUN_TAG}`)).toBeHidden({ timeout: 30_000 })

    // 承認された本人（村人）: フォームも審査待ちも出ず、「すでに村人」の案内
    const { page } = sessions.user
    await page.goto(url(), { waitUntil: 'domcontentloaded' })
    await expect(page.getByText('すでに村人です')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('join-request-message')).toHaveCount(0)
    await expect(page.getByTestId('join-request-mine-error-state')).toHaveCount(0)
  })
})
