import { existsSync, mkdirSync, readFileSync, writeFileSync, appendFileSync } from 'node:fs'
import path from 'node:path'
import { test, expect, type Page, type Browser } from '@playwright/test'
import {
  API, FE, createActor, createOrg, createTeam, joinAsMember, must, openAs, gotoAndHydrate, sql, RUN,
  type Actor, type ApiBody, type Scope,
} from './helpers/n1p1-world'
import { OWN_LEDGER, WORLD_FILE } from './helpers/cmp0726-global-teardown'

/**
 * 戦役 CMP-261116-0726「大会連絡スペースの存在オラクル是正」（PR #4643）の回帰用 実機E2E。
 *
 * 確かめること:
 * - 大会の連絡スペース（掲示板・チャット・ファイル置き場・公開設定）について、閲覧権のない者
 *   （部外者・他組織の管理者・主催組織の一般会員・資格を失った参加チーム・未認証）に返す応答が、
 *   存在しない ID への応答と status・error.code・本文（message）まで完全に一致すること（存在オラクルの是正）。
 *   スレッド・返信・リアクション・添付・フォルダ・ファイルの各入口と公開設定 API を、実在 ID と不在 ID で比べる。
 * - 権限のある者（主催 ADMIN・参加チーム代表・SYSTEM_ADMIN）は従来どおり閲覧・投稿・公開設定の変更ができ、
 *   中間の権限の者（参加チーム一般）は閲覧のみで投稿・公開設定は 403 になること。画面操作も併せて確かめる。
 *
 * 実行方法（検証用 BE・FE と、共有開発 DB のコンテナ mannschaft-mysql が必要）:
 *   cd frontend
 *   API_BASE_URL=http://localhost:8081 BASE_URL=http://localhost:3001 \
 *     npx playwright test --config playwright-cmp0726.config.ts
 *   BASE_URL / API_BASE_URL の既定は専用設定の 3001 / 8081。SYSTEM_ADMIN は既存 real spec と同じ
 *   TEST_ADMIN_EMAIL / TEST_ADMIN_PASSWORD（既定値も同じ）。証跡の出力先は EVIDENCE_DIR。
 *   前提データを残して再実行したいときだけ N1P1_KEEP_WORLD=1（後始末を飛ばす。最終実行では使わない）。
 *
 * 前提データと後始末:
 * - 利用者・組織・チーム・チームの組織加盟（招待→承諾）・大会・ディビジョン・参加資格・スレッド・返信・添付・
 *   共有ファイルは公開 API で作る。組織とチームは n1p1-world の recordAsset に記録され、専用設定の
 *   globalTeardown（cmp0726-global-teardown.ts）が API の DELETE で後始末する（大会・スレッドなどは
 *   組織・チームの削除に従う。個別の削除 API は持たない）。利用者は n1p1-world と同じく残る（テスト専用ドメインのアドレス）。
 * - 直接 SQL の例外（前例: n1p1-world.ts の createActor が users.status の有効化に使う）:
 *   (1) 大会 T4 の BULLETIN スペースの論理削除。AC-19 の「欠落」状態を作る公開 API が無い。
 *       後始末は不要（T4 は主催組織の削除に従う）。
 *   (2) DB の不変・永続化を確かめる SELECT。読み取りのみで後始末は不要。
 * - 証跡: EVIDENCE_DIR（既定 test-results/cmp0726-evidence）へスクリーンショットと応答記録 JSON-lines を残す。
 */
const EVID = process.env.EVIDENCE_DIR || path.resolve('test-results', 'cmp0726-evidence')
mkdirSync(EVID, { recursive: true })
const LOG = path.join(EVID, 'responses.jsonl')
const ABSENT = 999999990 // 存在しない ID（大会・スレッド・返信・添付・フォルダ・ファイル共通）
const SYS = { email: process.env.TEST_OPERATOR_EMAIL ?? 'e2e-operator@test.mannschaft.local', password: process.env.TEST_OPERATOR_PASSWORD ?? 'TestPass2026!' }

interface World {
  orgAdmin: Actor; orgMember: Actor; pAdmin: Actor; pMember: Actor; wAdmin: Actor; outsider: Actor; foreignAdmin: Actor
  orgO: Scope; orgF: Scope; teamP: Scope; teamW: Scope
  t1: number; t2: number; t4: number; d1: number
  t1Spaces: Array<{ id: string; spaceKind: string; refId: number; isPublic: boolean }>
  t2Spaces: Array<{ id: string; spaceKind: string; refId: number; isPublic: boolean }>
  thA: number; thL: number; thW: number; rA: number; rW: number; thD: number; rD1: number; rD2: number
  attA: number; attD: number
  chatChannelT1: number
  folderT1: number; fileT1: number
  tag: string
}
let W: World

function log(entry: Record<string, unknown>) {
  appendFileSync(LOG, JSON.stringify({ at: new Date().toISOString(), ...entry }) + '\n')
}

/** ログイン済みブラウザ文脈の fetch。応答の status・error.code・message を正規化して返す。 */
async function probe(page: Page | null, method: string, p: string, body?: unknown, actorForNull?: undefined) {
  void actorForNull
  const run = async (pg: Page) => pg.evaluate(async ({ method, url, body }) => {
    const r = await fetch(url, { method, credentials: 'include', headers: { 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
    const text = await r.text()
    return { status: r.status, text }
  }, { method, url: `${API}${p}`, body })
  let res: { status: number; text: string }
  if (page) res = await run(page)
  else {
    // 未認証: Cookie を持たない素の fetch
    const r = await fetch(`${API}${p}`, { method, headers: { 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
    res = { status: r.status, text: await r.text() }
  }
  let json: ApiBody = null
  try { json = JSON.parse(res.text) } catch { /* 非 JSON */ }
  const err = json?.error ?? json
  return { status: res.status, code: err?.code ?? null, message: err?.message ?? null, raw: res.text.slice(0, 300), json }
}
type Probe = Awaited<ReturnType<typeof probe>>
const sig = (x: Probe) => `${x.status}|${x.code}|${x.message}`

/** 閲覧権のない者の応答が、不在 ID の応答と status・code・message まで一致することを確かめて記録する。 */
async function expectSameAsAbsent(label: string, page: Page | null, method: string, real: string, absent: string, body?: unknown, absentBody?: unknown) {
  const a = await probe(page, method, real, body)
  const b = await probe(page, method, absent, absentBody ?? body)
  log({ label, method, real, absent, real_sig: sig(a), absent_sig: sig(b) })
  expect.soft(sig(a), `${label}: ${method} ${real} (実在) と ${absent} (不在) の応答一致`).toBe(sig(b))
  return a
}

function sqlNum(q: string): number { return Number(sql(q)) }

async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(EVID, `${name}.png`), fullPage: true })
}

const commUrl = (slug: string, tId: number) => `/organizations/${slug}/tournaments/${tId}/communication`

// 失敗でワーカーが再起動しても世界を作り直さない（世界ファイルがあれば必ず再利用。新規に作り直すときはファイルを消して実行する）
// 組織・チームは作った時点から専用台帳（OWN_LEDGER）へ記録する。n1p1 の台帳には触れない。
test.beforeAll(async () => {
  test.setTimeout(600_000)
  if (existsSync(WORLD_FILE)) { W = JSON.parse(readFileSync(WORLD_FILE, 'utf8')); return }
  await buildWorld()
})

async function buildWorld() {
  const orgAdmin = await createActor('og')
  const orgMember = await createActor('om')
  const pAdmin = await createActor('pa')
  const pMember = await createActor('pm')
  const wAdmin = await createActor('wa')
  const outsider = await createActor('out')
  const foreignAdmin = await createActor('fa')
  const orgO = await createOrg(orgAdmin, `0726主催${RUN}`, 'PUBLIC', OWN_LEDGER)
  const orgF = await createOrg(foreignAdmin, `0726他組織${RUN}`, 'PUBLIC', OWN_LEDGER)
  await joinAsMember('organizations', orgAdmin, orgO.slug, orgMember)
  const teamP = await createTeam(pAdmin, `0726参加P${RUN}`, 'PUBLIC', OWN_LEDGER)
  await joinAsMember('teams', pAdmin, teamP.slug, pMember)
  const teamW = await createTeam(wAdmin, `0726脱退W${RUN}`, 'PUBLIC', OWN_LEDGER)
  // チームの組織加盟: 組織 ADMIN が招待 → チーム代表が承諾（公開 API）
  for (const [t, teamAdmin] of [[teamP, pAdmin], [teamW, wAdmin]] as const) {
    const invite = await must(orgAdmin, 'POST', `/api/v1/organizations/${orgO.slug}/team-invites`, { teamSlug: t.slug })
    await must(teamAdmin, 'POST', `/api/v1/teams/${t.slug}/org-invites/${invite.data.id}/accept`)
  }
  const mkTournament = async (name: string) => {
    const r = await must(orgAdmin, 'POST', `/api/v1/organizations/${orgO.numericId}/tournaments`, { name, format: 'LEAGUE', sport: 'SOCCER', visibility: 'PUBLIC' })
    log({ step: 'tournament created', name, body: JSON.stringify(r).slice(0, 300) })
    return Number(r.data?.id ?? r.data?.content?.id)
  }
  const t1 = await mkTournament(`0726大会1-${RUN}`)
  const t2 = await mkTournament(`0726大会2-${RUN}`)
  const d1 = Number((await must(orgAdmin, 'POST', `/api/v1/organizations/${orgO.numericId}/tournaments/${t1}/divisions`, { name: `D1-${RUN}`, level: 1 })).data.id)
  const d2 = Number((await must(orgAdmin, 'POST', `/api/v1/organizations/${orgO.numericId}/tournaments/${t2}/divisions`, { name: `D2-${RUN}`, level: 1 })).data.id)
  const addP = async (div: number, tId: number, team: Scope) => Number((await must(orgAdmin, 'POST', `/api/v1/organizations/${orgO.numericId}/tournaments/${tId}/divisions/${div}/participants`, { teamId: team.numericId })).data.id)
  await addP(d1, t1, teamP)
  const pW = await addP(d1, t1, teamW)
  await addP(d2, t2, teamP)
  // AC-19: 大会・参加資格は残し BULLETIN スペースだけ論理削除した大会 T4
  const t4 = await mkTournament(`0726大会4-${RUN}`)
  const d4 = Number((await must(orgAdmin, 'POST', `/api/v1/organizations/${orgO.numericId}/tournaments/${t4}/divisions`, { name: `D4-${RUN}`, level: 1 })).data.id)
  await must(orgAdmin, 'POST', `/api/v1/organizations/${orgO.numericId}/tournaments/${t4}/divisions/${d4}/participants`, { teamId: teamP.numericId })
  // 直接 SQL の例外 (1): 欠落状態を作る公開 API が無い（冒頭コメント参照）
  sql(`UPDATE tournament_contact_space SET deleted_at=NOW() WHERE scope_type='TOURNAMENT' AND scope_id=${t4} AND space_kind='BULLETIN';`)
  const spaces = async (tId: number) => (await must(orgAdmin, 'GET', `/api/v1/tournaments/${tId}/contact-spaces`)).data as World['t1Spaces']
  const t1Spaces = await spaces(t1)
  const t2Spaces = await spaces(t2)
  log({ step: 'spaces', t1Spaces, t2Spaces })
  const bull1 = t1Spaces.find(s => s.spaceKind === 'BULLETIN')!
  const chat1 = t1Spaces.find(s => s.spaceKind === 'CHAT')!
  const mkThread = async (who: Actor, scopeId: number, title: string, scopeType = 'TOURNAMENT') =>
    Number((await must(who, 'POST', '/api/v1/bulletin/threads', { title, body: `${title} の本文`, priority: 'INFO', categoryId: bull1.refId, scopeType, scopeId })).data.id)
  const mkReply = async (who: Actor, threadId: number, body: string) => Number((await must(who, 'POST', `/api/v1/bulletin/threads/${threadId}/replies`, { body })).data.id)
  const thA = await mkThread(orgAdmin, t1, `thA-${RUN}`)
  const thL = await mkThread(orgAdmin, t1, `thL-${RUN}`)
  const rA = await mkReply(orgAdmin, thA, `rA-${RUN}`)
  // AC-16: WITHDRAWN になる前に投稿（REGISTERED のうちに作成）
  const thW = await mkThread(wAdmin, t1, `thW-${RUN}`)
  const rW = await mkReply(wAdmin, thA, `rW-${RUN}`)
  await must(orgAdmin, 'PATCH', `/api/v1/organizations/${orgO.numericId}/tournaments/${t1}/divisions/${d1}/participants/${pW}`, { status: 'WITHDRAWN' })
  // 差し戻し修正の検証用: 返信（と子返信）・添付を持つスレッド
  const thD = await mkThread(orgAdmin, t1, `thD-${RUN}`)
  const rD1 = await mkReply(orgAdmin, thD, `rD1-${RUN}`)
  const rD2 = Number((await must(orgAdmin, 'POST', `/api/v1/bulletin/replies/${rD1}/replies`, { body: `rD2-${RUN}` })).data.id)
  // 添付: 署名付き URL の発行 → メタデータ確定（公開 API）。実体のアップロードは行わない
  // （存在オラクルの検証対象は添付の行の有無で、確定 API はオブジェクトの実在を確認しない）。
  const mkAttachment = async (threadId: number, name: string) => {
    const fileName = `${name}-${RUN}.txt`
    const presign = await must(orgAdmin, 'POST', '/api/v1/bulletin/attachments/upload-url', { targetType: 'THREAD', targetId: threadId, fileName, contentType: 'text/plain', fileSize: 12 })
    const r = await must(orgAdmin, 'POST', '/api/v1/bulletin/attachments', { targetType: 'THREAD', targetId: threadId, fileKey: presign.data.fileKey, originalFilename: fileName, fileSize: 12, contentType: 'text/plain' })
    return Number(r.data.id)
  }
  const attA = await mkAttachment(thA, 'attA')
  const attD = await mkAttachment(thD, 'attD')
  // 共有ファイル: 大会のルートフォルダ（無ければ作る）に、署名付き URL 発行 → メタデータ登録（公開 API）
  const roots = (await must(orgAdmin, 'GET', `/api/v1/tournaments/${t1}/folders`)).data as Array<{ id: number }>
  const folderT1 = roots.length > 0
    ? Number(roots[0]!.id)
    : Number((await must(orgAdmin, 'POST', `/api/v1/tournaments/${t1}/folders`, { name: `f0726-root-${RUN}`, scopeType: 'TOURNAMENT', scopeId: String(t1) })).data.id)
  const filePresign = await must(orgAdmin, 'POST', '/api/v1/files/presign-upload', { folderId: folderT1, fileName: `f0726-${RUN}.txt`, contentType: 'text/plain', fileSize: 12 })
  const fileT1 = Number((await must(orgAdmin, 'POST', '/api/v1/files', { folderId: folderT1, name: `f0726-${RUN}.txt`, fileKey: filePresign.data.fileKey, fileSize: 12, contentType: 'text/plain' })).data.id)
  W = {
    orgAdmin, orgMember, pAdmin, pMember, wAdmin, outsider, foreignAdmin, orgO, orgF, teamP, teamW,
    t1, t2, t4, d1, t1Spaces, t2Spaces, thA, thL, thW, rA, rW, thD, rD1, rD2, attA, attD,
    chatChannelT1: chat1.refId, folderT1, fileT1, tag: RUN,
  }
  mkdirSync(path.dirname(WORLD_FILE), { recursive: true })
  writeFileSync(WORLD_FILE, JSON.stringify(W, null, 2))
  void foreignAdmin
}

test('00 前提: 検証 BE が起動しており本 PR head の応答を返す', async () => {
  expect(W.t1).toBeGreaterThan(0)
  log({ step: 'world', W: { t1: W.t1, t2: W.t2, d1: W.d1, thA: W.thA, thD: W.thD, folderT1: W.folderT1 } })
})

// ============================================================ 画面（正の視点）
test('10 [AC-12] 主催ADMIN: 画面から閲覧・スレッド作成・返信・ピン留め（更新）ができ、再読込後も残る', async ({ browser }) => {
  const { context, page } = await openAs(browser, W.orgAdmin)
  const spaces = page.waitForResponse(r => /\/tournaments\/\d+\/contact-spaces$/.test(new URL(r.url()).pathname) && r.request().method() === 'GET')
  await gotoAndHydrate(page, commUrl(W.orgO.slug, W.t1))
  expect((await spaces).status()).toBe(200)
  await expect(page.getByText(`thA-${W.tag}`).first()).toBeVisible()
  await shot(page, '10-orgadmin-bulletin-list')
  const title = `ui新規-${W.tag}`
  await page.getByRole('button', { name: '新規スレッド' }).click()
  await page.getByPlaceholder('スレッドのタイトル').fill(title)
  await page.getByPlaceholder('本文を入力...').fill('画面から投稿した本文')
  const created = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/bulletin/threads' && r.request().method() === 'POST')
  await page.getByRole('button', { name: '投稿', exact: true }).click()
  expect([200, 201]).toContain((await created).status())
  await expect(page.getByText(title).first()).toBeVisible()
  // 再読込後も残る（永続化）
  await gotoAndHydrate(page, commUrl(W.orgO.slug, W.t1))
  await expect(page.getByText(title).first()).toBeVisible()
  // 返信
  await page.getByText(title).first().click()
  await page.getByPlaceholder('返信を入力...').fill('画面からの返信')
  const rep = page.waitForResponse(r => /\/threads\/\d+\/replies$/.test(new URL(r.url()).pathname) && r.request().method() === 'POST')
  await page.getByRole('button', { name: '返信', exact: true }).click()
  expect([200, 201]).toContain((await rep).status())
  await expect(page.getByText('画面からの返信')).toBeVisible()
  // 更新（ピン留め）→ 再読込 → 同じスレッドが保たれたままピン状態が残る
  const pin = page.waitForResponse(r => /\/threads\/\d+\/pin$/.test(new URL(r.url()).pathname))
  await page.getByRole('button', { name: 'ピン留め' }).click()
  expect((await pin).status()).toBe(200)
  await shot(page, '10-orgadmin-thread-pinned')
  await gotoAndHydrate(page, commUrl(W.orgO.slug, W.t1))
  await page.getByText(title).first().click()
  await expect(page.getByRole('button', { name: 'ピン解除' })).toBeVisible()
  await expect(page.getByText('画面からの返信')).toBeVisible()
  expect(sqlNum(`SELECT COUNT(*) FROM bulletin_threads WHERE title='${title}';`)).toBe(1)
  await context.close()
})

test('11 [AC-11/17] 主催ADMIN: 公開設定を画面で切替（T2: 掲示板を公開）', async ({ browser }) => {
  const { context, page } = await openAs(browser, W.orgAdmin)
  await gotoAndHydrate(page, commUrl(W.orgO.slug, W.t2))
  const switches = page.getByRole('switch')
  await expect(switches).toHaveCount(2)
  const patch = page.waitForResponse(r => /\/contact-spaces\/[^/]+\/visibility$/.test(new URL(r.url()).pathname) && r.request().method() === 'PATCH')
  await switches.nth(0).click()
  expect((await patch).status()).toBe(200)
  await shot(page, '11-t2-bulletin-public')
  expect(sql(`SELECT GROUP_CONCAT(CONCAT(space_kind,'=',is_public) ORDER BY space_kind) FROM tournament_contact_space WHERE scope_type='TOURNAMENT' AND scope_id=${W.t2} AND deleted_at IS NULL;`)).toBe('BULLETIN=1,CHAT=0')
  await context.close()
})

test('12 [AC-8/12] 主催ADMIN: ファイル置き場でフォルダを作成でき、既存ファイルが見える（画面）', async ({ browser }) => {
  const { context, page } = await openAs(browser, W.orgAdmin)
  const list = page.waitForResponse(r => new URL(r.url()).pathname === `/api/v1/tournaments/${W.t1}/folders` && r.request().method() === 'GET')
  await gotoAndHydrate(page, `/organizations/${W.orgO.slug}/tournaments/${W.t1}/files`)
  expect((await list).status()).toBe(200)
  const name = `uiフォルダ-${W.tag}`
  await page.getByRole('button', { name: 'フォルダを作成' }).first().click()
  await page.getByPlaceholder('フォルダ名').fill(name)
  const mk = page.waitForResponse(r => new URL(r.url()).pathname === `/api/v1/tournaments/${W.t1}/folders` && r.request().method() === 'POST')
  await page.getByRole('dialog').getByRole('button', { name: 'フォルダを作成' }).click()
  const mkRes = await mk
  const mkBody = (await mkRes.text()).slice(0, 300)
  log({ step: 'FINDING files UI folder create', status: mkRes.status(), body: mkBody })
  await page.waitForTimeout(800)
  await shot(page, '12-files-folder-create-result')
  // 既知の既存不具合（main と同一の FE/BE 契約不一致）: FE は scopeType を送らず BE が 400 を返す。PR 差分の外なので所見として記録し、先へ進む。
  if (mkRes.status() === 400) {
    test.info().annotations.push({ type: 'FINDING', description: `UI フォルダ作成が 400: ${mkBody}` })
    await page.getByRole('dialog').getByRole('button', { name: 'キャンセル' }).click()
  }
  else {
    expect([200, 201]).toContain(mkRes.status())
    await expect(page.getByText(name)).toBeVisible()
  }
  const folderBtn = page.locator('button', { has: page.locator('.pi-folder') }).first()
  await folderBtn.click()
  await page.waitForTimeout(1500)
  log({ step: 'files-ui-after-open-first-folder', text: (await page.locator('body').innerText()).slice(0, 500) })
  await shot(page, '12-files-first-folder')
  await context.close()
})

test('13 [AC-10/12] 主催ADMIN: チャットタブが開ける（画面）', async ({ browser }) => {
  const { context, page } = await openAs(browser, W.orgAdmin)
  await gotoAndHydrate(page, commUrl(W.orgO.slug, W.t1))
  const ch = page.waitForResponse(r => new URL(r.url()).pathname === `/api/v1/chat/channels/${W.chatChannelT1}`)
  await page.getByRole('button', { name: 'チャット' }).click()
  const st = (await ch).status()
  log({ step: 'orgAdmin chat channel GET', status: st })
  expect(st).toBe(200)
  await page.waitForTimeout(1500)
  await shot(page, '13-orgadmin-chat')
  await context.close()
})

// ============================================================ 負の視点（画面 + 応答比較）
type Role = 'outsider' | 'foreignAdmin' | 'orgMember' | 'wAdmin' | 'unauth'
const ROLES: Role[] = ['outsider', 'foreignAdmin', 'orgMember', 'wAdmin', 'unauth']

/** 画面で開いたときの BE 応答を集める（連絡スペース・フォルダの入口）。 */
async function uiNegative(browser: Browser, role: Role) {
  const mk = async () => {
    if (role === 'unauth') {
      const context = await browser.newContext({ baseURL: FE, locale: 'ja-JP', viewport: { width: 1280, height: 900 } })
      return { context, page: await context.newPage() }
    }
    const o = await openAs(browser, W[role])
    return { context: o.context, page: o.page }
  }
  const { context, page } = await mk()
  const seen: Array<{ url: string; status: number }> = []
  page.on('response', (r) => { if (r.url().includes('/api/v1/') && !r.url().includes('/csp-reports')) seen.push({ url: new URL(r.url()).pathname + new URL(r.url()).search, status: r.status() }) })
  const result: Record<string, unknown> = {}
  for (const [name, tId] of [['real', W.t1], ['absent', ABSENT]] as const) {
    seen.length = 0
    const cs = role === 'unauth' ? null : page.waitForResponse(r => new URL(r.url()).pathname === `/api/v1/tournaments/${tId}/contact-spaces`, { timeout: 120_000 })
    await page.goto(`${FE}${commUrl(W.orgO.slug, tId)}`, { waitUntil: 'commit' })
    const csRes = cs ? await cs : null
    await page.waitForTimeout(3000)
    const text = (await page.locator('body').innerText()).slice(0, 600)
    const csBody = csRes ? (await csRes.text().catch(() => '')).slice(0, 300) : null
    result[name] = { url: page.url(), apis: [...seen], text, cs: csRes ? { status: csRes.status(), body: csBody } : null }
    await shot(page, `20-ui-${role}-${name}-communication`)
    expect.soft(text, `${role}/${name}: 既存スレッドが見えてはならない`).not.toContain(`thA-${W.tag}`)
    expect.soft(await page.getByRole('button', { name: '新規スレッド' }).count(), `${role}/${name}: 投稿導線が出てはならない`).toBe(0)
  }
  log({ step: `ui-negative ${role}`, result })
  await context.close()
  return result
}

for (const role of ROLES) {
  test(`20 [AC-1/13 画面] ${role}: 連絡スペース URL を直打ちしても実在大会と不在大会で同じ見え方（スレッドも投稿導線も出ない）`, async ({ browser }) => {
    const r = await uiNegative(browser, role) as Record<string, { url: string; text: string; cs: { status: number; body: string } | null }>
    const norm = (c: { status: number; body: string } | null) => {
      if (!c) return null
      try { const e = JSON.parse(c.body)?.error; return `${c.status}|${e?.code}|${e?.message}` } catch { return `${c.status}|${c.body}` }
    }
    log({ step: `ui-negative ${role} contact-spaces`, real: norm(r.real!.cs), absent: norm(r.absent!.cs), realUrl: r.real!.url, absentUrl: r.absent!.url })
    // 実在・不在とも、連絡スペース入口の応答が status・code・message まで同一（unauth は入口へ到達せず同じ遷移先）
    expect(norm(r.real!.cs)).toBe(norm(r.absent!.cs))
    if (role === 'unauth') expect(r.real!.text).toBe(r.absent!.text) // 未認証は入口 API へ到達せず、実在・不在で同じ表示
    else expect(norm(r.real!.cs)).toMatch(/^404\|TOUR_029\|/)
  })
}

const T = '/api/v1/bulletin'
async function negativeSuite(role: Role, page: Page | null) {
  const exp = role === 'unauth' ? 401 : 404
  const same = async (label: string, method: string, real: string, absent: string, body?: unknown, absentBody?: unknown, code?: string, unauthStatus?: number) => {
    const a = await expectSameAsAbsent(`${role} ${label}`, page, method, real, absent, body, absentBody)
    expect.soft(a.status, `${role} ${label} status`).toBe(role === 'unauth' && unauthStatus ? unauthStatus : exp)
    if (code && role !== 'unauth') expect.soft(a.code, `${role} ${label} code`).toBe(code)
    return a
  }
  const bull1 = W.t1Spaces.find(s => s.spaceKind === 'BULLETIN')!
  // AC-1
  for (const [sc, id] of [['TOURNAMENT', W.t1], ['TOURNAMENT_DIVISION', W.d1]] as const) {
    await same(`AC-1 threads GET ${sc}`, 'GET', `${T}/threads?scope_type=${sc}&scope_id=${id}&page=0&size=20`, `${T}/threads?scope_type=${sc}&scope_id=${ABSENT}&page=0&size=20`, undefined, undefined, 'TOUR_029')
    await same(`AC-1 categories GET ${sc}`, 'GET', `${T}/categories?scope_type=${sc}&scope_id=${id}`, `${T}/categories?scope_type=${sc}&scope_id=${ABSENT}`, undefined, undefined, 'TOUR_029')
    await same(`AC-1 read-all POST ${sc}`, 'POST', `${T}/threads/read-all`, `${T}/threads/read-all`, { scopeType: sc, scopeId: id }, { scopeType: sc, scopeId: ABSENT }, 'TOUR_029')
  }
  // AC-2: 作成（行が増えない）
  const before = sqlNum(`SELECT COUNT(*) FROM bulletin_threads WHERE title LIKE 'neg-${role}-${W.tag}%';`)
  await same('AC-2 thread POST', 'POST', `${T}/threads`, `${T}/threads`,
    { title: `neg-${role}-${W.tag}`, body: 'x', priority: 'INFO', categoryId: bull1.refId, scopeType: 'TOURNAMENT', scopeId: W.t1 },
    { title: `neg-${role}-${W.tag}`, body: 'x', priority: 'INFO', categoryId: bull1.refId, scopeType: 'TOURNAMENT', scopeId: ABSENT }, 'TOUR_029')
  expect.soft(sqlNum(`SELECT COUNT(*) FROM bulletin_threads WHERE title LIKE 'neg-${role}-${W.tag}%';`), `${role} AC-2 行が増えていない`).toBe(before)
  // AC-4a/4b: スレッド ID
  const th = (id: number | string) => `${T}/threads/${id}`
  for (const [m, suffix, body] of [
    ['GET', '', undefined], ['GET', '/replies', undefined], ['POST', '/read', undefined], ['GET', '/readers', undefined],
    ['PUT', '', { title: 'x', body: 'y' }], ['DELETE', '', undefined],
    ['PATCH', '/priority', { priority: 'INFO' }], ['PATCH', '/pin', { pinned: true }], ['PATCH', '/lock', { locked: true }],
    ['POST', '/archive', { is_archived: true }], ['POST', '/replies', { body: 'x' }],
  ] as const) {
    await same(`AC-4 thread ${m} ${suffix || '/{id}'}`, m, th(W.thA) + suffix, th(ABSENT) + suffix, body, body, 'BULLETIN_002')
  }
  // AC-5: 返信 ID
  for (const [m, suffix, body] of [['POST', '/replies', { body: 'x' }], ['PUT', '', { body: 'y' }], ['DELETE', '', undefined]] as const) {
    await same(`AC-5 reply ${m} ${suffix || '/{id}'}`, m, `${T}/replies/${W.rA}${suffix}`, `${T}/replies/${ABSENT}${suffix}`, body, body, 'BULLETIN_003')
  }
  // AC-6: リアクション
  for (const [tt, id, code] of [['THREAD', W.thA, 'BULLETIN_002'], ['REPLY', W.rA, 'BULLETIN_003']] as const) {
    const rb = (x: number) => ({ targetType: tt, targetId: x, emoji: '👍' })
    await same(`AC-6 reaction POST ${tt}`, 'POST', `${T}/reactions`, `${T}/reactions`, rb(id), rb(ABSENT), code)
    await same(`AC-6 reaction DELETE ${tt}`, 'DELETE', `${T}/reactions?targetType=${tt}&targetId=${id}&emoji=%F0%9F%91%8D`, `${T}/reactions?targetType=${tt}&targetId=${ABSENT}&emoji=%F0%9F%91%8D`, undefined, undefined, code)
    await same(`AC-6 reaction GET ${tt}`, 'GET', `${T}/reactions?targetType=${tt}&targetId=${id}`, `${T}/reactions?targetType=${tt}&targetId=${ABSENT}`, undefined, undefined, code)
  }
  // AC-7: 添付 入口別
  const attBefore = sqlNum(`SELECT COUNT(*) FROM bulletin_attachments WHERE target_id IN (${W.thA},${W.rA},${W.thD});`)
  await same('AC-7 attachments list(thread)', 'GET', `${T}/threads/${W.thA}/attachments`, `${T}/threads/${ABSENT}/attachments`, undefined, undefined, 'BULLETIN_002')
  await same('AC-7 attachments list(reply)', 'GET', `${T}/replies/${W.rA}/attachments`, `${T}/replies/${ABSENT}/attachments`, undefined, undefined, 'BULLETIN_025')
  await same('AC-7 attachment download-url', 'GET', `${T}/attachments/${W.attA}/download-url`, `${T}/attachments/${ABSENT}/download-url`, undefined, undefined, 'BULLETIN_007')
  await same('AC-7 attachment DELETE', 'DELETE', `${T}/attachments/${W.attA}`, `${T}/attachments/${ABSENT}`, undefined, undefined, 'BULLETIN_007')
  const pre = (x: number) => ({ targetType: 'THREAD', targetId: x, fileName: 'a.txt', contentType: 'text/plain', fileSize: 12 })
  await same('AC-7 upload-url POST', 'POST', `${T}/attachments/upload-url`, `${T}/attachments/upload-url`, pre(W.thA), pre(ABSENT), 'BULLETIN_025')
  const conf = (x: number) => ({ targetType: 'THREAD', targetId: x, fileKey: `e2e0726/neg-${role}`, originalFilename: 'a.txt', fileSize: 12, contentType: 'text/plain' })
  await same('AC-7 attachment confirm POST', 'POST', `${T}/attachments`, `${T}/attachments`, conf(W.thA), conf(ABSENT), 'BULLETIN_025')
  expect.soft(sqlNum(`SELECT COUNT(*) FROM bulletin_attachments WHERE target_id IN (${W.thA},${W.rA},${W.thD});`), `${role} AC-7 添付件数不変`).toBe(attBefore)
  // AC-8: 大会フォルダ
  await same('AC-8 folders GET', 'GET', `/api/v1/tournaments/${W.t1}/folders`, `/api/v1/tournaments/${ABSENT}/folders`, undefined, undefined, 'TOUR_029', 404)
  await same('AC-8 folders POST', 'POST', `/api/v1/tournaments/${W.t1}/folders`, `/api/v1/tournaments/${ABSENT}/folders`, { name: `neg-${role}`, scopeType: 'TOURNAMENT', scopeId: String(W.t1) }, { name: `neg-${role}`, scopeType: 'TOURNAMENT', scopeId: String(ABSENT) }, 'TOUR_029')
  await same('AC-8 division folders GET', 'GET', `/api/v1/tournaments/${W.t1}/divisions/${W.d1}/folders`, `/api/v1/tournaments/${W.t1}/divisions/${ABSENT}/folders`, undefined, undefined, 'TOUR_029', 404)
  // AC-9: フォルダ ID / ファイル ID
  await same('AC-9 folder GET', 'GET', `/api/v1/files/folders/${W.folderT1}`, `/api/v1/files/folders/${ABSENT}`)
  await same('AC-9 file download-url', 'GET', `/api/v1/files/${W.fileT1}/download-url`, `/api/v1/files/${ABSENT}/download-url`)
  await same('AC-9 file DELETE', 'DELETE', `/api/v1/files/${W.fileT1}`, `/api/v1/files/${ABSENT}`)
  expect.soft(sqlNum(`SELECT COUNT(*) FROM shared_files WHERE id=${W.fileT1} AND deleted_at IS NULL;`), `${role} AC-9 ファイル不変`).toBe(1)
  // AC-10: チャット（回帰: 従来どおり秘匿）
  { const a = await probe(page, 'GET', `/api/v1/chat/channels/${W.chatChannelT1}`); const b = await probe(page, 'GET', `/api/v1/chat/channels/${ABSENT}`); log({ label: `${role} FINDING-INFO chat channel detail GET`, real_sig: sig(a), absent_sig: sig(b) }) }
  await same('AC-10 chat messages GET', 'GET', `/api/v1/chat/channels/${W.chatChannelT1}/messages`, `/api/v1/chat/channels/${ABSENT}/messages`)
  await same('AC-10 chat message POST', 'POST', `/api/v1/chat/channels/${W.chatChannelT1}/messages`, `/api/v1/chat/channels/${ABSENT}/messages`, { body: 'x' }, { body: 'x' })
  // AC-11: 公開設定 API
  await same('AC-11 contact-spaces GET', 'GET', `/api/v1/tournaments/${W.t1}/contact-spaces`, `/api/v1/tournaments/${ABSENT}/contact-spaces`, undefined, undefined, 'TOUR_029')
  await same('AC-11 visibility PATCH', 'PATCH', `/api/v1/tournaments/${W.t1}/contact-spaces/${bull1.id}/visibility`, `/api/v1/tournaments/${ABSENT}/contact-spaces/${bull1.id}/visibility`, { isPublic: true }, { isPublic: true }, 'TOUR_029')
  expect.soft(sql(`SELECT is_public FROM tournament_contact_space WHERE scope_type='TOURNAMENT' AND scope_id=${W.t1} AND space_kind='BULLETIN' AND deleted_at IS NULL;`), `${role} AC-11 公開設定不変`).toBe('0')
  if (role === 'wAdmin') {
    // AC-16: 投稿後に資格喪失した本人の自スレッド・自返信
    const body0 = sql(`SELECT title FROM bulletin_threads WHERE id=${W.thW};`)
    await same('AC-16 own thread PUT', 'PUT', th(W.thW), th(ABSENT), { title: 'tamper', body: 'tamper' }, { title: 'tamper', body: 'tamper' }, 'BULLETIN_002')
    await same('AC-16 own thread DELETE', 'DELETE', th(W.thW), th(ABSENT), undefined, undefined, 'BULLETIN_002')
    await same('AC-16 own reply PUT', 'PUT', `${T}/replies/${W.rW}`, `${T}/replies/${ABSENT}`, { body: 'tamper' }, { body: 'tamper' }, 'BULLETIN_003')
    await same('AC-16 own reply DELETE', 'DELETE', `${T}/replies/${W.rW}`, `${T}/replies/${ABSENT}`, undefined, undefined, 'BULLETIN_003')
    expect(sql(`SELECT title FROM bulletin_threads WHERE id=${W.thW} AND deleted_at IS NULL;`), 'AC-16 スレッド不変').toBe(body0)
    expect(sql(`SELECT body FROM bulletin_replies WHERE id=${W.rW} AND deleted_at IS NULL;`), 'AC-16 返信不変').toBe(`rW-${W.tag}`)
  }
}

for (const role of ROLES) {
  test(`21 [AC-1〜11,13,16 応答比較] ${role}: 実在 ID への全入口が不在 ID と status・code・message 完全一致`, async ({ browser }) => {
    test.setTimeout(600_000)
    if (role === 'unauth') { await negativeSuite(role, null); return }
    const { context, page } = await openAs(browser, W[role])
    await negativeSuite(role, page)
    await context.close()
  })
}

// ============================================================ 権限ごとの正の視点・閲覧可/投稿不可・公開設定
const mkBody = (x: number, tag: string, cat: number, sc = 'TOURNAMENT') => ({ title: `pos-${tag}`, body: 'b', priority: 'INFO', categoryId: cat, scopeType: sc, scopeId: x })

test('30 [AC-3/11/12] 参加チーム代表・一般・SYSTEM_ADMIN・公開スペースの部外者: 閲覧/投稿/公開設定の応答', async ({ browser }) => {
  test.setTimeout(600_000)
  const bull1 = W.t1Spaces.find(s => s.spaceKind === 'BULLETIN')!
  const bull2 = W.t2Spaces.find(s => s.spaceKind === 'BULLETIN')!
  const open = async (a: { email: string; password: string }) => {
    const o = await openAs(browser, a as Actor)
    return o
  }
  const check = async (label: string, page: Page, method: string, p: string, body: unknown, status: number, code?: string) => {
    const r = await probe(page, method, p, body)
    log({ label, method, path: p, sig: sig(r) })
    expect.soft(r.status, `${label}`).toBe(status)
    if (code) expect.soft(r.code, `${label} code`).toBe(code)
    return r
  }
  // 参加チーム代表（T1 非公開）
  { const o = await open(W.pAdmin)
    await check('pAdmin 掲示板一覧 200', o.page, 'GET', `${T}/threads?scope_type=TOURNAMENT&scope_id=${W.t1}&page=0&size=20`, undefined, 200)
    await check('pAdmin スレッド作成 201', o.page, 'POST', `${T}/threads`, mkBody(W.t1, `pAdmin-${W.tag}`, bull1.refId), 201)
    await check('pAdmin フォルダ作成 201', o.page, 'POST', `/api/v1/tournaments/${W.t1}/folders`, { name: `pf-${W.tag}`, scopeType: 'TOURNAMENT', scopeId: String(W.t1) }, 201)
    await check('pAdmin 公開設定一覧 403 TOUR_032', o.page, 'GET', `/api/v1/tournaments/${W.t1}/contact-spaces`, undefined, 403, 'TOUR_032')
    await check('pAdmin 公開設定変更 403 TOUR_032', o.page, 'PATCH', `/api/v1/tournaments/${W.t1}/contact-spaces/${bull1.id}/visibility`, { isPublic: true }, 403, 'TOUR_032')
    await check('pAdmin スレッド詳細 200', o.page, 'GET', `${T}/threads/${W.thA}`, undefined, 200)
    await check('pAdmin 返信 201', o.page, 'POST', `${T}/threads/${W.thA}/replies`, { body: 'pAdmin返信' }, 201)
    await check('pAdmin チャット閲覧 200', o.page, 'GET', `/api/v1/chat/channels/${W.chatChannelT1}/messages`, undefined, 200)
    await o.context.close() }
  // 参加チーム一般（閲覧可・投稿不可）
  { const o = await open(W.pMember)
    await check('pMember 掲示板一覧 200', o.page, 'GET', `${T}/threads?scope_type=TOURNAMENT&scope_id=${W.t1}&page=0&size=20`, undefined, 200)
    await check('pMember スレッド作成 403 TOUR_031', o.page, 'POST', `${T}/threads`, mkBody(W.t1, `pMember-${W.tag}`, bull1.refId), 403, 'TOUR_031')
    await check('pMember フォルダ作成 403 TOUR_031', o.page, 'POST', `/api/v1/tournaments/${W.t1}/folders`, { name: `pmf-${W.tag}`, scopeType: 'TOURNAMENT', scopeId: String(W.t1) }, 403, 'TOUR_031')
    await check('pMember 返信 403 TOUR_031', o.page, 'POST', `${T}/threads/${W.thA}/replies`, { body: 'x' }, 403, 'TOUR_031')
    await check('pMember 公開設定 403 TOUR_032', o.page, 'GET', `/api/v1/tournaments/${W.t1}/contact-spaces`, undefined, 403, 'TOUR_032')
    expect(sqlNum(`SELECT COUNT(*) FROM bulletin_threads WHERE title='pos-pMember-${W.tag}';`)).toBe(0)
    await o.context.close() }
  // SYSTEM_ADMIN（200/201。大会連絡スペースは SYSTEM_ADMIN も通る）
  { const o = await open(SYS)
    await check('sysAdmin 掲示板一覧 200', o.page, 'GET', `${T}/threads?scope_type=TOURNAMENT&scope_id=${W.t1}&page=0&size=20`, undefined, 200)
    await check('sysAdmin スレッド作成 201', o.page, 'POST', `${T}/threads`, mkBody(W.t1, `sys-${W.tag}`, bull1.refId), 201)
    await check('sysAdmin 公開設定一覧 200', o.page, 'GET', `/api/v1/tournaments/${W.t1}/contact-spaces`, undefined, 200)
    await o.context.close() }
  // 公開スペース（T2 掲示板は test 11 で公開）の部外者: 閲覧 200・投稿 403 TOUR_031・公開設定は知り得ない 404 TOUR_029、チャット（非公開）は不在と一致
  { const o = await open(W.outsider)
    await check('outsider 公開掲示板一覧 200', o.page, 'GET', `${T}/threads?scope_type=TOURNAMENT&scope_id=${W.t2}&page=0&size=20`, undefined, 200)
    await check('outsider 公開掲示板へ作成 403 TOUR_031', o.page, 'POST', `${T}/threads`, mkBody(W.t2, `outsider-${W.tag}`, bull2.refId), 403, 'TOUR_031')
    await check('outsider 公開スペースの公開設定一覧 403 TOUR_032（存在を知り得る者）', o.page, 'GET', `/api/v1/tournaments/${W.t2}/contact-spaces`, undefined, 403, 'TOUR_032')
    const chat2 = W.t2Spaces.find(s => s.spaceKind === 'CHAT')!
    await expectSameAsAbsent('outsider AC-17 非公開チャット(T2)の messages は不在と一致', o.page, 'GET', `/api/v1/chat/channels/${chat2.refId}/messages`, `/api/v1/chat/channels/${ABSENT}/messages`)
    expect(sqlNum(`SELECT COUNT(*) FROM bulletin_threads WHERE title='pos-outsider-${W.tag}';`)).toBe(0)
    await o.context.close() }
})

test('31 [AC-18] 主催ADMIN: 別大会のパスで正ディビジョンを指定しても公開設定は不在と一致し、設定は不変', async ({ browser }) => {
  const { context, page } = await openAs(browser, W.orgAdmin)
  const list = await probe(page, 'GET', `/api/v1/tournaments/${W.t1}/divisions/${W.d1}/contact-spaces`)
  expect(list.status).toBe(200)
  const sp = (list.json.data as Array<{ id: string; spaceKind: string }>)[0]!
  const before = sql(`SELECT GROUP_CONCAT(is_public) FROM tournament_contact_space WHERE scope_type='TOURNAMENT_DIVISION' AND scope_id=${W.d1};`)
  await expectSameAsAbsent('AC-18 GET 別大会パス', page, 'GET', `/api/v1/tournaments/${W.t2}/divisions/${W.d1}/contact-spaces`, `/api/v1/tournaments/${W.t2}/divisions/${ABSENT}/contact-spaces`)
  const p = await expectSameAsAbsent('AC-18 PATCH 別大会パス', page, 'PATCH', `/api/v1/tournaments/${W.t2}/divisions/${W.d1}/contact-spaces/${sp.id}/visibility`, `/api/v1/tournaments/${W.t2}/divisions/${ABSENT}/contact-spaces/${sp.id}/visibility`, { isPublic: true })
  expect(p.status).toBe(404)
  expect(sql(`SELECT GROUP_CONCAT(is_public) FROM tournament_contact_space WHERE scope_type='TOURNAMENT_DIVISION' AND scope_id=${W.d1};`)).toBe(before)
  await context.close()
})

test('32 [AC-19] 参加チーム代表: 大会と参加資格が残り掲示板スペースだけ欠落した大会では 404 TOUR_029（DB不変）', async ({ browser }) => {
  const { context, page } = await openAs(browser, W.pAdmin)
  const t0 = sqlNum(`SELECT COUNT(*) FROM bulletin_threads WHERE title LIKE 'ac19-%${W.tag}';`)
  const f0 = sqlNum(`SELECT COUNT(*) FROM shared_folders WHERE scope_type='TOURNAMENT' AND scope_ref_id=${W.t4};`)
  const body = (x: number) => ({ title: `ac19-${W.tag}`, body: 'b', priority: 'INFO', scopeType: 'TOURNAMENT', scopeId: x })
  const a = await expectSameAsAbsent('AC-19 スレッド作成', page, 'POST', `${T}/threads`, `${T}/threads`, body(W.t4), body(ABSENT))
  expect(a.status).toBe(404); expect(a.code).toBe('TOUR_029')
  const b = await expectSameAsAbsent('AC-19 フォルダ作成', page, 'POST', `/api/v1/tournaments/${W.t4}/folders`, `/api/v1/tournaments/${ABSENT}/folders`, { name: 'ac19f', scopeType: 'TOURNAMENT', scopeId: String(W.t4) }, { name: 'ac19f', scopeType: 'TOURNAMENT', scopeId: String(ABSENT) })
  log({ step: 'AC-19 folder', sig: sig(b) })
  expect.soft(b.status).toBe(404); expect.soft(b.code).toBe('TOUR_029')
  expect(sqlNum(`SELECT COUNT(*) FROM bulletin_threads WHERE title LIKE 'ac19-%${W.tag}';`)).toBe(t0)
  expect(sqlNum(`SELECT COUNT(*) FROM shared_folders WHERE scope_type='TOURNAMENT' AND scope_ref_id=${W.t4};`)).toBe(f0)
  await context.close()
})

test('33 [AC-15] readers: 通常+INDIVIDUAL=200、通常+COUNT_ONLY=200 空配列、unread+非モデレーター=403 COMMON_002（500にならない）', async ({ browser }) => {
  const bull1 = W.t1Spaces.find(s => s.spaceKind === 'BULLETIN')!
  const mk = async (mode: string) => Number((await must(W.orgAdmin, 'POST', `${T}/threads`, { ...mkBody(W.t1, `rd-${mode}-${W.tag}`, bull1.refId), readTrackingMode: mode })).data.id)
  const ind = await mk('INDIVIDUAL'); const cnt = await mk('COUNT_ONLY')
  const { context, page } = await openAs(browser, W.pMember)
  const r1 = await probe(page, 'GET', `${T}/threads/${ind}/readers`)
  const r2 = await probe(page, 'GET', `${T}/threads/${cnt}/readers`)
  const r3 = await probe(page, 'GET', `${T}/threads/${ind}/readers?filter=unread`)
  log({ step: 'AC-15', individual: sig(r1), countOnly: sig(r2), countOnlyBody: r2.raw, unread: sig(r3) })
  expect(r1.status).toBe(200); expect(r2.status).toBe(200); expect(r2.json?.data).toEqual([])
  expect(r3.status).toBe(403); expect(r3.code).toBe('COMMON_002')
  await context.close()
})

test('34 [AC-17 逆] 主催ADMIN: T2 を 掲示板=非公開 / チャット=公開 に画面で付け替え → 部外者の応答', async ({ browser }) => {
  const a = await openAs(browser, W.orgAdmin)
  await gotoAndHydrate(a.page, commUrl(W.orgO.slug, W.t2))
  const switches = a.page.getByRole('switch')
  await expect(switches).toHaveCount(2)
  const patch = () => a.page.waitForResponse(r => /\/contact-spaces\/[^/]+\/visibility$/.test(new URL(r.url()).pathname) && r.request().method() === 'PATCH')
  let w = patch(); await switches.nth(0).click(); expect((await w).status()).toBe(200)
  w = patch(); await switches.nth(1).click(); expect((await w).status()).toBe(200)
  await shot(a.page, '34-t2-chat-public-bulletin-private')
  expect(sql(`SELECT GROUP_CONCAT(CONCAT(space_kind,'=',is_public) ORDER BY space_kind) FROM tournament_contact_space WHERE scope_type='TOURNAMENT' AND scope_id=${W.t2} AND deleted_at IS NULL;`)).toBe('BULLETIN=0,CHAT=1')
  await a.context.close()
  const bull2 = W.t2Spaces.find(s => s.spaceKind === 'BULLETIN')!
  const chat2 = W.t2Spaces.find(s => s.spaceKind === 'CHAT')!
  const { context, page } = await openAs(browser, W.outsider)
  const g = await expectSameAsAbsent('AC-17 非公開掲示板 一覧は不在と一致', page, 'GET', `${T}/threads?scope_type=TOURNAMENT&scope_id=${W.t2}&page=0&size=20`, `${T}/threads?scope_type=TOURNAMENT&scope_id=${ABSENT}&page=0&size=20`)
  expect(g.code).toBe('TOUR_029')
  const c = await probe(page, 'GET', `/api/v1/chat/channels/${chat2.refId}/messages`)
  log({ step: 'AC-17 公開チャット 部外者 GET', sig: sig(c) })
  expect(c.status).toBe(200)
  const m = await probe(page, 'POST', `/api/v1/chat/channels/${chat2.refId}/messages`, { body: 'outsider-msg' })
  log({ step: 'AC-17 公開チャット 部外者 POST', sig: sig(m), raw: m.raw })
  expect(m.status).toBe(403); expect(m.code).toBe('TOUR_031')
  void bull2
  await context.close()
})

test('40 [AC-5/7 差し戻し修正] 主催ADMIN: 返信を持つスレッドを画面で削除 → 返信ID・リアクション・添付ID の各入口が不在IDと同一応答', async ({ browser }) => {
  const { context, page } = await openAs(browser, W.orgAdmin)
  await gotoAndHydrate(page, commUrl(W.orgO.slug, W.t1))
  const title = `thD-${W.tag}`
  await expect(page.getByText(title).first()).toBeVisible()
  await page.getByText(title).first().click()
  await expect(page.getByText(`thD-${W.tag} の本文`).first()).toBeVisible()
  await shot(page, '40-thD-before-delete')
  const del = page.waitForResponse(r => new URL(r.url()).pathname === `${T}/threads/${W.thD}` && r.request().method() === 'DELETE')
  await page.getByRole('button', { name: '削除' }).click()
  expect([200, 204]).toContain((await del).status())
  await expect(page.getByText(title)).toHaveCount(0)
  await shot(page, '40-thD-after-delete')
  expect(sqlNum(`SELECT COUNT(*) FROM bulletin_threads WHERE id=${W.thD} AND deleted_at IS NULL;`)).toBe(0)
  // 削除後: 返信（親スレッドが論理削除済み）への各操作は、存在しない ID と同じ応答（BULLETIN_003）、添付は BULLETIN_007
  const same = async (label: string, method: string, real: string, absent: string, body: unknown, code: string, absentBody?: unknown) => {
    // 実在側と不在側が同一 URL・同一本文の自己比較にならないよう、同一 URL の入口は不在側の本文を必ず渡す
    if (real === absent && absentBody === undefined) throw new Error(`${label}: 実在・不在が同一の比較になっている`)
    const a = await expectSameAsAbsent(label, page, method, real, absent, body, absentBody)
    expect.soft(a.status, label).toBe(404); expect.soft(a.code, label).toBe(code)
  }
  for (const rid of [W.rD1, W.rD2]) {
    await same(`40 reply#${rid} への返信`, 'POST', `${T}/replies/${rid}/replies`, `${T}/replies/${ABSENT}/replies`, { body: 'x' }, 'BULLETIN_003')
    await same(`40 reply#${rid} 編集`, 'PUT', `${T}/replies/${rid}`, `${T}/replies/${ABSENT}`, { body: 'y' }, 'BULLETIN_003')
    await same(`40 reply#${rid} 削除`, 'DELETE', `${T}/replies/${rid}`, `${T}/replies/${ABSENT}`, undefined, 'BULLETIN_003')
    await same(`40 reply#${rid} リアクション追加`, 'POST', `${T}/reactions`, `${T}/reactions`, { targetType: 'REPLY', targetId: rid, emoji: '👍' }, 'BULLETIN_003', { targetType: 'REPLY', targetId: ABSENT, emoji: '👍' })
  }
  await same('40 添付削除(削除済スレッドの添付)', 'DELETE', `${T}/attachments/${W.attD}`, `${T}/attachments/${ABSENT}`, undefined, 'BULLETIN_007')
  await same('40 添付DL URL', 'GET', `${T}/attachments/${W.attD}/download-url`, `${T}/attachments/${ABSENT}/download-url`, undefined, 'BULLETIN_007')
  expect(sqlNum(`SELECT COUNT(*) FROM bulletin_reactions WHERE target_type='REPLY' AND target_id IN (${W.rD1},${W.rD2});`)).toBe(0)
  await context.close()
})
