import { execFileSync } from 'node:child_process'
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { test, expect, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import {
  API, FE, PASSWORD, createOrg, createTeam, gotoAndHydrate, must, recordAsset, sql, ROLE_ID,
  type Actor, type Scope,
} from './helpers/n1p1-world'
import { OWN_LEDGER, WORLD_FILE } from './helpers/cmp0722-global-teardown'

/**
 * 戦役 CMP-261116-0722「全体検索 F04.6 の作り直し」実機E2E（PR-1〜8 マージ後の main）。
 *
 * 確かめること（docs/features/F04.6_search.md §6 認可・§7 セキュリティ・FE の入口の節が正本）:
 * - 権限あり: 組織A/チームA1 の ADMIN・MEMBER が、自分の見てよい投稿・メッセージ・スレッド・記事・ファイル・
 *   ユーザー・チーム・組織・活動記録を各種別タブで見られ、タブの件数とリスト件数が一致し、結果から詳細へ到達できる。
 * - 権限なし: MEMBER に管理者限定記事・管理者限定フォルダのファイルが出ない。課金ゲート記事は未払いに出ず、
 *   支払い済みに出る。DRAFT の活動記録は誰にも出ない。SUPPORTER にメンバー限定・非参加チャンネルが出ない。
 * - 他組織: 組織Bだけに所属する者に、組織A側のデータが結果・件数・サジェストのどれにも出ない。
 *   scopeType/scopeId の URL 直打ち（A の ID / 実在しない ID）で表示が同じ。A の詳細 URL 直打ちで中身が出ない。
 * - ヘッダー検索欄（lg 以上の入力欄・サジェスト・矢印/Enter/Esc・IME 変換中の Enter）とモバイルの虫眼鏡。
 * - URL 同期（q・type が URL に残り、再読込・戻る/進むで復元）。
 *
 * 対象操作（検索）は必ず画面で行う。API を使うのはログイン・前提データ作成・後始末だけ。
 * 実行: cd frontend && npx playwright test --config playwright-cmp0722.config.ts
 *   既定は検証用 BE 8081 / FE 3001。前提データを残して再実行したいときだけ N1P1_KEEP_WORLD=1。
 */

const EVIDENCE_DIR = process.env.EVIDENCE_DIR || path.resolve('test-results', 'cmp0722-evidence')
mkdirSync(EVIDENCE_DIR, { recursive: true })
function log(entry: Record<string, unknown>) {
  appendFileSync(path.join(EVIDENCE_DIR, 'evidence.jsonl'), JSON.stringify({ at: new Date().toISOString(), ...entry }) + '\n')
}
async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(EVIDENCE_DIR, name + '.png'), fullPage: true })
}

type ContentType = 'POST' | 'MESSAGE' | 'THREAD' | 'ARTICLE' | 'FILE' | 'USER' | 'TEAM' | 'ORGANIZATION' | 'ACTIVITY'
const TYPES: ContentType[] = ['POST', 'MESSAGE', 'THREAD', 'ARTICLE', 'FILE', 'USER', 'TEAM', 'ORGANIZATION', 'ACTIVITY']

interface World {
  K: string
  run: string
  actors: Record<'aAdmin' | 'aMember' | 'aSupporter' | 'aPayer' | 'outsider', Actor>
  supporterOk: boolean
  orgA: Scope
  teamA: Scope
  orgB: Scope
  teamB: Scope
  ids: Record<string, number>
}

// ---------- 前提データ ----------
function newRunId() {
  return Date.now().toString(36).slice(-5) + Math.random().toString(36).slice(2, 5)
}

function valkeyDel(key: string): void {
  const args = ['exec', 'mannschaft-valkey', 'valkey-cli', 'DEL', key]
  if (process.platform === 'win32') execFileSync('wsl.exe', ['-e', 'docker', ...args], { stdio: 'pipe', timeout: 60_000 })
  else execFileSync('docker', args, { stdio: 'pipe', timeout: 60_000 })
}

/** WSL2 mirrored 越しの接続断（HTTP 応答前の通信断）だけを再試行する（n1p1-world の netFetch と同じ方針） */
async function fetchRetry(url: string, init?: RequestInit): Promise<Response> {
  let last: unknown
  for (let i = 0; i < 4; i++) {
    try {
      return await fetch(url, init)
    } catch (e) {
      last = e
      await new Promise((r) => setTimeout(r, 1_000 * (i + 1)))
    }
  }
  throw last
}

/** 表示名（= nickname）に固有キーワードと側の印（sidea / sideb）を入れた利用者を新規登録する。 */
async function createSearchActor(K: string, label: string, side: 'sidea' | 'sideb'): Promise<Actor> {
  const run = K.replace('e2e0722zq', '')
  const email = 'e2e-0722-' + label + '-' + run + '@test.mannschaft.local'
  const nickname = K + side + label
  valkeyDel('mannschaft:auth:register_attempt:127.0.0.1')
  const res = await fetchRetry(API + '/api/v1/auth/register', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      email, password: PASSWORD, lastName: '検証', firstName: label, nickname, birth_date: '1990-01-01',
      privacyPolicyAccepted: true, privacyPolicyVersion: '1.1.0', postalCode: '1000001', timezone: 'Asia/Tokyo', locale: 'ja',
    }),
  })
  if (res.status !== 201) throw new Error('利用者登録失敗 ' + label + ': ' + res.status + ' ' + (await res.text()))
  // メール認証トークンは平文を復元できないため、有効化だけは n1p1-world と同じく直更新（本 run のメール・表示名に限定）
  sql("UPDATE users SET status='ACTIVE' WHERE email='" + email + "' AND display_name='" + nickname + "' AND status='PENDING_VERIFICATION';")
  const id = Number(sql("SELECT id FROM users WHERE email='" + email + "';"))
  appendFileSync(path.join(path.dirname(OWN_LEDGER), 'cmp0722-users.txt'), id + '\t' + email + '\n')
  return { label, email, password: PASSWORD, id, nickname }
}

async function joinWithRole(kind: 'organizations' | 'teams', admin: Actor, scopeSlug: string, member: Actor, roleId: number) {
  const token = await must(admin, 'POST', '/api/v1/' + kind + '/' + scopeSlug + '/invite-tokens', { roleId })
  await must(member, 'POST', '/api/v1/invite/' + token.data.token + '/join')
}

async function buildWorld(): Promise<World> {
  const run = newRunId()
  const K = 'e2e0722zq' + run
  const aAdmin = await createSearchActor(K, 'adm', 'sidea')
  const aMember = await createSearchActor(K, 'mem', 'sidea')
  const aSupporter = await createSearchActor(K, 'sup', 'sidea')
  const aPayer = await createSearchActor(K, 'pay', 'sidea')
  const outsider = await createSearchActor(K, 'out', 'sideb')
  const ids: Record<string, number> = {}

  const orgA = await createOrg(aAdmin, K + ' sidea 組織A', 'PRIVATE', OWN_LEDGER)
  const teamA = await createTeam(aAdmin, K + ' sidea チームA1', 'MEMBERS_AND_ABOVE', OWN_LEDGER)
  // チームA1 を組織A に加盟させる（組織 ADMIN の招待 → チーム代表の承諾。どちらも aAdmin）
  try {
    const invite = await must(aAdmin, 'POST', '/api/v1/organizations/' + orgA.slug + '/team-invites', { teamSlug: teamA.slug })
    await must(aAdmin, 'POST', '/api/v1/teams/' + teamA.slug + '/org-invites/' + invite.data.id + '/accept')
  } catch (e) {
    log({ step: 'team affiliation failed (検索の可視性には影響しない)', error: String(e) })
  }
  await joinWithRole('teams', aAdmin, teamA.slug, aMember, ROLE_ID.MEMBER)
  await joinWithRole('organizations', aAdmin, orgA.slug, aMember, ROLE_ID.MEMBER)
  await joinWithRole('teams', aAdmin, teamA.slug, aPayer, ROLE_ID.MEMBER)
  // SUPPORTER は正規のサポーター経路（チームのフォロー → 申請承認。memberships.role_kind=SUPPORTER・user_roles 行なし）で作る。
  // 招待トークン経由の参加は role_kind を MEMBER で作る既存の不整合（MembershipGrantService、別件で扱う）があるため使わない。
  // フォローは「チームを閲覧できる者」に限られるので、A1 を一時的に PUBLIC・サポーター受け入れ有効にしてから非公開へ戻す。
  let supporterOk = true
  try {
    const patchTeam = async (body: Record<string, unknown>) => {
      const cur = await must(aAdmin, 'GET', '/api/v1/teams/' + teamA.slug)
      const version = cur.data.metadata?.version ?? cur.data.version
      await must(aAdmin, 'PATCH', '/api/v1/teams/' + teamA.slug, { ...body, version })
    }
    await patchTeam({ visibility: 'PUBLIC', supporterEnabled: true })
    const follow = await must(aSupporter, 'POST', '/api/v1/teams/' + teamA.slug + '/follow')
    log({ step: 'supporter follow', body: follow })
    const status = JSON.stringify(follow)
    if (!/APPROVED/i.test(status)) {
      const apps = await must(aAdmin, 'GET', '/api/v1/teams/' + teamA.slug + '/supporter-applications?size=50')
      const list = (apps.data ?? apps.content ?? []) as Array<{ id: number; userId?: number; user?: { id: number } }>
      const app = list.find((x) => (x.userId ?? x.user?.id) === aSupporter.id)
      if (!app) throw new Error('サポーター申請が見つからない: ' + JSON.stringify(apps).slice(0, 500))
      await must(aAdmin, 'POST', '/api/v1/teams/' + teamA.slug + '/supporter-applications/' + app.id + '/approve')
    }
    await patchTeam({ visibility: 'MEMBERS_AND_ABOVE' })
    const kinds = sql('SELECT role_kind FROM memberships WHERE user_id=' + aSupporter.id + " AND scope_type='TEAM' AND scope_id=" + teamA.numericId + ' AND left_at IS NULL;')
    const roles = sql('SELECT COUNT(*) FROM user_roles WHERE user_id=' + aSupporter.id + ' AND team_id=' + teamA.numericId + ';')
    const vis = sql('SELECT visibility FROM teams WHERE id=' + teamA.numericId + ';')
    log({ step: 'supporter membership', roleKind: kinds, userRoleRows: roles, teamVisibility: vis })
    if (kinds !== 'SUPPORTER' || roles !== '0' || vis !== 'MEMBERS_AND_ABOVE') throw new Error('サポーターの前提が整わない: role_kind=' + kinds + ' user_roles=' + roles + ' visibility=' + vis)
  } catch (e) {
    supporterOk = false
    log({ step: 'supporter setup failed', error: String(e) })
  }

  const orgB = await createOrg(outsider, K + ' sideb 組織B', 'PRIVATE', OWN_LEDGER)
  const teamB = await createTeam(outsider, K + ' sideb チームB1', 'MEMBERS_AND_ABOVE', OWN_LEDGER)

  // ----- POST -----
  const post = async (owner: Actor, scopeType: string, scopeId: number, marker: string) => {
    const r = await must(owner, 'POST', '/api/v1/timeline/posts', { content: K + ' ' + marker + ' の投稿本文', scopeType, scopeId: String(scopeId) })
    recordAsset('post ' + marker, '/api/v1/timeline/posts/' + r.data.id, owner, OWN_LEDGER)
    ids[marker] = Number(r.data.id)
  }
  await post(aAdmin, 'TEAM', teamA.numericId, 'sidea-postteam')
  await post(aAdmin, 'ORGANIZATION', orgA.numericId, 'sidea-postorg')
  await post(outsider, 'TEAM', teamB.numericId, 'sideb-postteam')

  // ----- MESSAGE（非公開チャンネル。aAdmin と aMember だけが参加） -----
  const channel = async (owner: Actor, team: Scope, members: number[], marker: string) => {
    const ch = await must(owner, 'POST', '/api/v1/chat/channels', {
      channelType: 'TEAM_PRIVATE', teamId: team.numericId, name: marker + ' ch', isPrivate: true, memberUserIds: members,
    })
    const chId = Number(ch.data.id)
    recordAsset('channel ' + marker, '/api/v1/chat/channels/' + chId, owner, OWN_LEDGER)
    const msg = await must(owner, 'POST', '/api/v1/chat/channels/' + chId + '/messages', { body: K + ' ' + marker + ' のメッセージ' })
    ids[marker] = Number(msg.data.id)
    ids[marker + ':channel'] = chId
  }
  await channel(aAdmin, teamA, [aMember.id], 'sidea-msg')
  await channel(outsider, teamB, [], 'sideb-msg')

  // ----- THREAD -----
  const thread = async (owner: Actor, team: Scope, marker: string) => {
    const r = await must(owner, 'POST', '/api/v1/teams/' + team.numericId + '/bulletin/threads', { title: K + ' ' + marker, body: marker + ' のスレッド本文', priority: 'INFO' })
    recordAsset('thread ' + marker, '/api/v1/teams/' + team.numericId + '/bulletin/threads/' + r.data.id, owner, OWN_LEDGER)
    ids[marker] = Number(r.data.id)
  }
  await thread(aAdmin, teamA, 'sidea-thread')
  await thread(outsider, teamB, 'sideb-thread')

  // ----- ARTICLE -----
  const article = async (owner: Actor, team: Scope, visibility: string, marker: string) => {
    const r = await must(owner, 'POST', '/api/v1/blog/posts', {
      teamId: String(team.numericId), title: K + ' ' + marker, slug: marker + '-' + run, body: marker + ' の記事本文',
      postType: 'BLOG', visibility, crossPostToTimeline: false,
    })
    const id = Number(r.data.id)
    recordAsset('article ' + marker, '/api/v1/blog/posts/' + id, owner, OWN_LEDGER)
    await must(owner, 'PATCH', '/api/v1/blog/posts/' + id + '/publish', { status: 'PUBLISHED' })
    ids[marker] = id
  }
  await article(aAdmin, teamA, 'MEMBERS_ONLY', 'sidea-artmem')
  await article(aAdmin, teamA, 'ADMINS_AND_ABOVE', 'sidea-artadm')
  await article(aAdmin, teamA, 'MEMBERS_ONLY', 'sidea-artgate')
  await article(outsider, teamB, 'MEMBERS_ONLY', 'sideb-artmem')
  // 課金ゲート: 支払い項目 → ゲート設定 → aPayer の手動支払い記録（PAID）
  const item = await must(aAdmin, 'POST', '/api/v1/teams/' + teamA.numericId + '/payment-items', { name: 'sidea-gate-item-' + run, type: 'TERM', amount: 5000, termEndsOn: '2027-12-31' })
  ids['gate-item'] = Number(item.data.id)
  recordAsset('payment-item', '/api/v1/teams/' + teamA.numericId + '/payment-items/' + item.data.id, aAdmin, OWN_LEDGER)
  await must(aAdmin, 'PUT', '/api/v1/teams/' + teamA.numericId + '/content-payment-gates', {
    contentType: 'POST', contentId: ids['sidea-artgate'], gates: [{ paymentItemId: ids['gate-item'], isTitleHidden: false }],
  })
  const now = new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 19)
  await must(aAdmin, 'POST', '/api/v1/teams/' + teamA.numericId + '/payment-items/' + ids['gate-item'] + '/payments', {
    userId: aPayer.id, amountPaid: 5000, paidAt: now, paymentMethod: 'CASH',
  })

  // ----- FILE -----
  const folder = async (owner: Actor, team: Scope, minVisibleRole: string | null, marker: string) => {
    const r = await must(owner, 'POST', '/api/v1/files/folders', { name: marker + ' folder', scopeType: 'TEAM', scopeId: String(team.numericId), minVisibleRole })
    const id = Number(r.data.id)
    recordAsset('folder ' + marker, '/api/v1/files/folders/' + id, owner, OWN_LEDGER)
    return id
  }
  const fileIn = async (owner: Actor, folderId: number, marker: string) => {
    const name = K + '-' + marker + '.txt'
    const presign = await must(owner, 'POST', '/api/v1/files/presign-upload', { folderId, fileName: name, contentType: 'text/plain', fileSize: 12 })
    const r = await must(owner, 'POST', '/api/v1/files', { folderId, name, fileKey: presign.data.fileKey, fileSize: 12, contentType: 'text/plain', description: marker + ' の説明' })
    recordAsset('file ' + marker, '/api/v1/files/' + r.data.id, owner, OWN_LEDGER)
    ids[marker] = Number(r.data.id)
  }
  await fileIn(aAdmin, await folder(aAdmin, teamA, null, 'sidea-open'), 'sidea-fileopen')
  await fileIn(aAdmin, await folder(aAdmin, teamA, 'MEMBERS_AND_ABOVE', 'sidea-mem'), 'sidea-filemem')
  await fileIn(aAdmin, await folder(aAdmin, teamA, 'ADMINS_AND_ABOVE', 'sidea-adm'), 'sidea-fileadm')
  await fileIn(outsider, await folder(outsider, teamB, null, 'sideb-open'), 'sideb-fileopen')

  // ----- ACTIVITY -----
  const template = async (owner: Actor, team: Scope, marker: string) => {
    const r = await must(owner, 'POST', '/api/v1/activity-templates?scope_type=TEAM&scope_id=' + team.numericId, { name: marker + ' tpl', fields: [] })
    return Number(r.data.id)
  }
  const activity = async (owner: Actor, team: Scope, templateId: number, visibility: string, marker: string) => {
    const r = await must(owner, 'POST', '/api/v1/activities?scope_type=TEAM&scope_id=' + team.numericId, {
      templateId, title: K + ' ' + marker, activityDate: '2026-11-20', description: marker + ' の説明', visibility,
    })
    recordAsset('activity ' + marker, '/api/v1/activities/' + r.data.id, owner, OWN_LEDGER)
    ids[marker] = Number(r.data.id)
  }
  const tplA = await template(aAdmin, teamA, 'sidea')
  const tplB = await template(outsider, teamB, 'sideb')
  await activity(aAdmin, teamA, tplA, 'PUBLIC', 'sidea-actpub')
  await activity(aAdmin, teamA, tplA, 'MEMBERS_ONLY', 'sidea-actmem')
  const draft = await must(aAdmin, 'POST', '/api/v1/activities/draft?scope_type=TEAM&scope_id=' + teamA.numericId, {
    title: K + ' sidea-actdraft', activityDate: '2026-11-20', visibility: 'PUBLIC',
  })
  recordAsset('activity sidea-actdraft', '/api/v1/activities/' + draft.data.id, aAdmin, OWN_LEDGER)
  ids['sidea-actdraft'] = Number(draft.data.id)
  await activity(outsider, teamB, tplB, 'PUBLIC', 'sideb-actpub')
  // テンプレートは活動記録の後に消す（台帳は逆順に削除される）ため、記録より前に積めないので末尾に積む…ではなく
  // 逆順削除で活動記録→テンプレの順になるよう、ここで先頭側へ挿入する
  const ledger = JSON.parse(readFileSync(OWN_LEDGER, 'utf8')) as unknown[]
  const tplRecs = [
    { label: 'activity-template A', path: '/api/v1/activity-templates/' + tplA, owner: { email: aAdmin.email, password: aAdmin.password } },
    { label: 'activity-template B', path: '/api/v1/activity-templates/' + tplB, owner: { email: outsider.email, password: outsider.password } },
  ]
  // 組織・チーム（先頭 4 件）の直後に置く → 逆順削除で活動記録の後・チーム削除の前に消える
  ledger.splice(4, 0, ...tplRecs)
  writeFileSync(OWN_LEDGER, JSON.stringify(ledger, null, 2))

  const world: World = { K, run, actors: { aAdmin, aMember, aSupporter, aPayer, outsider }, supporterOk, orgA, teamA, orgB, teamB, ids }
  log({ step: 'world built', K, ids, orgA, teamA, orgB, teamB, supporterOk, actors: Object.fromEntries(Object.entries(world.actors).map(([k, a]) => [k, { id: a.id, nickname: a.nickname }])) })
  return world
}

let W: World
test.beforeAll(async () => {
  test.setTimeout(600_000)
  if (existsSync(WORLD_FILE)) {
    W = JSON.parse(readFileSync(WORLD_FILE, 'utf8')) as World
    return
  }
  W = await buildWorld()
  writeFileSync(WORLD_FILE, JSON.stringify(W, null, 2))
})

// ---------- 画面操作の共通部 ----------
/** 検索 API はユーザーあたり 30 回/分。429 で偽の失敗を出さないよう、利用者ごとに 1 分あたり 20 回へ抑える。 */
const searchCalls = new Map<string, number[]>()
async function pace(actor: Actor, need = 2) {
  for (;;) {
    const now = Date.now()
    const recent = (searchCalls.get(actor.email) ?? []).filter((t) => now - t < 61_000)
    searchCalls.set(actor.email, recent)
    if (recent.length + need <= 20) return
    await new Promise((r) => setTimeout(r, 2_000))
  }
}

interface Session { context: BrowserContext; page: Page; actor: Actor; searchStatuses: number[]; searchUrls: string[] }

async function open(browser: Browser, actor: Actor, opts: { viewport?: { width: number; height: number } } = {}): Promise<Session> {
  const context = await browser.newContext({
    baseURL: FE, storageState: { cookies: [], origins: [] }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo',
    viewport: opts.viewport ?? { width: 1280, height: 900 },
  })
  const page = await context.newPage()
  const s: Session = { context, page, actor, searchStatuses: [], searchUrls: [] }
  page.on('request', (req) => {
    const u = new URL(req.url())
    if (u.pathname === '/api/v1/search') {
      const list = searchCalls.get(actor.email) ?? []
      list.push(Date.now())
      searchCalls.set(actor.email, list)
      s.searchUrls.push(u.pathname + u.search)
    }
  })
  page.on('response', (res) => {
    const u = new URL(res.url())
    if (u.pathname === '/api/v1/search') s.searchStatuses.push(res.status())
  })
  await page.goto(FE + '/robots.txt')
  await loginViaApi(page, actor, { apiBaseUrl: API, deferNavigation: true })
  return s
}

function isSearchResponse(r: { url(): string; request(): { method(): string } }) {
  return r.request().method() === 'GET' && new URL(r.url()).pathname === '/api/v1/search'
}

async function settled(page: Page) {
  await expect(page.locator('[data-testid="global-search-results"], [data-testid="global-search-empty"]').first()).toBeVisible({ timeout: 60_000 })
}

/** /search の入力欄から検索する（画面操作） */
async function searchOnPage(s: Session, q: string) {
  await pace(s.actor)
  const input = s.page.getByTestId('global-search-input')
  await input.click()
  await input.fill('')
  await input.pressSequentially(q, { delay: 5 })
  const [res] = await Promise.all([
    s.page.waitForResponse(isSearchResponse, { timeout: 90_000 }),
    s.page.getByTestId('global-search-submit').click(),
  ])
  expect(res.status(), '検索 API の応答').toBe(200)
  await settled(s.page)
}

/** ヘッダー検索欄から検索する（Enter で /search へ遷移） */
async function searchFromHeader(s: Session, q: string) {
  await pace(s.actor)
  const input = s.page.getByTestId('header-search-input')
  await expect(input).toBeVisible({ timeout: 60_000 })
  await input.click()
  await input.fill('')
  await input.pressSequentially(q, { delay: 5 })
  const [res] = await Promise.all([
    s.page.waitForResponse(isSearchResponse, { timeout: 90_000 }),
    input.press('Enter'),
  ])
  expect(res.status(), '検索 API の応答').toBe(200)
  await expect(s.page).toHaveURL(/\/search\?/)
  await settled(s.page)
}

function cards(page: Page) {
  return page.locator('[data-testid="global-search-results"] > [data-testid^="global-search-result-"]')
}

async function tabCounts(page: Page): Promise<Record<string, number>> {
  const tabs = page.locator('[data-testid^="global-search-tab-"]')
  const n = await tabs.count()
  const out: Record<string, number> = {}
  for (let i = 0; i < n; i++) {
    const id = (await tabs.nth(i).getAttribute('data-testid'))!.replace('global-search-tab-', '')
    const m = (await tabs.nth(i).innerText()).match(/\((\d+)\)/)
    out[id] = m ? Number(m[1]) : NaN
  }
  return out
}

interface Hit { type: string; id: string; text: string; href: string | null }
interface Collected { counts: Record<string, number>; hits: Hit[]; mismatches: string[] }

/** 概要の件数を読み、件数のある各種別タブを押して（詳細ビュー）件数とリスト件数を突き合わせ、全結果を集める */
async function collectAll(s: Session, label: string): Promise<Collected> {
  const page = s.page
  const counts = await tabCounts(page)
  const mismatches: string[] = []
  const sum = TYPES.reduce((acc, t) => acc + (counts[t] ?? 0), 0)
  if ((counts.ALL ?? 0) !== sum) mismatches.push('ALL ' + counts.ALL + ' != 種別合計 ' + sum)
  const overviewCards = await cards(page).count()
  const expectedOverview = TYPES.reduce((acc, t) => acc + Math.min(counts[t] ?? 0, 5), 0)
  if (overviewCards !== expectedOverview) mismatches.push('概要のカード ' + overviewCards + ' != min(件数,5) の合計 ' + expectedOverview)
  const hits: Hit[] = []
  for (const t of TYPES) {
    const c = counts[t] ?? 0
    if (c === 0) continue
    await pace(s.actor, 1)
    const [res] = await Promise.all([
      page.waitForResponse(isSearchResponse, { timeout: 90_000 }),
      page.getByTestId('global-search-tab-' + t).click(),
    ])
    expect(res.status()).toBe(200)
    await settled(page)
    await expect(page.getByTestId('global-search-tab-' + t)).toHaveAttribute('aria-selected', 'true')
    const list = cards(page)
    const n = await list.count()
    const tabNow = (await tabCounts(page))[t]
    if (n !== c || tabNow !== c) mismatches.push(t + ': タブ件数 ' + tabNow + '（概要時 ' + c + '） / リスト件数 ' + n)
    for (let i = 0; i < n; i++) {
      const el = list.nth(i)
      const tid = (await el.getAttribute('data-testid'))!.replace('global-search-result-', '')
      const [type, id] = [tid.split('-')[0], tid.split('-').slice(1).join('-')]
      hits.push({ type, id, text: (await el.innerText()).replace(/\s+/g, ' '), href: await el.getAttribute('href') })
    }
  }
  await shot(page, label)
  log({ step: 'collected', label, url: page.url(), counts, mismatches, hits })
  return { counts, hits, mismatches }
}

function has(c: Collected, type: ContentType, marker: string) {
  return c.hits.some((h) => h.type === type && h.text.toLowerCase().includes(marker))
}
function leaked(c: Collected, forbidden: string) {
  return c.hits.filter((h) => h.text.toLowerCase().includes(forbidden)).map((h) => h.type + '#' + h.id + ' ' + h.text.slice(0, 120))
}

function expectVisibility(c: Collected, mustSee: Array<[ContentType, string]>, mustNot: string[], who: string) {
  for (const [type, marker] of mustSee) expect.soft(has(c, type, marker), who + ' に ' + type + ' ' + marker + ' が出る').toBe(true)
  for (const marker of mustNot) expect(leaked(c, marker), who + ' に ' + marker + ' が出ない（権限範囲外）').toEqual([])
  expect.soft(c.mismatches, who + ' のタブ件数とリスト件数が一致').toEqual([])
}

// ---------- 1. 権限あり ----------
test('T1 a-admin: /search から検索し、見てよい全種別が出てタブ件数とリスト件数が一致する', async ({ browser }) => {
  test.setTimeout(600_000)
  const s = await open(browser, W.actors.aAdmin)
  await gotoAndHydrate(s.page, '/search')
  await searchOnPage(s, W.K)
  await expect(s.page).toHaveURL(new RegExp('/search\\?q=' + W.K + '$'))
  const c = await collectAll(s, 'T1-a-admin')
  expectVisibility(c, [
    ['POST', 'sidea-postteam'], ['POST', 'sidea-postorg'], ['MESSAGE', 'sidea-msg'], ['THREAD', 'sidea-thread'],
    ['ARTICLE', 'sidea-artmem'], ['ARTICLE', 'sidea-artadm'], ['FILE', 'sidea-fileopen'], ['FILE', 'sidea-filemem'],
    ['FILE', 'sidea-fileadm'], ['ACTIVITY', 'sidea-actpub'], ['ACTIVITY', 'sidea-actmem'], ['TEAM', 'sidea'], ['ORGANIZATION', 'sidea'],
    ['USER', 'sideamem'],
  ], ['sideb', 'sidea-artgate', 'sidea-actdraft'], 'a-admin')
  expect(s.searchStatuses.filter((x) => x !== 200), '検索 API が 200 以外を返していない').toEqual([])
  writeFileSync(path.join(path.dirname(WORLD_FILE), 'cmp0722-admin-hits.json'), JSON.stringify(c.hits, null, 2))
  await s.context.close()
})

test('T2 a-member: ヘッダー検索欄から検索し、管理者限定記事・管理者フォルダのファイル・課金ゲート記事・DRAFT が出ない', async ({ browser }) => {
  test.setTimeout(600_000)
  const s = await open(browser, W.actors.aMember)
  await gotoAndHydrate(s.page, '/')
  await searchFromHeader(s, W.K)
  const c = await collectAll(s, 'T2-a-member')
  expectVisibility(c, [
    ['POST', 'sidea-postteam'], ['POST', 'sidea-postorg'], ['MESSAGE', 'sidea-msg'], ['THREAD', 'sidea-thread'],
    ['ARTICLE', 'sidea-artmem'], ['FILE', 'sidea-fileopen'], ['FILE', 'sidea-filemem'], ['ACTIVITY', 'sidea-actpub'],
    ['ACTIVITY', 'sidea-actmem'], ['TEAM', 'sidea'], ['ORGANIZATION', 'sidea'], ['USER', 'sideaadm'],
  ], ['sideb', 'sidea-artadm', 'sidea-fileadm', 'sidea-artgate', 'sidea-actdraft'], 'a-member')
  await s.context.close()
})

test('T3 a-supporter: メンバー限定記事・メンバー以上フォルダ・メンバー限定活動・未参加チャンネルが出ない', async ({ browser }) => {
  test.setTimeout(600_000)
  test.skip(!W.supporterOk, 'SUPPORTER として所属させられなかった（evidence.jsonl 参照）')
  const s = await open(browser, W.actors.aSupporter)
  await gotoAndHydrate(s.page, '/search')
  await searchOnPage(s, W.K)
  const c = await collectAll(s, 'T3-a-supporter')
  // チームA1 は visibility=MEMBERS_AND_ABOVE。TEAM 種別はチーム詳細の正準（TeamVisibilityMapper → 段の規則）と同じ判定で、
  // SUPPORTER は MEMBER 段に届かないため結果にも件数にも出ない（#4806 / F04.6 §6 TEAM/ORG 行）。
  // 組織A には所属していないため ORGANIZATION も 0 件。
  expectVisibility(c, [['POST', 'sidea-postteam'], ['FILE', 'sidea-fileopen'], ['ACTIVITY', 'sidea-actpub']],
    ['sideb', 'sidea-msg', 'sidea-artmem', 'sidea-artadm', 'sidea-artgate', 'sidea-filemem', 'sidea-fileadm', 'sidea-actmem', 'sidea-actdraft', 'sidea-postorg'],
    'a-supporter')
  expect(c.counts.TEAM ?? 0, 'a-supporter の TEAM 件数（MEMBERS_AND_ABOVE のチームA1 は数えない）').toBe(0)
  expect(c.hits.filter((h) => h.type === 'TEAM' || h.type === 'ORGANIZATION'), 'a-supporter に TEAM/ORGANIZATION の結果が出ない').toEqual([])
  await s.context.close()
})

test('T4 a-payer: 支払い済みの課金ゲート記事が出る（管理者限定・DRAFT・未参加チャンネルは出ない）', async ({ browser }) => {
  test.setTimeout(600_000)
  const s = await open(browser, W.actors.aPayer)
  await gotoAndHydrate(s.page, '/search')
  await searchOnPage(s, W.K)
  const c = await collectAll(s, 'T4-a-payer')
  expectVisibility(c, [['ARTICLE', 'sidea-artgate'], ['ARTICLE', 'sidea-artmem'], ['THREAD', 'sidea-thread']],
    ['sideb', 'sidea-artadm', 'sidea-fileadm', 'sidea-actdraft', 'sidea-msg'], 'a-payer')
  await s.context.close()
})

// ---------- 3. 他組織 ----------
test('T5 outsider-b: 組織A側のデータが結果・件数・サジェストに出ない／scope の URL 直打ちは実在しない ID と同じ表示', async ({ browser }) => {
  test.setTimeout(600_000)
  const s = await open(browser, W.actors.outsider)
  const page = s.page
  // サジェスト（ヘッダー検索欄）
  await gotoAndHydrate(page, '/')
  const input = page.getByTestId('header-search-input')
  await expect(input).toBeVisible({ timeout: 60_000 })
  await input.click()
  const [sres] = await Promise.all([
    page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/search/suggestions', { timeout: 60_000 }),
    input.pressSequentially(W.K, { delay: 5 }),
  ])
  expect(sres.status()).toBe(200)
  await expect(page.getByTestId('header-search-suggestions')).toBeVisible()
  const suggestItems = (await page.getByTestId('header-search-suggestions').locator('[role="option"]').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').toLowerCase())
  await shot(page, 'T5-outsider-suggestions')
  log({ step: 'outsider suggestions', items: suggestItems })
  // KEYWORD 候補は本人の検索履歴だけ（F04.6 §4。他人の検索語は出さない）。本人が過去に打った語（例: 探索で入力した
  // 「…sidea」）がそのまま出るのは仕様なので、漏洩判定は可視性述語を通る USER / TEAM / ORGANIZATION 候補で行う。
  const entityItems = suggestItems.filter((t) => !t.endsWith('キーワード'))
  expect(entityItems.filter((t) => t.includes('sidea')), 'サジェストの USER/TEAM/ORG 候補に組織A側（sidea）が出ない').toEqual([])
  expect(entityItems.join(' '), 'サジェストに自分の側（sideb）は出る').toContain('sideb')
  // ヘッダー Enter で /search へ
  await pace(s.actor)
  const [res] = await Promise.all([page.waitForResponse(isSearchResponse, { timeout: 90_000 }), input.press('Enter')])
  expect(res.status()).toBe(200)
  await settled(page)
  const base = await collectAll(s, 'T5-outsider-base')
  expect(leaked(base, 'sidea'), 'outsider-b に組織A側が出ない').toEqual([])
  expect.soft(base.mismatches, 'outsider-b のタブ件数とリスト件数が一致').toEqual([])
  for (const t of ['POST', 'MESSAGE', 'THREAD', 'ARTICLE', 'FILE', 'TEAM', 'ORGANIZATION', 'ACTIVITY'] as ContentType[]) {
    expect.soft(base.counts[t] ?? 0, 'outsider-b の ' + t + ' 件数は自分の側の 1 件だけ').toBe(1)
  }

  // scopeType / scopeId の URL 直打ち（A の実在 ID / 実在しない ID）
  const variants: Record<string, string> = {
    teamA: '/search?q=' + W.K + '&scopeType=TEAM&scopeId=' + W.teamA.numericId,
    teamAbsent: '/search?q=' + W.K + '&scopeType=TEAM&scopeId=987654321',
    orgA: '/search?q=' + W.K + '&scopeType=ORGANIZATION&scopeId=' + W.orgA.numericId,
    orgAbsent: '/search?q=' + W.K + '&scopeType=ORGANIZATION&scopeId=987654321',
  }
  const seen: Record<string, { counts: Record<string, number>; ids: string[]; apiUrl: string; body: string }> = {}
  for (const [k, url] of Object.entries(variants)) {
    await pace(s.actor)
    const [r] = await Promise.all([page.waitForResponse(isSearchResponse, { timeout: 90_000 }), gotoAndHydrate(page, url)])
    expect(r.status()).toBe(200)
    await settled(page)
    const counts = await tabCounts(page)
    const ids = (await cards(page).evaluateAll((els) => els.map((e) => e.getAttribute('data-testid') ?? ''))).sort()
    const body = (await page.locator('main').innerText().catch(() => page.locator('body').innerText())).toLowerCase()
    seen[k] = { counts, ids, apiUrl: new URL(r.url()).pathname + new URL(r.url()).search, body: body.slice(0, 2000) }
    await shot(page, 'T5-outsider-scope-' + k)
    expect(body, k + ': 組織A側（sidea）が出ない').not.toContain('sidea')
  }
  log({ step: 'outsider scope direct urls', seen: Object.fromEntries(Object.entries(seen).map(([k, v]) => [k, { counts: v.counts, ids: v.ids, apiUrl: v.apiUrl }])) })
  expect(seen.teamA.counts, 'TEAM: 実在 A1 ID と実在しない ID で件数表示が同じ').toEqual(seen.teamAbsent.counts)
  expect(seen.teamA.ids, 'TEAM: 実在 A1 ID と実在しない ID で結果が同じ').toEqual(seen.teamAbsent.ids)
  expect(seen.orgA.counts, 'ORG: 実在 A ID と実在しない ID で件数表示が同じ').toEqual(seen.orgAbsent.counts)
  expect(seen.orgA.ids, 'ORG: 実在 A ID と実在しない ID で結果が同じ').toEqual(seen.orgAbsent.ids)

  // 結果詳細 URL（a-admin が T1 で得たリンク）の直打ち
  const adminHits = JSON.parse(readFileSync(path.join(path.dirname(WORLD_FILE), 'cmp0722-admin-hits.json'), 'utf8')) as Hit[]
  const targets: Array<[string, string]> = []
  for (const [type, marker] of [['ARTICLE', 'sidea-artmem'], ['FILE', 'sidea-fileopen'], ['ACTIVITY', 'sidea-actpub'], ['THREAD', 'sidea-thread'], ['POST', 'sidea-postteam'], ['MESSAGE', 'sidea-msg']] as const) {
    const h = adminHits.find((x) => x.type === type && x.text.toLowerCase().includes(marker))
    if (h?.href) targets.push([type + ':' + marker, h.href])
    else log({ step: 'outsider direct: admin hit has no href', type, marker })
  }
  const directResults: Record<string, string> = {}
  for (const [label, href] of targets) {
    await gotoAndHydrate(page, href)
    // eslint-disable-next-line no-restricted-syntax -- 待機のタイムアウトは許容し、直後の expect が実際の表示を検証するため握りつぶしに当たらない
    await page.waitForLoadState('networkidle', { timeout: 30_000 }).catch(() => undefined)
    await page.waitForTimeout(3_000)
    const body = (await page.locator('body').innerText()).toLowerCase()
    const marker = label.split(':')[1].split(' ').pop()!
    directResults[label] = page.url() + ' | ' + body.replace(/\s+/g, ' ').slice(0, 300)
    await shot(page, 'T5-outsider-direct-' + label.replace(/[^a-z0-9]+/gi, '_'))
    if (label.startsWith('ACTIVITY')) {
      // 活動記録詳細は visibility を見ていない疑い（殿の既知事項）。出ても止まらず記録する
      directResults[label] += ' | 中身表示=' + body.includes(marker)
    } else {
      expect.soft(body, label + ' を outsider-b が直打ちしても中身（' + marker + '）が出ない').not.toContain(marker)
    }
  }
  directResults['ACTIVITY:/activities/{id} actmem'] = await recordActivityDetail(page, 'outsider', W.ids['sidea-actmem'], 'sidea-actmem')
  log({ step: 'outsider direct detail urls', directResults })
  expect(s.searchStatuses.filter((x) => x !== 200)).toEqual([])
  await s.context.close()
})

/** 活動記録詳細 /activities/{id} を直打ちし、画面に何が出たかを記録する（合否にはしない） */
async function recordActivityDetail(page: Page, who: string, id: number, marker: string): Promise<string> {
  const [res] = await Promise.all([
    // eslint-disable-next-line no-restricted-syntax -- 待機のタイムアウトは許容し、直後の expect が実際の表示を検証するため握りつぶしに当たらない
    page.waitForResponse((r) => new RegExp('/api/v1/activities/' + id + '$').test(new URL(r.url()).pathname), { timeout: 90_000 }).catch(() => null),
    gotoAndHydrate(page, '/activities/' + id),
  ])
  // eslint-disable-next-line no-restricted-syntax -- 待機のタイムアウトは許容し、直後の expect が実際の表示を検証するため握りつぶしに当たらない
  await page.waitForLoadState('networkidle', { timeout: 30_000 }).catch(() => undefined)
  await page.waitForTimeout(3_000)
  const body = (await page.locator('body').innerText()).replace(/\s+/g, ' ')
  await shot(page, 'activity-detail-' + who + '-' + id)
  const out = page.url() + ' | API ' + (res ? res.status() : 'なし') + ' | 中身表示=' + body.toLowerCase().includes(marker) + ' | ' + body.slice(0, 300)
  log({ step: 'activity detail direct', who, id, out })
  return out
}

test('T5b a-supporter（正規サポーター）: MEMBERS_ONLY 活動記録の詳細を直打ちして画面を記録する', async ({ browser }) => {
  test.setTimeout(600_000)
  test.skip(!W.supporterOk, 'SUPPORTER を正規経路で作れなかった（evidence.jsonl 参照）')
  const s = await open(browser, W.actors.aSupporter)
  const out = await recordActivityDetail(s.page, 'supporter', W.ids['sidea-actmem'], 'sidea-actmem')
  const pub = await recordActivityDetail(s.page, 'supporter', W.ids['sidea-actpub'], 'sidea-actpub')
  log({ step: 'supporter activity detail', actmem: out, actpub: pub })
  await s.context.close()
})

// ---------- 6. 検分修正 #4806 の確認 ----------
/** ヘッダー検索欄に打鍵し、BE の候補応答を待ってサジェストの [テキスト, 種別ラベル] を返す */
async function headerSuggestions(page: Page, q: string): Promise<string[]> {
  const input = page.getByTestId('header-search-input')
  await expect(input).toBeVisible({ timeout: 60_000 })
  await input.click()
  await input.fill('')
  const [res] = await Promise.all([
    page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/search/suggestions', { timeout: 60_000 }),
    input.pressSequentially(q, { delay: 5 }),
  ])
  expect(res.status()).toBe(200)
  const list = page.getByTestId('header-search-suggestions')
  const visible = await list.waitFor({ state: 'visible', timeout: 5_000 }).then(() => true).catch(() => false)
  return visible ? (await list.locator('[role="option"]').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ')) : []
}

test('T9a TEAM 種別の段: MEMBERS_AND_ABOVE のチームA1 は正規サポーターの結果・件数・サジェストに出ず、a-member には出る', async ({ browser }) => {
  test.setTimeout(600_000)
  test.skip(!W.supporterOk, 'SUPPORTER を正規経路で作れなかった')
  const teamLabel = W.teamA.name.toLowerCase()
  for (const [who, actor, expectTeam] of [['supporter', W.actors.aSupporter, false], ['member', W.actors.aMember, true]] as const) {
    const s = await open(browser, actor)
    await pace(s.actor)
    const [r] = await Promise.all([s.page.waitForResponse(isSearchResponse, { timeout: 90_000 }), gotoAndHydrate(s.page, '/search?q=' + W.K)])
    expect(r.status()).toBe(200)
    await settled(s.page)
    const counts = await tabCounts(s.page)
    const teamCards = await cards(s.page).filter({ has: s.page.getByTestId('global-search-result-type-badge').filter({ hasText: 'チーム' }) }).allInnerTexts()
    // 候補は最大 5 件（KEYWORD 最大3・各種別最大2をマージ）で、キーワードだけだと履歴とユーザーで枠が埋まり
    // チーム候補が押し出されることがある。チーム名にだけ当たる語（キーワード＋「sidea チーム」）で引く
    const sugg = await headerSuggestions(s.page, W.K + ' sidea チーム')
    const teamSugg = sugg.filter((t) => t.toLowerCase().includes(teamLabel))
    await shot(s.page, 'T9a-' + who)
    log({ step: 'T9a team ladder', who, counts, teamCards, suggestions: sugg })
    if (expectTeam) {
      expect(counts.TEAM, who + ' の TEAM 件数').toBe(1)
      expect(teamCards.some((t) => t.toLowerCase().includes(teamLabel)), who + ' の結果にチームA1').toBe(true)
      expect(teamSugg.length, who + ' のサジェストにチームA1').toBeGreaterThan(0)
    } else {
      expect(counts.TEAM ?? 0, who + ' の TEAM 件数').toBe(0)
      expect(teamCards, who + ' の結果に TEAM カードが無い').toEqual([])
      expect(teamSugg, who + ' のサジェストにチームA1 が無い').toEqual([])
    }
    await s.context.close()
  }
})

test('T9b 狭幅（360x800・390x844）で結果カードの種別バッジが折れず、日付がカード外にはみ出さない', async ({ browser }) => {
  test.setTimeout(600_000)
  for (const vp of [{ width: 360, height: 800 }, { width: 390, height: 844 }]) {
    const s = await open(browser, W.actors.aMember, { viewport: vp })
    await pace(s.actor)
    const [r] = await Promise.all([s.page.waitForResponse(isSearchResponse, { timeout: 90_000 }), gotoAndHydrate(s.page, '/search?q=' + W.K)])
    expect(r.status()).toBe(200)
    await settled(s.page)
    const dims = await cards(s.page).evaluateAll((els) => els.map((card) => {
      const rect = (e: Element | null) => (e ? e.getBoundingClientRect() : null)
      const badge = card.querySelector('[data-testid="global-search-result-type-badge"]') as HTMLElement | null
      const date = card.querySelector('[data-testid="global-search-result-date"]') as HTMLElement | null
      const c = rect(card)!
      const b = rect(badge)
      const d = rect(date)
      const lh = (el: HTMLElement | null) => (el ? parseFloat(getComputedStyle(el).lineHeight) || parseFloat(getComputedStyle(el).fontSize) * 1.5 : 0)
      // 1 行かどうか: テキスト行の矩形（Range の client rects）の上端の種類数
      const lines = (el: HTMLElement | null) => {
        if (!el) return 0
        const range = document.createRange()
        range.selectNodeContents(el)
        return new Set(Array.from(range.getClientRects()).filter((x) => x.width > 0).map((x) => Math.round(x.top))).size
      }
      return {
        id: card.getAttribute('data-testid'),
        card: { left: c.left, right: c.right, width: c.width },
        badge: b && { text: badge!.innerText, width: b.width, height: b.height, lines: lines(badge), lineHeight: lh(badge) },
        date: d && { text: date!.innerText, left: d.left, right: d.right, height: d.height, lines: lines(date) },
        docOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      }
    }))
    await shot(s.page, 'T9b-' + vp.width + 'x' + vp.height)
    log({ step: 'T9b dims', viewport: vp, dims })
    expect(dims.length, '結果カードがある').toBeGreaterThan(0)
    for (const d of dims) {
      expect.soft(d.badge?.lines, vp.width + ' ' + d.id + ' 種別バッジが 1 行').toBe(1)
      if (d.date) {
        expect.soft(d.date.lines, vp.width + ' ' + d.id + ' 日付が 1 行').toBe(1)
        expect.soft(d.date.right, vp.width + ' ' + d.id + ' 日付がカードの右端を越えない').toBeLessThanOrEqual(d.card.right + 0.5)
        expect.soft(d.date.left, vp.width + ' ' + d.id + ' 日付がカードの左端より内側').toBeGreaterThanOrEqual(d.card.left - 0.5)
      }
      expect.soft(d.docOverflow, vp.width + ' 横スクロールが出ない').toBe(false)
    }
    await s.context.close()
  }
})

test('T9c 題名を持つ結果（スレッド・記事・ファイル・活動記録）の題名に検索語の強調（mark）が付く', async ({ browser }) => {
  test.setTimeout(600_000)
  const s = await open(browser, W.actors.aMember)
  await pace(s.actor)
  const [r] = await Promise.all([s.page.waitForResponse(isSearchResponse, { timeout: 90_000 }), gotoAndHydrate(s.page, '/search?q=' + W.K)])
  expect(r.status()).toBe(200)
  await settled(s.page)
  const titled = await cards(s.page).evaluateAll((els) => els
    .filter((card) => card.querySelector('[data-testid="global-search-result-title"]'))
    .map((card) => {
      const title = card.querySelector('[data-testid="global-search-result-title"]') as HTMLElement
      return { id: card.getAttribute('data-testid'), title: title.innerText, marks: Array.from(title.querySelectorAll('mark')).map((m) => m.textContent) }
    }))
  await shot(s.page, 'T9c-title-mark')
  log({ step: 'T9c title marks', titled })
  for (const type of ['THREAD', 'ARTICLE', 'FILE', 'ACTIVITY']) {
    const ofType = titled.filter((t) => t.id?.startsWith('global-search-result-' + type + '-'))
    expect.soft(ofType.length, type + ' の題名付き結果がある').toBeGreaterThan(0)
    for (const t of ofType) expect.soft(t.marks.map((m) => (m ?? '').toLowerCase()), t.id + ' の題名の強調').toEqual([W.K])
  }
  await s.context.close()
})

// ---------- 1'. 結果から詳細への導線（a-admin） ----------
test('T6 a-admin: 各種別の結果リンクを押して詳細画面へ到達できる', async ({ browser }) => {
  test.setTimeout(600_000)
  const s = await open(browser, W.actors.aAdmin)
  const page = s.page
  const plan: Array<[ContentType, string]> = [
    ['POST', 'sidea-postteam'], ['MESSAGE', 'sidea-msg'], ['THREAD', 'sidea-thread'], ['ARTICLE', 'sidea-artmem'],
    ['FILE', 'sidea-fileopen'], ['TEAM', 'sidea'], ['ORGANIZATION', 'sidea'], ['ACTIVITY', 'sidea-actpub'], ['USER', 'sideamem'],
  ]
  const outcome: Record<string, string> = {}
  for (const [type, marker] of plan) {
    await pace(s.actor)
    const [r] = await Promise.all([page.waitForResponse(isSearchResponse, { timeout: 90_000 }), gotoAndHydrate(page, '/search?q=' + W.K + '&type=' + type)])
    expect(r.status()).toBe(200)
    await settled(page)
    const card = cards(page).filter({ hasText: new RegExp(marker, 'i') }).first()
    await expect(card, type + ' の結果カード').toBeVisible()
    const href = await card.getAttribute('href')
    if (!href) {
      outcome[type] = 'リンクなし（url=null）'
      expect.soft(type, type + ' の結果にリンクがある（USER は social handle が無いと null が仕様）').toBe('USER')
      continue
    }
    await card.click()
    await page.waitForURL((u) => !u.pathname.startsWith('/search'), { timeout: 60_000 })
    // eslint-disable-next-line no-restricted-syntax -- 待機のタイムアウトは許容し、直後の expect が実際の表示を検証するため握りつぶしに当たらない
    await page.waitForLoadState('networkidle', { timeout: 30_000 }).catch(() => undefined)
    // 遷移先で対象（題名・本文・ファイル名など）が表示されるまで待つ
    let reached = await page.getByText(new RegExp(type === 'TEAM' || type === 'ORGANIZATION' ? W.K : marker, 'i')).first()
      .waitFor({ state: 'visible', timeout: 45_000 }).then(() => true).catch(() => false)
    let note = ''
    if (!reached && type === 'FILE') {
      // FILE の url はスコープのファイル一覧（設計書 §4）。ファイルはフォルダ内にあるため、フォルダを開いて辿れるかを記録する
      const folder = page.getByText(/sidea-open folder/i).first()
      const folderShown = await folder.waitFor({ state: 'visible', timeout: 15_000 }).then(() => true).catch(() => false)
      await shot(page, 'T6-link-FILE-landing')
      if (folderShown) {
        await folder.click()
        reached = await page.getByText(new RegExp(marker, 'i')).first().waitFor({ state: 'visible', timeout: 30_000 }).then(() => true).catch(() => false)
        note = '（一覧の着地点にはファイルが無く、フォルダ「sidea-open folder」を開いて到達=' + reached + '）'
      } else {
        note = '（一覧の着地点にフォルダもファイルも見えない）'
      }
    }
    outcome[type] = href + ' -> ' + page.url() + (reached ? ' 到達・表示OK' : ' 表示を確認できず') + note
    await shot(page, 'T6-link-' + type)
    expect.soft(reached, type + ' の結果リンク ' + href + ' の遷移先で対象が表示される').toBe(true)
  }
  log({ step: 'result links', outcome })
  await s.context.close()
})

// ---------- 4. ヘッダー検索 ----------
test('T7 ヘッダー検索: サジェスト表示→矢印/Enter で遷移・Esc で閉じる・IME 変換中の Enter で遷移しない・狭幅は虫眼鏡', async ({ browser }) => {
  test.setTimeout(600_000)
  const s = await open(browser, W.actors.aMember)
  const page = s.page
  await gotoAndHydrate(page, '/')
  const input = page.getByTestId('header-search-input')
  await expect(input).toBeVisible({ timeout: 60_000 })
  await expect(page.getByTestId('header-search-mobile-link')).toBeHidden()
  const startUrl = page.url()

  // サジェスト → Esc で閉じる
  await input.click()
  await Promise.all([
    page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/search/suggestions', { timeout: 60_000 }),
    input.pressSequentially(W.K, { delay: 5 }),
  ])
  const list = page.getByTestId('header-search-suggestions')
  await expect(list).toBeVisible()
  const items = await list.locator('[role="option"]').allInnerTexts()
  log({ step: 'member suggestions', items })
  expect(items.join(' ').toLowerCase()).not.toContain('sideb')
  await shot(page, 'T7-suggestions-open')
  await input.press('Escape')
  await expect(list).toBeHidden()
  expect(page.url(), 'Esc で遷移しない').toBe(startUrl)

  // 矢印で選択 → Enter で遷移
  await input.fill('')
  await Promise.all([
    page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/search/suggestions', { timeout: 60_000 }),
    input.pressSequentially(W.K, { delay: 5 }),
  ])
  await expect(list).toBeVisible()
  await input.press('ArrowDown')
  const first = page.getByTestId('header-search-suggestion-0')
  await expect(first).toHaveAttribute('aria-selected', 'true')
  await input.press('ArrowDown')
  const second = page.getByTestId('header-search-suggestion-1')
  const optionCount = await list.locator('[role="option"]').count()
  if (optionCount > 1) await expect(second).toHaveAttribute('aria-selected', 'true')
  await input.press('ArrowUp')
  await expect(first).toHaveAttribute('aria-selected', 'true')
  const chosen = (await first.innerText()).replace(/\s+/g, ' ')
  await input.press('Enter')
  await page.waitForURL((u) => u.href !== startUrl, { timeout: 60_000 })
  log({ step: 'suggestion enter', chosen, url: page.url() })
  await shot(page, 'T7-suggestion-enter')
  expect(page.url(), '候補の Enter で遷移する').not.toBe(startUrl)

  // IME 変換中の Enter で遷移しない（CDP の IME 合成で compositionstart / compositionend を実際に発火させる）
  await gotoAndHydrate(page, '/')
  const ime = page.getByTestId('header-search-input')
  await expect(ime).toBeVisible({ timeout: 60_000 })
  await page.evaluate(() => {
    const w = window as unknown as { __imeLog: string[] }
    w.__imeLog = []
    const el = document.querySelector('[data-testid="header-search-input"]')!
    for (const ev of ['compositionstart', 'compositionend']) el.addEventListener(ev, () => w.__imeLog.push(ev))
    el.addEventListener('keydown', (e) => {
      const k = e as KeyboardEvent
      w.__imeLog.push('keydown:' + k.key + ':composing=' + k.isComposing + ':keyCode=' + k.keyCode)
    }, true)
  })
  await ime.click()
  await ime.pressSequentially(W.K, { delay: 5 })
  const before = page.url()
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Input.imeSetComposition', { text: 'けんさく', selectionStart: 4, selectionEnd: 4 })
  await cdp.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 229, nativeVirtualKeyCode: 229 })
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 229, nativeVirtualKeyCode: 229 })
  await page.waitForTimeout(2_000)
  expect(page.url(), 'IME 変換中の Enter では遷移しない').toBe(before)
  await cdp.send('Input.insertText', { text: 'けんさく' })
  const imeLog = await page.evaluate(() => (window as unknown as { __imeLog: string[] }).__imeLog)
  log({ step: 'ime', imeLog, value: await ime.inputValue() })
  expect(imeLog, 'compositionstart が発火した').toContain('compositionstart')
  expect(imeLog, 'compositionend が発火した').toContain('compositionend')
  expect(imeLog.some((l) => l.startsWith('keydown:') && (l.includes('composing=true') || l.includes('keyCode=229'))), '変換中の keydown を観測').toBe(true)
  await expect(ime).toHaveValue(W.K + 'けんさく')
  // 確定後の通常 Enter では遷移する
  await pace(s.actor)
  const navigated = await Promise.all([page.waitForURL(/\/search\?q=/, { timeout: 30_000 }).then(() => true).catch(() => false), ime.press('Enter')]).then(([ok]) => ok)
  const afterLog = await page.evaluate(() => (window as unknown as { __imeLog?: string[] }).__imeLog ?? [])
  log({ step: 'ime after commit enter', navigated, url: page.url(), afterLog: afterLog.slice(-4) })
  expect(navigated, '確定後の通常 Enter で /search へ遷移する').toBe(true)
  await shot(page, 'T7-ime-after-commit-enter')

  // 狭幅（モバイル）: 虫眼鏡アイコン → 検索画面
  const m = await open(browser, W.actors.aMember, { viewport: { width: 390, height: 844 } })
  await gotoAndHydrate(m.page, '/')
  const icon = m.page.getByTestId('header-search-mobile-link')
  await expect(icon).toBeVisible({ timeout: 60_000 })
  await expect(m.page.getByTestId('header-search-input')).toBeHidden()
  await shot(m.page, 'T7-mobile-header')
  await icon.click()
  await m.page.waitForURL(/\/search$/, { timeout: 60_000 })
  await expect(m.page.getByTestId('global-search-input')).toBeVisible({ timeout: 60_000 })
  await shot(m.page, 'T7-mobile-search-page')
  await m.context.close()
  await s.context.close()
})

// ---------- 5. URL 同期 ----------
test('T8 URL 同期: q・type が URL に残り、再読込で復元、戻る/進むで前の検索語・種別が復元する', async ({ browser }) => {
  test.setTimeout(600_000)
  const s = await open(browser, W.actors.aMember)
  const page = s.page
  await gotoAndHydrate(page, '/')
  await searchFromHeader(s, W.K)
  await expect(page).toHaveURL(new RegExp('/search\\?q=' + W.K + '$'))
  await pace(s.actor)
  await Promise.all([page.waitForResponse(isSearchResponse, { timeout: 90_000 }), page.getByTestId('global-search-tab-ARTICLE').click()])
  await settled(page)
  await expect(page).toHaveURL(new RegExp('/search\\?q=' + W.K + '&type=ARTICLE$'))
  // 再読込で復元
  await pace(s.actor)
  await Promise.all([page.waitForResponse(isSearchResponse, { timeout: 90_000 }), page.reload({ waitUntil: 'commit' })])
  await settled(page)
  await expect(page.getByTestId('global-search-input')).toHaveValue(W.K)
  await expect(page.getByTestId('global-search-tab-ARTICLE')).toHaveAttribute('aria-selected', 'true')
  await expect(cards(page).filter({ hasText: /sidea-artmem/i })).toHaveCount(1)
  await shot(page, 'T8-after-reload')
  // ヘッダーから別の語で再検索（type は引き継ぐ）
  const K2 = W.K + ' sidea'
  await searchFromHeader(s, K2)
  await expect(page).toHaveURL(/type=ARTICLE/)
  expect(decodeURIComponent(new URL(page.url()).searchParams.get('q') ?? '')).toBe(K2)
  // 戻る → 前の検索語・種別
  await page.goBack()
  await expect(page).toHaveURL(new RegExp('/search\\?q=' + W.K + '&type=ARTICLE$'))
  await expect(page.getByTestId('global-search-input')).toHaveValue(W.K)
  await expect(page.getByTestId('global-search-tab-ARTICLE')).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByTestId('header-search-input')).toHaveValue(W.K)
  await shot(page, 'T8-after-back')
  // 進む → 後の検索語
  await page.goForward()
  await expect(page).toHaveURL(/type=ARTICLE/)
  await expect(page.getByTestId('global-search-input')).toHaveValue(K2)
  await expect(page.getByTestId('header-search-input')).toHaveValue(K2)
  await shot(page, 'T8-after-forward')
  log({ step: 'T8 search calls', urls: s.searchUrls, statuses: s.searchStatuses })
  expect.soft(s.searchStatuses.filter((x) => x !== 200), '検索 API が 200 以外を返していない').toEqual([])
  await s.context.close()
})
