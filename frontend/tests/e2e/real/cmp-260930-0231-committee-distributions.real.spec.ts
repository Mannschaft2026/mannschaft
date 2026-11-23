import { test, expect, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * 実機E2E: CMP-260930-0231 委員会の配信履歴画面（/committees/[id]/distributions）。
 *
 * 前提データは全て API で RUN_TAG 付きに作る（自前の組織・委員会）。共有 DB への直接 DML は行わない。
 *   組織A: e2e-user(CHAIR/org ADMIN)、e2e-dummy-2(SECRETARY)、e2e-dummy-3(MEMBER)、e2e-dummy-1(組織メンバーだが委員会に属さない)
 *   組織B: e2e-outsider（他組織の ADMIN。組織A とは無関係）
 * 後始末: 委員会は ARCHIVE、組織A/B は DELETE（自分が作った分だけ）。
 *
 * 実行: BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8080 npx playwright test -c playwright.cloud.local.config.ts \
 *         cmp-260930-0231 --project=chromium-real --no-deps --reporter=list --trace off
 */
const API = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PASSWORD = 'TestPass2026!'
const RUN_TAG = `R${Date.now().toString(36)}`
const mail = (n: string) => `${n}@test.mannschaft.local`
const EVIDENCE = '../docs/evidence/CMP-260930-0231'

test.setTimeout(180_000)

type Who = 'chair' | 'secretary' | 'member' | 'orgOnly' | 'outsider'
const ACCOUNTS: Record<Who, string> = {
  chair: mail('e2e-user'),
  secretary: mail('e2e-dummy-2'),
  member: mail('e2e-dummy-3'),
  orgOnly: mail('e2e-dummy-1'),
  outsider: mail('e2e-outsider'),
}

interface Session { ctx: BrowserContext; page: Page; token: string; userId: number }
const sessions = {} as Record<Who, Session>

let orgA = { slug: '', id: 0 }
let orgB = { slug: '', id: 0 }
let committeeId = 0
let seededTitleLogId = 0
let seededUntitledLogId = 0
const created = { orgInviteIds: [] as number[] }

async function openSession(browser: Browser, who: Who): Promise<Session> {
  const ctx = await browser.newContext({
    baseURL: process.env.BASE_URL ?? 'http://localhost:3001',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
  })
  const page = await ctx.newPage()
  // 429（email+IP で1分10回）は待って再試行
  for (let i = 0; ; i++) {
    try {
      await loginViaApi(page, { email: ACCOUNTS[who], password: PASSWORD }, { apiBaseUrl: API })
      break
    } catch (e) {
      if (i >= 4 || !String(e).includes('429')) throw e
      await page.waitForTimeout(15_000)
    }
  }
  const login = await page.request.post(`${API}/api/v1/auth/login`, { data: { email: ACCOUNTS[who], password: PASSWORD } })
  expect(login.ok(), `login ${who}`).toBeTruthy()
  const token = (await login.json()).data.accessToken as string
  const me = await page.request.get(`${API}/api/v1/users/me`)
  const userId = (await me.json()).data.id as number
  return { ctx, page, token, userId }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Json = any

async function api(req: APIRequestContext, method: 'get' | 'post' | 'patch' | 'delete', path: string, token: string, data?: unknown) {
  const res = await req[method](`${API}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
    ...(data === undefined ? {} : { data }),
  })
  const text = await res.text()
  let body: { data?: Json } = {}
  try { body = JSON.parse(text) } catch { /* 本文なし */ }
  return { status: res.status(), body, text }
}

const listDistributions = (who: Who) =>
  api(sessions[who].page.request, 'get', `/api/v1/committees/${committeeId}/distributions?size=50`, sessions[who].token)

async function openDistributions(page: Page) {
  await page.goto(`/committees/${committeeId}/distributions`)
  await waitForHydration(page)
  await dismissOverlays(page)
}

/** 管理者の初回「メンバーの権限を初期設定」ダイアログが覆う場合は「あとで決める」で閉じる。 */
async function dismissOverlays(page: Page) {
  const later = page.getByRole('button', { name: 'あとで決める' })
  if (await later.isVisible().catch(() => false)) await later.click()
}

test.describe.serial('CMP-260930-0231 委員会の配信履歴・配信操作 実機E2E', () => {
  test.beforeAll(async ({ browser }) => {
    for (const who of Object.keys(ACCOUNTS) as Who[]) sessions[who] = await openSession(browser, who)
    const chair = sessions.chair
    const req = chair.page.request

    // 組織A（chair が ADMIN）
    const o = await api(req, 'post', '/api/v1/organizations', chair.token, {
      name: `CD0231-${RUN_TAG}`, slug: `cd-${RUN_TAG.toLowerCase()}`, orgType: 'COMMUNITY', visibility: 'PUBLIC',
    })
    expect(o.status, o.text).toBe(201)
    orgA = { slug: o.body.data.slug, id: o.body.data.numericId }
    // 組織メンバー（dummy-1/2/3）を招待トークンで参加させる
    for (const who of ['orgOnly', 'secretary', 'member'] as Who[]) {
      const inv = await api(req, 'post', `/api/v1/organizations/${orgA.slug}/invite-tokens`, chair.token, { roleId: 4, maxUses: 1, expiresIn: '1d' })
      expect(inv.status, inv.text).toBe(201)
      created.orgInviteIds.push(inv.body.data.id)
      const j = await api(sessions[who].page.request, 'post', `/api/v1/invite/${inv.body.data.token}/join`, sessions[who].token)
      expect(j.status, `${who} join: ${j.text}`).toBe(200)
    }
    // 組織B（他組織: outsider が ADMIN）
    const ob = await api(sessions.outsider.page.request, 'post', '/api/v1/organizations', sessions.outsider.token, {
      name: `CD0231B-${RUN_TAG}`, slug: `cdb-${RUN_TAG.toLowerCase()}`, orgType: 'COMMUNITY', visibility: 'PUBLIC',
    })
    expect(ob.status, ob.text).toBe(201)
    orgB = { slug: ob.body.data.slug, id: ob.body.data.numericId }

    // 委員会（chair=CHAIR）→ ACTIVATE → 招集（SECRETARY / MEMBER）→ トークンで受諾
    const c = await api(req, 'post', `/api/v1/organizations/${orgA.id}/committees`, chair.token, {
      name: `CD0231-委員会-${RUN_TAG}`, initialChairUserId: chair.userId,
    })
    expect(c.status, c.text).toBe(201)
    committeeId = c.body.data.id
    const act = await api(req, 'post', `/api/v1/committees/${committeeId}/status`, chair.token, { action: 'ACTIVATE' })
    expect(act.status, act.text).toBe(200)
    for (const [who, role] of [['secretary', 'SECRETARY'], ['member', 'MEMBER']] as const) {
      const inv = await api(req, 'post', `/api/v1/committees/${committeeId}/invitations`, chair.token, {
        inviteeUserIds: [sessions[who].userId], proposedRole: role,
      })
      expect(inv.status, inv.text).toBe(201)
      const token = inv.body.data.invitations[0].inviteToken
      const acc = await api(sessions[who].page.request, 'post', '/api/v1/committee-invitations/accept-by-token', sessions[who].token, { inviteToken: token })
      expect(acc.status, `${who} accept: ${acc.text}`).toBe(200)
    }
    // 履歴の前提行（API=前提データ作成）: 件名付き行と、件名の無い行（アンケート結果）
    const d1 = await api(req, 'post', `/api/v1/committees/${committeeId}/distributions`, chair.token, {
      contentType: 'CUSTOM_MESSAGE', customTitle: `前提の連絡-${RUN_TAG}`, customBody: `前提の本文-${RUN_TAG}`,
      targetScope: 'PARENT_ORG', announcementEnabled: false, confirmationMode: 'OPTIONAL',
    })
    expect(d1.status, d1.text).toBe(201)
    seededTitleLogId = d1.body.data.id
    const d2 = await api(req, 'post', `/api/v1/committees/${committeeId}/distributions`, chair.token, {
      contentType: 'SURVEY_RESULT', contentId: 424242, targetScope: 'COMMITTEE_ONLY', announcementEnabled: false, confirmationMode: 'NONE',
    })
    expect(d2.status, d2.text).toBe(201)
    seededUntitledLogId = d2.body.data.id
  })

  test.afterAll(async () => {
    // 自分が作った分だけ後始末
    try {
      if (committeeId) {
        // 自分が生成した確認通知を各受信者側で確認済みにして、共有 seed ユーザーの確認待ちに残さない
        const logs = await listDistributions('chair')
        const nids = ((logs.body.data?.content ?? []) as { confirmableNotificationId: number | null }[])
          .map((x) => x.confirmableNotificationId).filter((x): x is number => !!x)
        for (const who of ['chair', 'secretary', 'member'] as Who[]) {
          for (const nid of nids) await api(sessions[who].page.request, 'post', `/api/v1/me/confirmable-notifications/${nid}/confirm`, sessions[who].token)
        }
      }
      if (committeeId) await api(sessions.chair.page.request, 'post', `/api/v1/committees/${committeeId}/status`, sessions.chair.token, { action: 'ARCHIVE' })
      if (orgA.slug) {
        for (const who of ['orgOnly', 'secretary', 'member'] as Who[]) {
          await api(sessions[who].page.request, 'delete', `/api/v1/organizations/${orgA.slug}/me`, sessions[who].token)
        }
        for (const id of created.orgInviteIds) await api(sessions.chair.page.request, 'delete', `/api/v1/organizations/${orgA.slug}/invite-tokens/${id}`, sessions.chair.token)
        await api(sessions.chair.page.request, 'delete', `/api/v1/organizations/${orgA.slug}`, sessions.chair.token)
      }
      if (orgB.slug) await api(sessions.outsider.page.request, 'delete', `/api/v1/organizations/${orgB.slug}`, sessions.outsider.token)
    } finally {
      for (const s of Object.values(sessions)) await s?.ctx.close()
    }
  })

  // ------------------------------------------------------------------ AC1 履歴の表示
  test('CD-E2E-01 履歴: 件名・本文・日時・ラベルが表示され、中身の無い行が並ばない（CHAIR）', async () => {
    const page = sessions.chair.page
    const errors: string[] = []
    page.on('response', (r) => { if (r.url().includes('/api/v1/') && r.status() >= 500) errors.push(`${r.status()} ${r.url()}`) })
    await openDistributions(page)
    await expect(page.getByRole('heading', { name: '伝達履歴' })).toBeVisible({ timeout: 30_000 })

    const titled = page.getByTestId(`distribution-row-${seededTitleLogId}`)
    await expect(titled).toBeVisible({ timeout: 30_000 })
    await expect(titled).toContainText(`前提の連絡-${RUN_TAG}`)
    await expect(titled).toContainText(`前提の本文-${RUN_TAG}`)
    await expect(titled).toContainText('組織全体') // 範囲ラベル
    await expect(titled).toContainText('確認モード: 任意') // 確認モードラベル
    await expect(titled).toContainText(/\d{4}\/\d{2}\/\d{2} \d{2}:\d{2}/) // 日時
    // 件名の二重表示でないこと
    expect((await titled.innerText()).split(`前提の連絡-${RUN_TAG}`).length - 1).toBe(1)

    const untitled = page.getByTestId(`distribution-row-${seededUntitledLogId}`)
    await expect(untitled).toContainText('アンケート結果 #424242') // 種別名 + 対象ID が見出し
    await expect(untitled).toContainText('委員会内のみ')
    await expect(untitled).toContainText('確認モード: 不要')

    // 生の enum 名が画面に出ていない・空行が無い
    const rows = page.locator('[data-testid^="distribution-row-"]')
    const n = await rows.count()
    expect(n).toBeGreaterThanOrEqual(2)
    for (let i = 0; i < n; i++) expect((await rows.nth(i).innerText()).trim().length).toBeGreaterThan(10)
    const body = await page.locator('body').innerText()
    for (const raw of ['COMMITTEE_ONLY', 'PARENT_ORG', 'CUSTOM_MESSAGE', 'SURVEY_RESULT', 'OPTIONAL']) expect(body).not.toContain(raw)
    expect(errors, errors.join('\n')).toHaveLength(0)
    await page.screenshot({ path: `${EVIDENCE}/01-history-chair.png` })
  })

  // ------------------------------------------------------------------ AC2 配信操作（正常系・入力検証）
  test('CD-E2E-02 配信操作: ダイアログ→入力→確認→配信→通知→履歴に新規行（CHAIR）。必須未入力・確認キャンセルは送信されない', async () => {
    const page = sessions.chair.page
    await openDistributions(page)
    await expect(page.getByTestId('distribution-open-button')).toBeVisible({ timeout: 30_000 })
    await page.getByTestId('distribution-open-button').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText('外部へ伝達する')).toBeVisible()

    // 件名未入力 → 送信されずエラー
    let posts = 0
    page.on('request', (r) => { if (r.method() === 'POST' && r.url().includes('/distributions')) posts++ })
    await page.getByTestId('distribution-submit-button').click()
    await expect(page.getByTestId('distribution-form-error')).toHaveText('必須項目を入力してください')
    expect(posts).toBe(0)

    const title = `UI配信-${RUN_TAG}`
    await page.getByTestId('distribution-title-input').fill(title)
    await page.getByTestId('distribution-body-input').fill(`UI本文-${RUN_TAG}`)
    page.once('dialog', (d) => { expect(d.message()).toContain('この内容で伝達します'); void d.accept() })
    await page.getByTestId('distribution-submit-button').click()
    await expect(page.getByText('伝達しました').first()).toBeVisible({ timeout: 20_000 })
    await expect(dialog).toBeHidden()
    expect(posts).toBe(1)

    // 履歴に新しい行（先頭）
    const row = page.locator('[data-testid^="distribution-row-"]', { hasText: title })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText(`UI本文-${RUN_TAG}`)
    await expect(page.locator('[data-testid^="distribution-row-"]').first()).toContainText(title)
    // 永続化（API 補助=確認のみ）
    const list = await listDistributions('chair')
    expect(list.status).toBe(200)
    expect(JSON.stringify(list.body)).toContain(title)

    // 確認ダイアログでキャンセルなら配信されない
    await page.getByTestId('distribution-open-button').click()
    await page.getByTestId('distribution-title-input').fill(`キャンセル-${RUN_TAG}`)
    page.once('dialog', (d) => { void d.dismiss() })
    await page.getByTestId('distribution-submit-button').click()
    await page.waitForTimeout(800)
    expect(posts).toBe(1)
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toBeHidden()
    await page.screenshot({ path: `${EVIDENCE}/02-after-distribute.png` })
  })

  // ------------------------------------------------------------------ AC2 REQUIRED の期限
  test('CD-E2E-03 確認モード REQUIRED: 期限必須・過去日時は拒否（deadline_past）・未来なら配信され受信者に届く', async () => {
    const page = sessions.chair.page
    await openDistributions(page)
    await page.getByTestId('distribution-open-button').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    let posts = 0
    page.on('request', (r) => { if (r.method() === 'POST' && r.url().includes('/distributions')) posts++ })

    const title = `REQ配信-${RUN_TAG}`
    const pendingIds = async (who: Who) => {
      const r = await api(sessions[who].page.request, 'get', '/api/v1/me/confirmable-notifications/pending?size=100', sessions[who].token)
      expect(r.status, r.text).toBe(200)
      return (r.body.data as { id: number }[]).map((x) => x.id)
    }
    const memberBefore = await pendingIds('member')
    const orgOnlyBefore = await pendingIds('orgOnly')
    await page.getByTestId('distribution-title-input').fill(title)
    // 確認ボタン Select を「必須」へ
    await dialog.locator('.p-select').nth(1).click()
    await page.getByRole('option', { name: '必須' }).click()
    const deadline = page.locator('#distribution-deadline-input')
    await expect(deadline).toBeVisible()

    // 期限未入力 → 必須エラー・送信なし
    await page.getByTestId('distribution-submit-button').click()
    await expect(page.getByTestId('distribution-form-error')).toHaveText('必須項目を入力してください')
    expect(posts).toBe(0)

    // カレンダーから日付を選ぶ（actionable な UI 操作）。pick(0)=今日（時刻は現在以前＝過去扱い）、pick(1)=明日。
    const pickDay = async (offset: number) => {
      await deadline.click()
      const panel = page.locator('.p-datepicker-panel').first()
      await expect(panel).toBeVisible()
      const cells = panel.locator('table.p-datepicker-day-view td')
      const todayIdx = await cells.evaluateAll((els) => els.findIndex((el) => el.classList.contains('p-datepicker-today')))
      expect(todayIdx).toBeGreaterThanOrEqual(0)
      await cells.nth(todayIdx + offset).locator('span').first().click()
      await page.getByText('外部へ伝達する').click() // パネルを閉じる（ダイアログ見出しをクリック）
      await expect(panel).toBeHidden()
      test.info().annotations.push({ type: `deadline-pick-${offset}`, description: await deadline.inputValue() })
    }
    await pickDay(0)
    await page.getByTestId('distribution-submit-button').click()
    await expect(page.getByTestId('distribution-form-error')).toHaveText('確認期限は現在より後の日時を指定してください')
    expect(posts).toBe(0)
    await page.setViewportSize({ width: 1280, height: 1100 })
    await page.screenshot({ path: `${EVIDENCE}/03-deadline-past-error.png` })
    await page.setViewportSize({ width: 1280, height: 720 })

    // 未来の日時（明日）→ 配信される
    await pickDay(1)
    page.once('dialog', (d) => { void d.accept() })
    await page.getByTestId('distribution-submit-button').click()
    await expect(page.getByText('伝達しました').first()).toBeVisible({ timeout: 20_000 })
    expect(posts).toBe(1)
    const row = page.locator('[data-testid^="distribution-row-"]', { hasText: title })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('確認モード: 必須')

    // 届く側: 履歴に確認通知 ID が付き、委員会メンバー(MEMBER)の確認待ち一覧が1件増える。委員会外の組織メンバーは増えない
    const logs = await listDistributions('chair')
    const mine = (logs.body.data.content as { customTitle: string; confirmableNotificationId: number | null }[]).find((x) => x.customTitle === title)
    expect(mine?.confirmableNotificationId, '確認通知が生成されていること').toBeTruthy()
    const memberAfter = await pendingIds('member')
    expect(memberAfter.length - memberBefore.length).toBe(1)
    expect(await pendingIds('orgOnly')).toEqual(orgOnlyBefore)
  })

  // ------------------------------------------------------------------ AC2 連打・送信中
  test('CD-E2E-04 連打しても配信は1回だけ・送信中はダイアログを閉じられない', async () => {
    const page = sessions.chair.page
    await openDistributions(page)
    await page.getByTestId('distribution-open-button').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    const title = `連打-${RUN_TAG}`
    await page.getByTestId('distribution-title-input').fill(title)

    const posts: string[] = []
    page.on('request', (r) => { if (r.method() === 'POST' && r.url().includes('/distributions')) posts.push(r.url()) })
    // 送信中の状態を観測できるよう BE 呼び出しを遅延させる
    await page.route('**/api/v1/committees/*/distributions', async (route) => {
      if (route.request().method() === 'POST') await new Promise((r) => setTimeout(r, 2500))
      await route.continue()
    })
    page.on('dialog', (d) => { void d.accept() })
    // 同一 JS タスク内で click を2回発火
    await page.getByTestId('distribution-submit-button').evaluate((el) => {
      ;(el as HTMLElement).click()
      ;(el as HTMLElement).click()
    })
    await page.waitForTimeout(500)
    // 送信中: 閉じるボタンが無い・Escape で閉じない・キャンセルボタンが無効
    await expect(dialog).toBeVisible()
    await expect(dialog.locator('.p-dialog-close-button')).toHaveCount(0)
    await page.keyboard.press('Escape')
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'キャンセル' })).toBeDisabled()
    await page.screenshot({ path: `${EVIDENCE}/04-submitting.png` })

    await expect(page.getByText('伝達しました').first()).toBeVisible({ timeout: 20_000 })
    await expect(dialog).toBeHidden()
    expect(posts).toHaveLength(1)
    await expect(page.locator('[data-testid^="distribution-row-"]', { hasText: title })).toHaveCount(1)
    const list = await listDistributions('chair')
    const count = (list.body.data.content as { customTitle: string }[]).filter((x) => x.customTitle === title).length
    expect(count).toBe(1)
    await page.unroute('**/api/v1/committees/*/distributions')
    page.removeAllListeners('dialog')
  })

  // ------------------------------------------------------------------ ロール横断
  test('CD-E2E-05 SECRETARY: 配信ボタンが出て配信できる', async () => {
    const page = sessions.secretary.page
    await openDistributions(page)
    await expect(page.getByTestId('distribution-open-button')).toBeVisible({ timeout: 30_000 })
    await page.getByTestId('distribution-open-button').click()
    const title = `書記配信-${RUN_TAG}`
    await page.getByTestId('distribution-title-input').fill(title)
    page.once('dialog', (d) => { void d.accept() })
    await page.getByTestId('distribution-submit-button').click()
    await expect(page.getByText('伝達しました').first()).toBeVisible({ timeout: 20_000 })
    await expect(page.locator('[data-testid^="distribution-row-"]', { hasText: title })).toHaveCount(1)
  })

  test('CD-E2E-06 一般委員(MEMBER): 履歴は見えるが配信ボタンが出ない。直接 POST も 403', async () => {
    const page = sessions.member.page
    await openDistributions(page)
    await expect(page.getByTestId(`distribution-row-${seededTitleLogId}`)).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('distribution-open-button')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '伝達する' })).toHaveCount(0)
    await page.screenshot({ path: `${EVIDENCE}/05-member-no-button.png` })
    const res = await api(page.request, 'post', `/api/v1/committees/${committeeId}/distributions`, sessions.member.token, {
      contentType: 'CUSTOM_MESSAGE', customTitle: `MEMBER直叩き-${RUN_TAG}`, targetScope: 'COMMITTEE_ONLY', announcementEnabled: false, confirmationMode: 'NONE',
    })
    expect(res.status, res.text).toBe(403)
    const after = await listDistributions('chair')
    expect(JSON.stringify(after.body)).not.toContain('MEMBER直叩き')
  })

  for (const who of ['orgOnly', 'outsider'] as const) {
    test(`CD-E2E-07-${who} 委員会に属さない${who === 'orgOnly' ? '組織メンバー' : '他組織ユーザー'}: URL 直打ちで履歴にも配信にも到達できない`, async () => {
      const page = sessions[who].page
      const gets: number[] = []
      page.on('response', (r) => { if (r.url().includes(`/committees/${committeeId}/distributions`) && r.request().method() === 'GET') gets.push(r.status()) })
      await openDistributions(page)
      await expect(page.getByTestId('committee-distributions-error-state')).toBeVisible({ timeout: 30_000 })
      const body = await page.locator('body').innerText()
      test.info().annotations.push({ type: 'screen-text', description: body.replace(/\s+/g, ' ').slice(0, 300) })
      await expect(page.getByTestId('distribution-open-button')).toHaveCount(0)
      await expect(page.locator('[data-testid^="distribution-row-"]')).toHaveCount(0)
      expect(body).not.toContain(RUN_TAG)
      expect(gets.some((s) => s === 403 || s === 404), `GET 履歴のステータス: ${gets}`).toBe(true)
      await page.screenshot({ path: `${EVIDENCE}/06-${who}-direct-url.png` })
      // 権限なしで API を直接叩いても拒否
      const get = await api(page.request, 'get', `/api/v1/committees/${committeeId}/distributions`, sessions[who].token)
      expect([403, 404], get.text).toContain(get.status)
      const post = await api(page.request, 'post', `/api/v1/committees/${committeeId}/distributions`, sessions[who].token, {
        contentType: 'CUSTOM_MESSAGE', customTitle: `${who}直叩き-${RUN_TAG}`, targetScope: 'COMMITTEE_ONLY', announcementEnabled: false, confirmationMode: 'NONE',
      })
      expect([403, 404], post.text).toContain(post.status)
      const after = await listDistributions('chair')
      expect(JSON.stringify(after.body)).not.toContain(`${who}直叩き`)
    })
  }

  // ------------------------------------------------------------------ 配信の宛先（範囲）
  test('CD-E2E-09 範囲「組織全体」で配信すると、組織のお知らせとして委員会外の組織メンバーに届く（設計書 F04.10: PARENT_ORG → ORGANIZATION スコープのお知らせ）', async () => {
    // 現状の BE は targetScope を履歴に保存するだけで、お知らせを常に委員会スコープで生成し、組織へは出さない（実装欠陥）。
    // 欠陥が直るまで test.fail() で「期待どおり届かない」ことを固定し、直ったら赤になって気付けるようにする。
    test.fail(true, 'BE が targetScope=PARENT_ORG を無視し、組織スコープのお知らせを生成しない（CommitteeDistributionService）')
    const page = sessions.chair.page
    await openDistributions(page)
    await page.getByTestId('distribution-open-button').click()
    const dialog = page.getByRole('dialog')
    const title = `組織全体-${RUN_TAG}`
    await page.getByTestId('distribution-title-input').fill(title)
    await dialog.locator('.p-select').nth(0).click()
    await page.getByRole('option', { name: '組織全体', exact: true }).click()
    page.once('dialog', (d) => { void d.accept() })
    await page.getByTestId('distribution-submit-button').click()
    await expect(page.getByText('伝達しました').first()).toBeVisible({ timeout: 20_000 })
    const row = page.locator('[data-testid^="distribution-row-"]', { hasText: title })
    await expect(row).toContainText('組織全体')

    const feeds = await api(sessions.orgOnly.page.request, 'get', `/api/v1/organizations/${orgA.id}/announcements?size=50`, sessions.orgOnly.token)
    test.info().annotations.push({ type: 'org-announcements-for-org-member', description: `status=${feeds.status} body=${feeds.text.slice(0, 300)}` })
    expect(feeds.status, feeds.text).toBe(200)
    expect(feeds.text, '組織メンバーのお知らせ一覧に配信が出ること').toContain(title)
  })

  test('CD-E2E-08 未ログインでの URL 直打ちはログイン画面へ', async ({ browser }) => {
    const ctx = await browser.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost:3001', locale: 'ja-JP' })
    const page = await ctx.newPage()
    await page.goto(`/committees/${committeeId}/distributions`)
    await expect(page).toHaveURL(/\/login/, { timeout: 30_000 })
    await ctx.close()
  })
})
