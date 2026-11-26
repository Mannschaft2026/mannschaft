import { existsSync, mkdirSync, readFileSync, writeFileSync, appendFileSync } from 'node:fs'
import path from 'node:path'
import { test, expect, type Page } from '@playwright/test'
import {
  API, FE, createActor, createOrg, createTeam, joinAsMember, must, apiAs, openAs, gotoAndHydrate, RUN,
  type Actor, type Scope,
} from './helpers/n1p1-world'
import { OWN_LEDGER, WORLD_FILE } from './helpers/cmp0900-global-teardown'

/**
 * 戦役 CMP-261118-0900「大会連絡スペースの参加者向け導線」の実機E2E（設計書 F08.7.1 01_communication.md §10・AC-P01〜P36）。
 *
 * 確かめること（画面操作は API で代替しない。API はログイン・前提データ作成・直接呼びの対照・後始末のみ）:
 * - 参加チーム ADMIN（POST）: チームの大会一覧カード → 連絡スペースページ。掲示板にスレッド作成・返信、チャット送信が画面に反映され、再読込後も残る。
 * - 参加チーム MEMBER（VIEW）: 閲覧できるが新規スレッド・返信欄・チャット送信/スレッド返信 UI が出ない。API 直呼びの返信は 403 TOUR_031。
 * - 非参加チームの利用者・部外者・未ログイン: カードに導線が無い／URL 直打ちで「利用できません」表示（404 TOUR_029）／ログイン画面へ。
 * - 辞退（WITHDRAWN）後の再読込は「利用できません」。
 * - 主催者 ADMIN: 主催者側画面（TournamentContactPanel へ寄せた refactor）で掲示板・チャットが従来どおり動く。
 *
 * 実行: cd frontend && API_BASE_URL=http://localhost:8081 BASE_URL=http://localhost:3001 \
 *   npx playwright test --config playwright-cmp0900.config.ts
 * 後始末: 組織・チームを台帳（cmp0900-assets.json）へ記録し globalTeardown が DELETE（大会・スレッド等は従属して消える）。
 *   利用者は残る（テスト専用ドメイン）。直接 SQL は createActor の users.status 有効化のみ。
 */
const EVID = process.env.EVIDENCE_DIR || path.resolve('test-results', 'cmp0900-evidence')
mkdirSync(EVID, { recursive: true })
const LOG = path.join(EVID, 'responses.jsonl')

interface World {
  orgAdmin: Actor; a1: Actor; a3: Actor; c1: Actor; z: Actor; w1: Actor
  orgO: Scope; teamA: Scope; teamC: Scope; teamW: Scope
  tId: number; d1: number; pW: number
  tName: string
  bulletinRef: number; chatRef: number
  thSeed: string; thSeedId: number
  chatSeed: string; chatSeedId: number
}
let W: World

function log(entry: Record<string, unknown>) {
  appendFileSync(LOG, JSON.stringify({ at: new Date().toISOString(), ...entry }) + '\n')
}
async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(EVID, `${name}.png`), fullPage: true })
}
const commUrl = (slug: string, tId: number) => `/teams/${slug}/tournaments/${tId}/communication`
const isParticipantList = (r: { url(): string; request(): { method(): string } }) =>
  /\/api\/v1\/teams\/[^/]+\/tournaments\/\d+\/contact-spaces$/.test(new URL(r.url()).pathname) && r.request().method() === 'GET'

test.beforeAll(async () => {
  test.setTimeout(600_000)
  if (existsSync(WORLD_FILE)) { W = JSON.parse(readFileSync(WORLD_FILE, 'utf8')); await openTournament(); return }
  await buildWorld()
  await openTournament()
})

/**
 * 大会を DRAFT から OPEN にする（前提操作）。DRAFT の大会は F00 の可視性判定（TournamentStatusMapper）で
 * 参加チームの会員にも見えず、チームの大会履歴（= 参加大会カード）に出ない。実運用の「公開された大会」に揃える。
 * 既に OPEN 以降なら変更しない。
 */
async function openTournament() {
  const cur = await must(W.orgAdmin, 'GET', `/api/v1/organizations/${W.orgO.numericId}/tournaments/${W.tId}`)
  const status = cur.data?.structure?.status
  log({ step: 'tournament status before', status })
  expect(status, '大会応答の structure.status が読める').toBeTruthy()
  if (status !== 'DRAFT') return
  await must(W.orgAdmin, 'PATCH', `/api/v1/organizations/${W.orgO.numericId}/tournaments/${W.tId}/status`, { status: 'OPEN' })
}

async function buildWorld() {
  const orgAdmin = await createActor('og')
  const a1 = await createActor('a1')
  const a3 = await createActor('a3')
  const c1 = await createActor('c1')
  const z = await createActor('zz')
  const w1 = await createActor('w1')
  const orgO = await createOrg(orgAdmin, `0900主催${RUN}`, 'PUBLIC', OWN_LEDGER)
  const teamA = await createTeam(a1, `0900参加A${RUN}`, 'PUBLIC', OWN_LEDGER)
  await joinAsMember('teams', a1, teamA.slug, a3)
  const teamC = await createTeam(c1, `0900非参加C${RUN}`, 'PUBLIC', OWN_LEDGER)
  const teamW = await createTeam(w1, `0900辞退W${RUN}`, 'PUBLIC', OWN_LEDGER)
  for (const [t, admin] of [[teamA, a1], [teamC, c1], [teamW, w1]] as const) {
    const invite = await must(orgAdmin, 'POST', `/api/v1/organizations/${orgO.slug}/team-invites`, { teamSlug: t.slug })
    await must(admin, 'POST', `/api/v1/teams/${t.slug}/org-invites/${invite.data.id}/accept`)
  }
  const tName = `0900大会-${RUN}`
  const t = await must(orgAdmin, 'POST', `/api/v1/organizations/${orgO.numericId}/tournaments`, { name: tName, format: 'LEAGUE', sport: 'SOCCER', visibility: 'PUBLIC' })
  const tId = Number(t.data?.id ?? t.data?.content?.id)
  const d1 = Number((await must(orgAdmin, 'POST', `/api/v1/organizations/${orgO.numericId}/tournaments/${tId}/divisions`, { name: `D1-${RUN}`, level: 1 })).data.id)
  const addP = async (team: Scope) => Number((await must(orgAdmin, 'POST', `/api/v1/organizations/${orgO.numericId}/tournaments/${tId}/divisions/${d1}/participants`, { teamId: team.numericId })).data.id)
  await addP(teamA)
  const pW = await addP(teamW)
  const spaces = (await must(orgAdmin, 'GET', `/api/v1/tournaments/${tId}/contact-spaces`)).data as Array<{ scopeType: string; spaceKind: string; refId: number }>
  log({ step: 'organizer spaces', spaces })
  const bull = spaces.find(s => s.scopeType === 'TOURNAMENT' && s.spaceKind === 'BULLETIN')!
  const chat = spaces.find(s => s.scopeType === 'TOURNAMENT' && s.spaceKind === 'CHAT')!
  // 閲覧のみ（a3）が開いて確かめるための既存スレッド（主催 ADMIN が作成・未ロック）と、スレッド返信つきのチャットメッセージ
  const thSeed = `seedTh-${RUN}`
  const th = await must(orgAdmin, 'POST', '/api/v1/bulletin/threads', { title: thSeed, body: `${thSeed} の本文`, priority: 'INFO', categoryId: bull.refId, scopeType: 'TOURNAMENT', scopeId: tId })
  const chatSeed = `seedChat-${RUN}`
  const msg = await must(a1, 'POST', `/api/v1/chat/channels/${chat.refId}/messages`, { body: chatSeed })
  await must(a1, 'POST', `/api/v1/chat/channels/${chat.refId}/messages`, { body: `seedChatReply-${RUN}`, parentId: msg.data.id })
  W = {
    orgAdmin, a1, a3, c1, z, w1, orgO, teamA, teamC, teamW, tId, d1, pW, tName,
    bulletinRef: bull.refId, chatRef: chat.refId,
    thSeed, thSeedId: Number(th.data.id), chatSeed, chatSeedId: Number(msg.data.id),
  }
  mkdirSync(path.dirname(WORLD_FILE), { recursive: true })
  writeFileSync(WORLD_FILE, JSON.stringify(W, null, 2))
}

test('00 前提: 世界が作られている', async () => {
  expect(W.tId).toBeGreaterThan(0)
  log({ step: 'world', tId: W.tId, teamA: W.teamA.slug, teamC: W.teamC.slug, orgO: W.orgO.slug, thSeedId: W.thSeedId })
})

// ============================================================ 1. 参加チーム ADMIN（POST）
test('10 [AC-P01/P23/P24/P25/P28] 参加チーム ADMIN: カード→連絡スペース、投稿・返信・チャット送信が反映され再読込後も残る', async ({ browser }) => {
  const { context, page } = await openAs(browser, W.a1)
  // 大会一覧カード（チーム側）
  await gotoAndHydrate(page, `/teams/${W.teamA.slug}/tournaments`)
  const link = page.getByRole('link', { name: '連絡スペースを開く' }).first()
  await expect(link).toBeVisible({ timeout: 60_000 })
  await shot(page, '10-a1-tournament-card')
  const list = page.waitForResponse(isParticipantList)
  await link.click()
  const res = await list
  expect(res.status()).toBe(200)
  const body = await res.json()
  log({ step: 'a1 list', url: page.url(), levels: body.data.spaces.map((s: { scopeType: string; spaceKind: string; accessLevel: string }) => `${s.scopeType}/${s.spaceKind}=${s.accessLevel}`) })
  expect(body.data.spaces.length).toBe(4)
  expect(body.data.spaces.every((s: { accessLevel: string }) => s.accessLevel === 'POST')).toBe(true)
  await expect(page).toHaveURL(new RegExp(`/teams/${W.teamA.slug}/tournaments/${W.tId}/communication`))
  await expect(page.getByRole('heading', { name: W.tName })).toBeVisible()
  await expect(page.getByRole('button', { name: '大会全体' })).toBeVisible()
  await expect(page.getByRole('button', { name: `D1-${RUN}` })).toBeVisible()
  await expect(page.getByRole('button', { name: '掲示板' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'チャット' })).toBeVisible()
  await expect(page.getByText(W.thSeed).first()).toBeVisible()
  await shot(page, '10-a1-communication-bulletin')
  // スレッド作成（POST 権限）
  const title = `a1新規-${RUN}`
  await page.getByRole('button', { name: '新規スレッド' }).click()
  await page.getByPlaceholder('スレッドのタイトル').fill(title)
  await page.getByPlaceholder('本文を入力...').fill('a1 が画面から投稿した本文')
  const created = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/bulletin/threads' && r.request().method() === 'POST')
  await page.getByRole('button', { name: '投稿', exact: true }).click()
  expect([200, 201]).toContain((await created).status())
  await expect(page.getByText(title).first()).toBeVisible()
  // 再読込後も残る（作成→保持）
  await gotoAndHydrate(page, commUrl(W.teamA.slug, W.tId))
  await expect(page.getByText(title).first()).toBeVisible()
  // 返信（更新経路）。管理操作（ピン・ロック等）は参加者画面に出ない（AC-P36）
  await page.getByText(title).first().click()
  await expect(page.getByRole('button', { name: 'ピン留め' })).toHaveCount(0)
  await page.getByPlaceholder('返信を入力...').fill('a1 の返信')
  const rep = page.waitForResponse(r => /\/threads\/\d+\/replies$/.test(new URL(r.url()).pathname) && r.request().method() === 'POST')
  await page.getByRole('button', { name: '返信', exact: true }).click()
  expect([200, 201]).toContain((await rep).status())
  await expect(page.getByText('a1 の返信')).toBeVisible()
  await shot(page, '10-a1-thread-replied')
  await gotoAndHydrate(page, commUrl(W.teamA.slug, W.tId))
  await page.getByText(title).first().click()
  await expect(page.getByText('a1 の返信')).toBeVisible()
  // チャット送信
  await page.getByRole('button', { name: 'チャット' }).click()
  const input = page.getByTestId('team-chat-input')
  await expect(input).toBeVisible({ timeout: 60_000 })
  await expect(input).toBeEnabled()
  const chatText = `a1チャット-${RUN}`
  await input.fill(chatText)
  const sent = page.waitForResponse(r => new URL(r.url()).pathname === `/api/v1/chat/channels/${W.chatRef}/messages` && r.request().method() === 'POST')
  await page.getByTestId('chat-send-btn').click()
  expect([200, 201]).toContain((await sent).status())
  await expect(page.getByText(chatText).first()).toBeVisible()
  await shot(page, '10-a1-chat-sent')
  await gotoAndHydrate(page, commUrl(W.teamA.slug, W.tId))
  await page.getByRole('button', { name: 'チャット' }).click()
  await expect(page.getByText(chatText).first()).toBeVisible({ timeout: 60_000 })
  await context.close()
})

// ============================================================ 2. 参加チーム MEMBER（VIEW）
test('11 [AC-P03/P24/P25/P28] 参加チーム MEMBER: 閲覧のみ（新規作成・返信・送信・スレッド返信の UI が出ない）', async ({ browser }) => {
  const { context, page } = await openAs(browser, W.a3)
  const sent: string[] = []
  page.on('request', (r) => { if (r.method() === 'POST' && /\/(replies|messages)$/.test(new URL(r.url()).pathname)) sent.push(new URL(r.url()).pathname) })
  await gotoAndHydrate(page, `/teams/${W.teamA.slug}/tournaments`)
  await expect(page.getByRole('link', { name: '連絡スペースを開く' }).first()).toBeVisible({ timeout: 60_000 })
  const list = page.waitForResponse(isParticipantList)
  await page.getByRole('link', { name: '連絡スペースを開く' }).first().click()
  const res = await list
  expect(res.status()).toBe(200)
  const body = await res.json()
  log({ step: 'a3 list', levels: body.data.spaces.map((s: { scopeType: string; spaceKind: string; accessLevel: string }) => `${s.scopeType}/${s.spaceKind}=${s.accessLevel}`) })
  expect(body.data.spaces.length).toBe(4)
  expect(body.data.spaces.every((s: { accessLevel: string }) => s.accessLevel === 'VIEW')).toBe(true)
  await expect(page.getByText(W.thSeed).first()).toBeVisible()
  await expect(page.getByText('閲覧のみです。投稿できるのはチームの代表・副代表と主催者です')).toBeVisible()
  expect(await page.getByRole('button', { name: '新規スレッド' }).count()).toBe(0)
  await shot(page, '11-a3-bulletin-viewonly')
  // スレッドを開く: 返信欄もロック表示も出ない
  await page.getByText(W.thSeed).first().click()
  await expect(page.getByText(`${W.thSeed} の本文`).first()).toBeVisible()
  await expect(page.getByPlaceholder('返信を入力...')).toHaveCount(0)
  await expect(page.getByText('このスレッドはロックされています')).toHaveCount(0)
  await shot(page, '11-a3-thread-no-reply')
  // API 直呼びの返信は 403 TOUR_031
  const direct = await apiAs(W.a3, 'POST', `/api/v1/bulletin/threads/${W.thSeedId}/replies`, { body: 'a3 直呼び' })
  log({ step: 'a3 direct reply', status: direct.status, body: JSON.stringify(direct.body).slice(0, 300) })
  expect(direct.status).toBe(403)
  expect(JSON.stringify(direct.body)).toContain('TOUR_031')
  // チャット: 送信入力は無効、スレッドを開いても返信入力が無い
  await page.getByRole('button', { name: 'チャット' }).click()
  const input = page.getByTestId('team-chat-input')
  await expect(input).toBeVisible({ timeout: 60_000 })
  await expect(input).toBeDisabled()
  await expect(page.getByText(W.chatSeed).first()).toBeVisible()
  await shot(page, '11-a3-chat-disabled')
  await page.getByTestId('thread-reply-btn').first().click()
  await expect(page.getByText(`seedChatReply-${RUN}`).first()).toBeVisible()
  await expect(page.getByTestId('thread-reply-input')).toHaveCount(0)
  await page.keyboard.press('Enter')
  await shot(page, '11-a3-chat-thread-no-reply')
  expect(sent, 'a3 の画面操作で投稿系リクエストが出ていない').toEqual([])
  // 送信 API 直呼びも 403
  const directChat = await apiAs(W.a3, 'POST', `/api/v1/chat/channels/${W.chatRef}/messages`, { body: 'a3 直呼びチャット' })
  log({ step: 'a3 direct chat', status: directChat.status, body: JSON.stringify(directChat.body).slice(0, 300) })
  await context.close()
})

// ============================================================ 3. 非参加・部外者・未ログイン
async function expectUnavailable(page: Page, url: string, name: string) {
  const list = page.waitForResponse(isParticipantList, { timeout: 120_000 })
  await page.goto(`${FE}${url}`, { waitUntil: 'commit' })
  const res = await list
  const text = await res.text()
  const status = res.status()
  await expect(page.getByText('この大会の連絡スペースを利用できません')).toBeVisible({ timeout: 60_000 })
  const shown = (await page.locator('body').innerText()).slice(0, 400)
  log({ step: `unavailable ${name}`, url: page.url(), status, body: text.slice(0, 300), shown })
  expect(status).toBe(404)
  expect(text).toContain('TOUR_029')
  expect(shown).not.toContain(W.thSeed)
  await shot(page, `12-${name}`)
}

test('12 [AC-P07/P09/P26] 非参加チームの利用者 c1: カードに導線が無く、URL 直打ちは「利用できません」', async ({ browser }) => {
  const { context, page } = await openAs(browser, W.c1)
  // 自チーム C の大会一覧に連絡スペースの導線は出ない
  await gotoAndHydrate(page, `/teams/${W.teamC.slug}/tournaments`)
  await page.waitForTimeout(4000)
  expect(await page.getByRole('link', { name: '連絡スペースを開く' }).count()).toBe(0)
  await shot(page, '12-c1-own-team-tournaments')
  // 自チーム C の URL 直打ち（C は T に不参加）
  await expectUnavailable(page, commUrl(W.teamC.slug, W.tId), 'c1-own-team')
  // 他チーム A の URL 直打ち（c1 は A の会員でない）
  await expectUnavailable(page, commUrl(W.teamA.slug, W.tId), 'c1-other-team-A')
  await context.close()
})

test('13 [AC-P09/P26] 部外者 z（どのチームにも属さない）: A の URL 直打ちは「利用できません」', async ({ browser }) => {
  const { context, page } = await openAs(browser, W.z)
  await expectUnavailable(page, commUrl(W.teamA.slug, W.tId), 'z-teamA')
  await context.close()
})

test('14 [AC-P10/P27] 未ログイン: URL 直打ちはログイン画面へ', async ({ browser }) => {
  const context = await browser.newContext({ baseURL: FE, locale: 'ja-JP', viewport: { width: 1280, height: 900 } })
  const page = await context.newPage()
  await page.goto(`${FE}${commUrl(W.teamA.slug, W.tId)}`, { waitUntil: 'commit' })
  await page.waitForURL(/\/login/, { timeout: 120_000 })
  log({ step: 'unauth', url: page.url() })
  expect(page.url()).toContain('/login')
  await shot(page, '14-unauth-login')
  const noAuth = await fetch(`${API}/api/v1/teams/${W.teamA.slug}/tournaments/${W.tId}/contact-spaces`)
  log({ step: 'unauth api', status: noAuth.status })
  expect(noAuth.status).toBe(401)
  await context.close()
})

// ============================================================ 4. 辞退（WITHDRAWN）
test('15 [AC-P29] 辞退チームの代表 w1: 開ける→辞退→再読込で「利用できません」', async ({ browser }) => {
  const { context, page } = await openAs(browser, W.w1)
  const list = page.waitForResponse(isParticipantList, { timeout: 120_000 })
  await gotoAndHydrate(page, commUrl(W.teamW.slug, W.tId))
  expect((await list).status()).toBe(200)
  await expect(page.getByText(W.thSeed).first()).toBeVisible()
  await shot(page, '15-w1-before-withdraw')
  // 主催者が辞退扱いにする（前提操作は API）
  await must(W.orgAdmin, 'PATCH', `/api/v1/organizations/${W.orgO.numericId}/tournaments/${W.tId}/divisions/${W.d1}/participants/${W.pW}`, { status: 'WITHDRAWN' })
  await expectUnavailable(page, commUrl(W.teamW.slug, W.tId), 'w1-withdrawn')
  await context.close()
})

// ============================================================ 5. 主催者（退行なし）
test('16 [AC-P22/P31] 主催者 ADMIN: 主催者側画面で掲示板・チャットが従来どおり動く', async ({ browser }) => {
  const { context, page } = await openAs(browser, W.orgAdmin)
  const spaces = page.waitForResponse(r => /\/tournaments\/\d+\/contact-spaces$/.test(new URL(r.url()).pathname) && !r.url().includes('/teams/') && r.request().method() === 'GET')
  await gotoAndHydrate(page, `/organizations/${W.orgO.slug}/tournaments/${W.tId}/communication`)
  expect((await spaces).status()).toBe(200)
  await expect(page.getByText(W.thSeed).first()).toBeVisible()
  await expect(page.getByRole('switch').first()).toBeVisible()
  await shot(page, '16-org-bulletin')
  const title = `org新規-${RUN}`
  await page.getByRole('button', { name: '新規スレッド' }).click()
  await page.getByPlaceholder('スレッドのタイトル').fill(title)
  await page.getByPlaceholder('本文を入力...').fill('主催者が画面から投稿')
  const created = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/bulletin/threads' && r.request().method() === 'POST')
  await page.getByRole('button', { name: '投稿', exact: true }).click()
  expect([200, 201]).toContain((await created).status())
  await expect(page.getByText(title).first()).toBeVisible()
  await page.getByText(title).first().click()
  await page.getByPlaceholder('返信を入力...').fill('主催者の返信')
  const rep = page.waitForResponse(r => /\/threads\/\d+\/replies$/.test(new URL(r.url()).pathname) && r.request().method() === 'POST')
  await page.getByRole('button', { name: '返信', exact: true }).click()
  expect([200, 201]).toContain((await rep).status())
  await expect(page.getByText('主催者の返信')).toBeVisible()
  const pin = page.waitForResponse(r => /\/threads\/\d+\/pin$/.test(new URL(r.url()).pathname))
  await page.getByRole('button', { name: 'ピン留め' }).click()
  expect((await pin).status()).toBe(200)
  await gotoAndHydrate(page, `/organizations/${W.orgO.slug}/tournaments/${W.tId}/communication`)
  await page.getByText(title).first().click()
  await expect(page.getByRole('button', { name: 'ピン解除' })).toBeVisible()
  await expect(page.getByText('主催者の返信')).toBeVisible()
  await shot(page, '16-org-thread-pinned')
  await page.getByRole('button', { name: 'チャット' }).click()
  const input = page.getByTestId('team-chat-input')
  await expect(input).toBeVisible({ timeout: 60_000 })
  await expect(input).toBeEnabled()
  const chatText = `org チャット-${RUN}`
  await input.fill(chatText)
  const sent = page.waitForResponse(r => new URL(r.url()).pathname === `/api/v1/chat/channels/${W.chatRef}/messages` && r.request().method() === 'POST')
  await page.getByTestId('chat-send-btn').click()
  expect([200, 201]).toContain((await sent).status())
  await expect(page.getByText(chatText).first()).toBeVisible()
  await shot(page, '16-org-chat-sent')
  await context.close()
})
