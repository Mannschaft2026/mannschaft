import { mkdirSync } from 'node:fs'
import path from 'node:path'
import type { Browser, Page } from '@playwright/test'
import {
  API,
  apiAs,
  createActor,
  createOrg,
  createTeam,
  expect,

  joinAsMember,
  must,
  openAs as openAsBase,
  readAssets,
  writeAssets,
  type Actor,
  type Scope,
} from './helpers/n1p1-world'
import { test } from '@playwright/test'

/**
 * CMP-261113-0110 実機E2E: GROUP_DM（Zimmer）の導出名 derivedName が画面に出ること。
 * - API を使うのはログイン・前提作成・後始末だけ。表示の確認は必ず画面を開いて行う。
 * - 専用設定 playwright-gdm.config.ts（検証用 BE 8081 / FE 3017）から実行する。
 * - 作成した利用者・組織・チーム・チャンネルは afterAll で正規 API により削除する。
 */
const EVIDENCE = process.env.GDM_EVIDENCE_DIR ?? path.resolve('test-results', 'gdm-evidence')
mkdirSync(EVIDENCE, { recursive: true })

interface World {
  a: Actor
  b: Actor
  c: Actor
  d: Actor
  e: Actor
  f: Actor
  orgA: Scope
  teamA: Scope
  orgE: Scope
  teamE: Scope
  kabineAB: number
  zimmerId: number
  zimmerFId: number
  zimmerDerived: string
}

const world = {} as World
const cleanup: Array<{ label: string; path: string; owner: Actor }> = []
/** この spec が作った利用者。作成直後に積み、afterAll で退会させる（AC6 で退会済みの F は withdrawn に入る） */
const createdActors: Actor[] = []
const withdrawn = new Set<number>()

async function newActor(name: string): Promise<Actor> {
  const actor = await createActor(name)
  createdActors.push(actor)
  return actor
}

async function shot(page: Page, name: string): Promise<void> {
  await page.screenshot({ path: path.join(EVIDENCE, `${name}.png`), fullPage: false })
}

/** dev サーバーの初回コンパイルが重い環境向けに、ハイドレーション待ちを長く取る（共有 helper の 180s では足りなかった） */
async function gotoAndHydrate(page: Page, p: string): Promise<void> {
  const base = process.env.BASE_URL ?? 'http://localhost:3027'
  await page.goto(`${base}${p}`, { waitUntil: 'commit' })
  await page.waitForFunction(
    () => {
      const el = document.querySelector('#__nuxt')
      return el !== null && '__vue_app__' in el && el.childElementCount > 0
    },
    undefined,
    { timeout: 600_000 },
  )
}

async function openAs(browser: Browser, actor: Actor, p?: string) {
  const r = await openAsBase(browser, actor)
  if (p) await gotoAndHydrate(r.page, p)
  return r
}

function activePanelHeader(page: Page) {
  return page.locator('[data-tab-active="true"] h3').first()
}

test.describe.configure({ mode: 'serial' })

test.beforeAll(async () => {
  test.setTimeout(600_000)
  world.a = await newActor('Alice')
  world.b = await newActor('Bob')
  world.c = await newActor('Carol')
  world.d = await newActor('Dave')
  world.e = await newActor('Eve')
  world.f = await newActor('Fay')
  world.orgA = await createOrg(world.a, `GDM組織A ${world.a.nickname}`)
  world.teamA = await createTeam(world.a, `GDMチームA ${world.a.nickname}`)
  for (const m of [world.b, world.c, world.d, world.f]) {
    await joinAsMember('organizations', world.a, world.orgA.slug, m)
    await joinAsMember('teams', world.a, world.teamA.slug, m)
  }
  world.orgE = await createOrg(world.e, `GDM組織E ${world.e.nickname}`)
  world.teamE = await createTeam(world.e, `GDMチームE ${world.e.nickname}`)

  // 前提: A と B の Kabine（画面で開く対象）。Zimmer 化は画面で行う
  const kabine = await must(world.a, 'POST', '/api/v1/chat/channels/conversations', { userIds: [world.b.id] })
  world.kabineAB = kabine.data.id
  cleanup.push({ label: `kabineAB ${world.kabineAB}`, path: `/api/v1/chat/channels/${world.kabineAB}`, owner: world.a })
})

test.afterAll(async () => {
  // 204 / 404（既に無い）以外は失敗として集め、全件試した後にまとめて throw する（後始末の失敗を隠さない）
  const failures: string[] = []
  const attempt = async (label: string, owner: Actor, method: string, p: string, body?: unknown): Promise<void> => {
    const res = await apiAs(owner, method, p, body)
    console.log(`[gdm] cleanup ${label} -> ${res.status}`)
    const ok = method === 'DELETE' && p === '/api/v1/users/me' ? res.status === 200 || res.status === 204 || res.status === 404 : res.status === 204 || res.status === 404
    if (!ok) failures.push(`${label}:${res.status}`)
  }
  for (const r of [...cleanup].reverse()) await attempt(r.label, r.owner, 'DELETE', r.path)
  // 管理者 A が他メンバーのいるスコープの唯一の ADMIN のままだと、A の退会が GDPR_011(409) で拒否される。
  // 退会の前提として、A がメンバーを除名してスコープを空にする（正規の除名 API。スコープ自体の削除は論理削除でメンバーが残る）
  if (world.a && world.orgA && world.teamA) {
    for (const m of [world.b, world.c, world.d, world.f]) {
      if (!m) continue
      await attempt(`removeMember org ${m.label}`, world.a, 'DELETE', `/api/v1/organizations/${world.orgA.slug}/members/${m.id}`)
      await attempt(`removeMember team ${m.label}`, world.a, 'DELETE', `/api/v1/teams/${world.teamA.slug}/members/${m.id}`)
    }
  }
  // 組織・チームは createOrg/createTeam が n1p1 の資産記録へ積む。ここで同じ DELETE を直接呼ぶ
  for (const [label, p, owner] of [
    ['teamE', `/api/v1/teams/${world.teamE?.slug}`, world.e],
    ['orgE', `/api/v1/organizations/${world.orgE?.slug}`, world.e],
    ['teamA', `/api/v1/teams/${world.teamA?.slug}`, world.a],
    ['orgA', `/api/v1/organizations/${world.orgA?.slug}`, world.a],
  ] as const) {
    if (!owner || p.endsWith('undefined')) continue
    const before = failures.length
    await attempt(label, owner, 'DELETE', p)
    // 削除に成功したら、createOrg/createTeam が共有台帳 n1p1-assets.json へ積んだ自分の記録だけを取り除く。
    // 失敗分は台帳に残す（n1p1-global-teardown が再処理できる）。他 spec の記録は path と所有者が一致するものしか触らない
    if (failures.length === before) {
      writeAssets(readAssets().filter((rec) => !(rec.path === p && rec.owner.email === owner.email)))
    }
  }
  // 利用者は最後に退会させる（AC6 で退会済みの F は除く。退会 API は AC6 と同じ DELETE /users/me）
  for (const actor of [...createdActors].reverse()) {
    if (withdrawn.has(actor.id)) continue
    // 台帳に所有者としての記録が残っている利用者を退会させると、後の n1p1-global-teardown のログインで退会が自動取り消しされる
    if (readAssets().some((rec) => rec.owner.email === actor.email)) {
      failures.push(`withdraw ${actor.label}:台帳に所有記録が残っているため退会しない`)
      continue
    }
    await attempt(`withdraw ${actor.label}`, actor, 'DELETE', '/api/v1/users/me', { currentPassword: actor.password })
  }
  if (failures.length) throw new Error(`後始末に失敗: ${failures.join(' ')}`)
})

test('AC1 A が画面で Kabine を開き Zimmer へ招待すると一覧・タブ・ヘッダーに「B, C」', async ({ browser }) => {
  test.setTimeout(900_000)
  // 招待候補は画面の文脈で決まる（チーム画面=チームメンバー、汎用 /chat=自分の連絡先）。
  // 利用者を連絡先にしていないので、チームのチャット画面から招待する。
  const { page, context } = await openAs(browser, world.a, `/teams/${world.teamA.slug}/chat`)
  await page.locator(`[data-testid="chat-channel-${world.kabineAB}"]`).click()
  await expect(page.getByTestId('team-chat-input')).toBeVisible({ timeout: 60_000 })
  await shot(page, 'ac1-1-kabine-opened')

  await page.locator('button:has(.pi-user-plus)').click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByRole('textbox').first().fill(world.c.nickname)
  await dialog.locator('button', { hasText: world.c.nickname }).first().click()
  await shot(page, 'ac1-2-invite-dialog')

  const [created] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === 'POST' && /\/channels\/\d+\/invite-to-zimmer$/.test(r.url())),
    dialog.getByRole('button', { name: 'Zimmerを作成' }).click(),
  ])
  console.log(`[gdm] invite-to-zimmer status=${created.status()}`)
  expect(created.status()).toBe(201)
  const body = await created.json()
  world.zimmerId = body.data.id
  world.zimmerDerived = body.data.derivedName
  console.log(`[gdm] invite-to-zimmer response id=${body.data.id} type=${body.data.identity?.channelType} derivedName=${JSON.stringify(body.data.derivedName)} meta.name=${JSON.stringify(body.data.meta?.name)}`)
  cleanup.push({ label: `zimmer ${world.zimmerId}`, path: `/api/v1/chat/channels/${world.zimmerId}`, owner: world.a })

  const expected = `${world.b.nickname}, ${world.c.nickname}`
  // 一覧（サイドバー）
  const listItem = page.locator(`[data-testid="chat-channel-${world.zimmerId}"]`)
  await expect(listItem).toContainText(expected, { timeout: 30_000 })
  console.log(`[gdm] AC1 list text=${JSON.stringify((await listItem.innerText()).trim())}`)
  // ヘッダー: 作成後に開いたパネル（チーム画面にはタブバーが無い）
  const header = page.locator('h3', { hasText: expected }).first()
  await expect(header).toBeVisible({ timeout: 30_000 })
  console.log(`[gdm] AC1 team-chat header text=${JSON.stringify((await header.innerText()).trim())}`)
  await shot(page, 'ac1-3-zimmer-list-header-teamchat')
  await context.close()
})

test('AC1b A の汎用チャット画面（/chat）でも 一覧・タブ・ヘッダー に「B, C」', async ({ browser }) => {
  test.setTimeout(900_000)
  const { page, context } = await openAs(browser, world.a, '/chat')
  const expected = `${world.b.nickname}, ${world.c.nickname}`
  const listItem = page.locator(`[data-testid="chat-channel-${world.zimmerId}"]`)
  await expect(listItem).toContainText(expected, { timeout: 60_000 })
  console.log(`[gdm] AC1b list=${JSON.stringify((await listItem.innerText()).trim())}`)
  await listItem.click()
  await expect(activePanelHeader(page)).toHaveText(expected, { timeout: 30_000 })
  console.log(`[gdm] AC1b header=${JSON.stringify((await activePanelHeader(page).innerText()).trim())}`)
  // 一覧・タブ・ヘッダーの3か所に同じ名前が出る（完全一致の要素数）
  const occurrences = await page.getByText(expected, { exact: true }).count()
  console.log(`[gdm] AC1b 導出名と完全一致する要素数(一覧+タブ+ヘッダー=3 期待)=${occurrences}`)
  expect(occurrences).toBeGreaterThanOrEqual(3)
  await shot(page, 'ac1b-chat-list-tab-header')
  await context.close()
})

for (const [label, who, others] of [
  ['AC2-B', 'b', ['a', 'c']],
  ['AC2-C', 'c', ['a', 'b']],
] as const) {
  test(`${label} ${who} には自分以外の参加者名が出る`, async ({ browser }) => {
    test.setTimeout(900_000)
    const me = world[who]
    const { page, context } = await openAs(browser, me, `/chat?channel=${world.zimmerId}`)
    const expected = others.map((k) => world[k].nickname).join(', ')
    const listItem = page.locator(`[data-testid="chat-channel-${world.zimmerId}"]`)
    await expect(listItem).toContainText(expected, { timeout: 60_000 })
    console.log(`[gdm] ${label} list=${JSON.stringify((await listItem.innerText()).trim())}`)
    await expect(activePanelHeader(page)).toHaveText(expected, { timeout: 60_000 })
    console.log(`[gdm] ${label} header=${JSON.stringify((await activePanelHeader(page).innerText()).trim())}`)
    await shot(page, `ac2-${who}`)
    await context.close()
  })
}

test('AC3 名前の設定導線（UI）と、API で名前を付けたとき名前が優先される', async ({ browser }) => {
  test.setTimeout(900_000)
  const { page, context } = await openAs(browser, world.a, `/chat?channel=${world.zimmerId}`)
  await expect(activePanelHeader(page)).toBeVisible({ timeout: 60_000 })
  // 設定導線の有無: ヘッダー右クリック・設定系ボタンを観測（画面に名前編集 UI があるか）
  const headerButtons = await page.locator('[data-tab-active="true"] button').evaluateAll((els) =>
    els.map((e) => `${e.getAttribute('aria-label') ?? ''}|${e.className.match(/pi-[a-z-]+/)?.[0] ?? ''}|${(e.textContent ?? '').trim().slice(0, 12)}`),
  )
  console.log(`[gdm] AC3 active-panel buttons=${JSON.stringify(headerButtons)}`)
  await shot(page, 'ac3-1-ui-no-rename-entry')
  // 設定導線が無いので API（PATCH）で名前を付け、画面の優先順位だけ確認する
  const named = `名前付きZimmer ${world.a.nickname}`
  const patch = await apiAs(world.a, 'PATCH', `/api/v1/chat/channels/${world.zimmerId}`, { name: named })
  console.log(`[gdm] AC3 PATCH name status=${patch.status} body.meta.name=${JSON.stringify(patch.body?.data?.meta?.name)} derived=${JSON.stringify(patch.body?.data?.derivedName)}`)
  expect(patch.status).toBe(200)
  await page.reload()
  const listItem = page.locator(`[data-testid="chat-channel-${world.zimmerId}"]`)
  await expect(listItem).toContainText(named, { timeout: 60_000 })
  await expect(activePanelHeader(page)).toHaveText(named, { timeout: 60_000 })
  await shot(page, 'ac3-2-named')
  // 元に戻す（名前を空にできるか観測）
  const clear = await apiAs(world.a, 'PATCH', `/api/v1/chat/channels/${world.zimmerId}`, { name: '' })
  console.log(`[gdm] AC3 PATCH clear status=${clear.status} meta.name=${JSON.stringify(clear.body?.data?.meta?.name)}`)
  await context.close()
})

test('AC4 convert-to-group の画面導線の有無', async ({ browser }) => {
  test.setTimeout(900_000)
  const { page, context } = await openAs(browser, world.a, `/chat?channel=${world.kabineAB}`)
  await expect(activePanelHeader(page)).toBeVisible({ timeout: 60_000 })
  const reqs: string[] = []
  page.on('request', (r) => {
    if (/convert-to-group/.test(r.url())) reqs.push(r.url())
  })
  const buttons = await page.locator('[data-tab-active="true"] button').evaluateAll((els) =>
    els.map((e) => `${e.getAttribute('aria-label') ?? ''}|${e.className.match(/pi-[a-z-]+/)?.[0] ?? ''}`),
  )
  console.log(`[gdm] AC4 kabine panel buttons=${JSON.stringify(buttons)}`)
  const hasText = await page.getByText(/グループに変換|グループDMに変換|変換/).count()
  console.log(`[gdm] AC4 '変換' 文言の出現数=${hasText} convert-to-group request=${reqs.length}`)
  await shot(page, 'ac4-kabine-panel')
  await context.close()
})

test('AC5 チームのチャット画面でも同じ表示', async ({ browser }) => {
  test.setTimeout(900_000)
  const { page, context } = await openAs(browser, world.b, `/teams/${world.teamA.slug}/chat`)
  const expected = `${world.a.nickname}, ${world.c.nickname}`
  const listItem = page.locator(`[data-testid="chat-channel-${world.zimmerId}"]`)
  const visible = await listItem.waitFor({ state: 'visible', timeout: 60_000 }).then(() => true, () => false)
  console.log(`[gdm] AC5 team chat list has zimmer item=${visible}`)
  await shot(page, 'ac5-1-team-chat-list')
  if (visible) {
    console.log(`[gdm] AC5 list=${JSON.stringify((await listItem.innerText()).trim())}`)
    await expect(listItem).toContainText(expected)
    await listItem.click()
    await expect(page.locator('h1, h2, h3').filter({ hasText: expected }).first()).toBeVisible({ timeout: 30_000 })
    await shot(page, 'ac5-2-team-chat-selected')
  }
  expect(visible).toBe(true)
  await context.close()
})

test('AC6 退会者が参加する Zimmer では「ユーザー」と出る', async ({ browser }) => {
  test.setTimeout(900_000)
  // A,B の Kabine に F を招待して Zimmer を作る（前提）。その後 F を正規の退会 API で退会させる
  const z = await must(world.a, 'POST', `/api/v1/chat/channels/${world.kabineAB}/invite-to-zimmer`, { userIds: [world.f.id], shareHistory: false })
  world.zimmerFId = z.data.id
  cleanup.push({ label: `zimmerF ${world.zimmerFId}`, path: `/api/v1/chat/channels/${world.zimmerFId}`, owner: world.a })
  console.log(`[gdm] AC6 前提 zimmerF id=${world.zimmerFId} derivedName(退会前)=${JSON.stringify(z.data.derivedName)}`)
  const wd = await apiAs(world.f, 'DELETE', '/api/v1/users/me', { currentPassword: world.f.password })
  console.log(`[gdm] AC6 F の退会 API status=${wd.status} body=${JSON.stringify(wd.body).slice(0, 200)}`)
  expect(wd.status).toBe(200)
  withdrawn.add(world.f.id)

  const { page, context } = await openAs(browser, world.a, `/chat?channel=${world.zimmerFId}`)
  const listItem = page.locator(`[data-testid="chat-channel-${world.zimmerFId}"]`)
  await expect(listItem).toBeVisible({ timeout: 60_000 })
  console.log(`[gdm] AC6 list=${JSON.stringify((await listItem.innerText()).trim())}`)
  await expect(activePanelHeader(page)).toBeVisible()
  const header = (await activePanelHeader(page).innerText()).trim()
  console.log(`[gdm] AC6 header=${JSON.stringify(header)} (期待: "${world.b.nickname}, ユーザー")`)
  await shot(page, 'ac6-withdrawn-user')
  expect(header).toBe(`${world.b.nickname}, ユーザー`)
  await context.close()
})

for (const [label, who] of [
  ['D(同組織の非参加者)', 'd'],
  ['E(他組織)', 'e'],
] as const) {
  test(`AC7 ${label} には Zimmer が一覧に出ず、直打ちも拒否され、参加者名が出ない`, async ({ browser }) => {
    test.setTimeout(900_000)
    const me = world[who]
    const names = [world.a, world.b, world.c, world.f].map((x) => x.nickname)
    // API 応答にも出ないこと（ログイン済み利用者として）
    const direct = await apiAs(me, 'GET', `/api/v1/chat/channels/${world.zimmerId}`)
    console.log(`[gdm] AC7 ${who} GET channel status=${direct.status} body=${JSON.stringify(direct.body).slice(0, 200)}`)
    expect([403, 404]).toContain(direct.status)
    for (const n of names) expect(JSON.stringify(direct.body)).not.toContain(n)

    const { page, context } = await openAs(browser, me)
    const apiHits: Array<{ url: string; status: number; text: string }> = []
    page.on('response', async (r) => {
      if (/\/api\/v1\/chat\//.test(r.url())) {
        apiHits.push({ url: r.url().replace(API, ''), status: r.status(), text: await r.text().catch(() => '') })
      }
    })
    await gotoAndHydrate(page, '/chat')
    await page.waitForTimeout(8_000)
    await expect(page.locator(`[data-testid="chat-channel-${world.zimmerId}"]`)).toHaveCount(0)
    await shot(page, `ac7-${who}-list`)
    await gotoAndHydrate(page, `/chat?channel=${world.zimmerId}`)
    await page.waitForTimeout(8_000)
    await shot(page, `ac7-${who}-direct-url`)
    const body = await page.locator('body').innerText()
    for (const n of names) expect(body).not.toContain(n)
    for (const h of apiHits) for (const n of names) expect(h.text, `${h.url} に ${n} が含まれる`).not.toContain(n)
    console.log(`[gdm] AC7 ${who} chat API hits=${JSON.stringify(apiHits.map((h) => `${h.status} ${h.url}`))}`)
    console.log(`[gdm] AC7 ${who} direct-url 画面本文(先頭200)=${JSON.stringify(body.replace(/\s+/g, ' ').slice(0, 200))}`)
    await context.close()
  })
}
