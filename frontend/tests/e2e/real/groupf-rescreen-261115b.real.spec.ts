import { appendFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { expect, test, type Browser, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import {
  API,
  affiliate,
  apiAs,
  createActor,
  createOrg,
  createTeam,
  joinScope,
  must,
  FE,
  type Actor,
  type Scope,
} from './helpers/f0121-org-groups-world'

/**
 * P1 群F 画面層の再実施（2026-11-15、D-F1＝CMP-261115-1635 修正 #4531 後）。
 *
 * - CMP-261002-1604: 組織の確認通知設定画面（直打ち・アプリ内遷移・履歴/送信/グループ/テンプレート/設定・キャンセル）と越境 ID
 * - CMP-261115-1635: 同画面の実機E2E ＋ 他の組織設定画面（同型の欠陥・退行がないか）
 * - CMP-261101-2008 E-1: /dashboard のチームパネルで組織告知が会員に見えるか
 * - CMP-261006-0901 O-1: 大会の連絡スペース・書類提出・参加費ほかでトーストが出たときの API の URL・status・error.code
 *
 * 作成するデータはすべて名前/メールに e2e-261115b / e2e261115b を付け、globalTeardown が組織・チームを削除する。
 * 観測は test-results/groupf-rescreen-obs.jsonl に追記する（md 記録用）。画像は docs/evidence/screenshots/groupF-261115b/。
 */

const SHOT_DIR = path.resolve('..', 'docs', 'evidence', 'screenshots', 'groupF-261115b')
const OBS = path.resolve('test-results', 'groupf-rescreen-obs.jsonl')
mkdirSync(SHOT_DIR, { recursive: true })
mkdirSync(path.dirname(OBS), { recursive: true })

function obs(key: string, value: unknown) {
  appendFileSync(OBS, `${JSON.stringify({ key, value })}\n`)
  console.log(`[obs] ${key}: ${JSON.stringify(value)}`)
}

async function shot(page: Page, name: string, fullPage = false) {
  await page.screenshot({ path: path.join(SHOT_DIR, `${name}.png`), fullPage })
}

async function bodyText(page: Page): Promise<string> {
  return (await page.locator('body').innerText()).replace(/\s+/g, ' ').trim()
}

async function noHorizontalOverflow(page: Page): Promise<boolean> {
  return page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)
}

async function waitHydrated(page: Page) {
  await page.waitForFunction(() => {
    const el = document.querySelector('#__nuxt')
    return el !== null && '__vue_app__' in el && el.childElementCount > 0
  }, undefined, { timeout: 180_000 })
}

test.describe.configure({ mode: 'serial' })

const problems: string[] = []
function rtest(name: string, fn: (args: { browser: Browser }) => Promise<void>) {
  test(name, async ({ browser }) => {
    try {
      await fn({ browser })
    } catch (error) {
      const message = String((error as Error).message).replace(/\[[0-9;]*m/g, '').slice(0, 500)
      problems.push(`${name}: ${message}`)
      obs(`FAIL ${name}`, message)
    }
  })
}

async function openBare(browser: Browser, actor: Actor) {
  const context = await browser.newContext({ baseURL: FE, storageState: { cookies: [], origins: [] }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo', viewport: { width: 1280, height: 900 } })
  const page = await context.newPage()
  await page.goto(`${FE}/robots.txt`)
  await loginViaApi(page, actor, { apiBaseUrl: API, deferNavigation: true })
  return { context, page }
}

interface Call { method: string; path: string; status: number; code: string | null; message: string | null; query: string }

/** ページが呼んだ /api/v1 の応答（4xx/5xx は error.code・message も）と console エラーを集める。 */
function tap(page: Page) {
  const calls: Call[] = []
  const consoleErrs: string[] = []
  const pageErrs: string[] = []
  page.on('console', (m) => { if (m.type() === 'error') consoleErrs.push(m.text().slice(0, 200)) })
  page.on('pageerror', (e) => pageErrs.push(String(e.message).slice(0, 160)))
  page.on('response', async (r) => {
    const u = new URL(r.url())
    if (!u.pathname.startsWith('/api/v1/')) return
    let code: string | null = null
    let message: string | null = null
    if (r.status() >= 400) {
      try {
        const body = await r.json()
        code = body?.error?.code ?? body?.code ?? null
        message = body?.error?.message ?? body?.message ?? null
      } catch { /* 本文なし */ }
    }
    calls.push({ method: r.request().method(), path: u.pathname, status: r.status(), code, message, query: u.search.replace(/\d{3,}/g, 'N') })
  })
  return { calls, consoleErrs, pageErrs }
}

/** 実 ID を伏せる（slug は呼称へ、数値は N へ）。 */
function mask(p: string, replacements: Array<[string, string]>): string {
  let out = p
  for (const [from, to] of replacements) out = out.split(from).join(to)
  return out.replace(/\/\d+/g, '/N')
}

async function visibleToasts(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const out: string[] = []
    document.querySelectorAll('[role="alert"], .p-toast-message, .p-message-error, [data-severity="error"]').forEach((e) => {
      const t = (e.textContent ?? '').replace(/\s+/g, ' ').trim()
      if (t) out.push(t.slice(0, 100))
    })
    return out
  })
}

const world = {} as {
  adminA: Actor
  memA: Actor
  adminB: Actor
  orgA: Scope
  orgB: Scope
  teamA: Scope
  notifA1: number
  notifA2: number
  notifB: number
  titleA1: string
  titleA2: string
  tourA: number
  tourNameA: string
  noticeTitle: string
}

const SPEC = 'e2e261115b'

test('準備: 検証用の組織・チーム・確認通知・組織告知・大会を自前で作る', async () => {
  world.adminA = await createActor('AdminA', SPEC)
  world.memA = await createActor('MemA', SPEC)
  world.adminB = await createActor('AdminB', SPEC)
  world.orgA = await createOrg(world.adminA, 'e2e-261115b 組織A')
  world.orgB = await createOrg(world.adminB, 'e2e-261115b 組織B')
  world.teamA = await createTeam(world.adminA, 'e2e-261115b チームA')
  await joinScope('organizations', world.adminA, world.orgA.slug, world.memA, 'MEMBER')
  await joinScope('teams', world.adminA, world.teamA.slug, world.memA, 'MEMBER')
  await affiliate(world.adminA, world.orgA.slug, world.adminA, world.teamA.slug)

  // 組織告知（E-1）。組織Aからチームへ一斉送信（TIMELINE_POST）。
  world.noticeTitle = 'e2e-261115b 組織告知'
  const bc = await apiAs(world.adminA, 'POST', `/api/v1/organizations/${world.orgA.numericId}/broadcast`, {
    channel: 'TIMELINE_POST',
    targetRole: 'MEMBERS_AND_ABOVE',
    targetTeamIds: [world.teamA.slug],
    priority: 'NORMAL',
    content: { title: world.noticeTitle, body: '画面層の再検証用の組織告知（群F）' },
  })
  obs('準備.組織告知broadcast', { status: bc.status, body: JSON.stringify(bc.body).slice(0, 300) })
  await new Promise((resolve) => setTimeout(resolve, 8_000))
  for (const [label, actor, kind, slug] of [['teamA会員', world.memA, 'team', world.teamA.slug], ['teamA管理者', world.adminA, 'team', world.teamA.slug], ['orgA管理者', world.adminA, 'organization', world.orgA.slug]] as const) {
    const d = await apiAs(actor, 'GET', `/api/v1/dashboard/${kind}/${slug}`)
    const raw = JSON.stringify(d.body)
    obs(`準備.告知のダッシュボードAPI.${label}`, { status: d.status, 告知欄: JSON.stringify(d.body?.data?.orgNotices ?? d.body?.data?.teamNotices).slice(0, 300), 告知タイトル含む: raw.includes(world.noticeTitle), 最上位キー: Object.keys(d.body?.data ?? {}).slice(0, 30), viewerRole: d.body?.data?.viewerRole })
  }

  world.titleA1 = 'e2e-261115b 確認通知A1'
  world.titleA2 = 'e2e-261115b 確認通知A2'
  for (const title of [world.titleA1, world.titleA2]) {
    await must(world.adminA, 'POST', `/api/v1/organizations/${world.orgA.numericId}/confirmable-notifications`, {
      title,
      body: '画面層の再検証用（群F）',
      priority: 'NORMAL',
      targets: [{ type: 'TEAM', id: world.teamA.numericId }],
    }, [202])
  }
  const teamB = await createTeam(world.adminB, 'e2e-261115b チームB')
  const memB = await createActor('MemB', SPEC)
  await joinScope('teams', world.adminB, teamB.slug, memB, 'MEMBER')
  await affiliate(world.adminB, world.orgB.slug, world.adminB, teamB.slug)
  await must(world.adminB, 'POST', `/api/v1/organizations/${world.orgB.numericId}/confirmable-notifications`, {
    title: 'e2e-261115b 確認通知B1',
    body: '画面層の再検証用（群F・他組織）',
    priority: 'NORMAL',
    targets: [{ type: 'TEAM', id: teamB.numericId }],
  }, [202])
  const idOf = async (actor: Actor, org: Scope, title: string) => {
    for (let i = 0; i < 20; i++) {
      const r = await apiAs(actor, 'GET', `/api/v1/organizations/${org.numericId}/confirmable-notifications?size=50`)
      const rows = (r.body?.data?.content ?? r.body?.data ?? []) as Array<{ id: number; title: string }>
      const hit = rows.find((n) => n.title === title)
      if (hit) return hit.id
      await new Promise((resolve) => setTimeout(resolve, 2_000))
    }
    throw new Error(`確認通知が見つからない: ${title}`)
  }
  world.notifA1 = await idOf(world.adminA, world.orgA, world.titleA1)
  world.notifA2 = await idOf(world.adminA, world.orgA, world.titleA2)
  world.notifB = await idOf(world.adminB, world.orgB, 'e2e-261115b 確認通知B1')

  // 新規大会（O-1 用。部門なし・書類なし・参加費なしの「新規」状態）
  world.tourNameA = 'e2e-261115b 大会A'
  const t = await must(world.adminA, 'POST', `/api/v1/organizations/${world.orgA.numericId}/tournaments`, { name: world.tourNameA, format: 'LEAGUE', visibility: 'PUBLIC' })
  world.tourA = Number(t.data.id)
  await must(world.adminA, 'PATCH', `/api/v1/organizations/${world.orgA.numericId}/tournaments/${world.tourA}/status`, { status: 'OPEN' })
  obs('準備', { orgA: world.orgA.slug, orgB: world.orgB.slug, notifA1: world.notifA1, notifA2: world.notifA2, notifB: world.notifB, tourA: world.tourA })
})

// ───────────────────────── 1604 / 1635 確認通知設定画面 ─────────────────────────

const SECTIONS = ['確認通知を送信', '発信履歴', '宛先グループ', 'テンプレート管理', '確認通知設定'] as const

function confirmableDiagnosis(label: string, calls: Call[], consoleErrs: string[], org: Scope) {
  const conf = calls.filter((c) => c.path.includes('/confirmable'))
  const emptyScope = calls.filter((c) => /\/organizations\/\/|\/organizations\/undefined|\/organizations\/null/.test(c.path))
  const wrongOrg = conf.filter((c) => c.path.startsWith('/api/v1/organizations/') && !c.path.startsWith(`/api/v1/organizations/${org.numericId}/`))
  const bad = conf.filter((c) => c.status >= 400)
  obs(`${label}.診断`, {
    confirmable系API: Array.from(new Set(conf.map((c) => `${c.method} ${c.status} ${mask(c.path, [[String(org.numericId), 'ORG_A']])}`))),
    組織ID空の要求: emptyScope.length,
    他組織IDの要求: wrongOrg.length,
    '4xx5xx': bad.map((c) => `${c.status} ${c.code} ${mask(c.path, [[String(org.numericId), 'ORG_A']])}`),
    consoleエラー: consoleErrs.filter((e) => !/favicon|hydration/i.test(e)).slice(0, 4),
  })
  expect(emptyScope, `${label}: 組織 ID 空の要求`).toEqual([])
  expect(wrongOrg, `${label}: 他組織 ID の要求`).toEqual([])
  expect(conf.length, `${label}: 確認通知系 API が呼ばれた`).toBeGreaterThan(0)
  expect(bad, `${label}: 4xx/5xx`).toEqual([])
}

rtest('1604/1635 実機: 管理者が URL 直打ちで組織の確認通知設定を開き、5 つの区画が機能する', async ({ browser }) => {
  test.setTimeout(400_000)
  const { context, page } = await openBare(browser, world.adminA)
  try {
    const t = tap(page)
    await page.goto(`/organizations/${world.orgA.slug}/settings/confirmable-notifications`, { waitUntil: 'commit' })
    await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toBeVisible({ timeout: 120_000 })
    await expect(page.getByText(world.titleA1)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(world.titleA2)).toBeVisible()
    await expect(page.getByText('e2e-261115b 確認通知B1')).toHaveCount(0)
    for (const s of SECTIONS) await expect(page.getByText(s, { exact: true }).first(), `区画 ${s}`).toBeVisible()
    await page.waitForLoadState('networkidle').catch(() => undefined)
    const h1 = await page.getByRole('heading', { level: 1 }).count()
    obs('1604.直打ち.画面', { h1数: h1, 横スクロールなし: await noHorizontalOverflow(page), toast: await visibleToasts(page) })
    await shot(page, '1604-admin-direct-url', true)
    confirmableDiagnosis('1604.直打ち', t.calls, t.consoleErrs, world.orgA)
  } finally {
    await context.close()
  }
})

rtest('1604/1635 実機: 管理者がアプリ内遷移（ダッシュボード→サイドメニュー）で開き、送信・グループ・テンプレート・キャンセルを画面で操作できる', async ({ browser }) => {
  test.setTimeout(600_000)
  const { context, page } = await openBare(browser, world.adminA)
  try {
    const t = tap(page)
    await page.goto(`/organizations/${world.orgA.slug}`, { waitUntil: 'commit' })
    await waitHydrated(page)
    const link = page.locator('a[href$="/settings/confirmable-notifications"]').first()
    await page.waitForTimeout(3_000)
    let viaDrawer = false
    if (!(await link.isVisible())) {
      await page.getByTestId('scope-sidebar-toggle').click({ timeout: 30_000 })
      viaDrawer = true
    }
    await expect(link).toBeVisible({ timeout: 30_000 })
    obs('1604.アプリ内遷移.導線', { 経路: viaDrawer ? 'ドロワーを開いてリンク' : '最初から表示' })
    t.calls.length = 0
    await link.click()
    await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(world.titleA1)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(world.titleA2)).toBeVisible()
    await page.waitForLoadState('networkidle').catch(() => undefined)
    obs('1604.アプリ内遷移.URL', page.url().replace(/^https?:\/\/[^/]+/, '').replace(world.orgA.slug, 'ORG_A'))
    confirmableDiagnosis('1604.アプリ内遷移', t.calls, t.consoleErrs, world.orgA)
    await shot(page, '1604-admin-spa-nav', true)

    // 送信（画面）。既定の宛先「この組織」。見込み受信者の確認後に送る。
    const title = 'e2e-261115b 画面からの送信'
    const sendSection = page.locator('section').filter({ has: page.getByRole('heading', { name: '確認通知を送信', level: 2 }) })
    await sendSection.locator('input[type="text"]').first().fill(title)
    await sendSection.locator('textarea').first().fill('画面から送信する検証用の本文')
    await expect(sendSection.getByText(/見込み受信者: [1-9]/)).toBeVisible({ timeout: 60_000 })
    const sendRes = page.waitForResponse((r) => r.request().method() === 'POST' && /\/organizations\/\d+\/confirmable-notifications$/.test(new URL(r.url()).pathname), { timeout: 60_000 })
    await sendSection.getByRole('button', { name: '確認通知を送信' }).click()
    const sr = await sendRes
    obs('1604.画面送信', { status: sr.status(), 受付メッセージ: await page.getByText(/配信を受け付けました/).first().isVisible().catch(() => false) })
    expect([200, 201, 202]).toContain(sr.status())
    await expect(page.getByText(title).first()).toBeVisible({ timeout: 90_000 })
    obs('1604.画面送信.履歴反映', true)

    // グループ（画面で削除できること。作成は宛先ピッカーが複雑なため API で用意）
    const g = await apiAs(world.adminA, 'POST', `/api/v1/organizations/${world.orgA.numericId}/confirmable-recipient-groups`, { name: 'e2e-261115b グループ', targets: [{ type: 'TEAM', id: world.teamA.numericId }] })
    obs('1604.グループ準備', { status: g.status })
    await page.reload({ waitUntil: 'commit' })
    await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toBeVisible({ timeout: 120_000 })
    await expect(page.getByText('e2e-261115b グループ').first()).toBeVisible({ timeout: 60_000 })
    await shot(page, '1604-admin-groups', true)

    // テンプレート（画面で作成）
    const tplSection = page.locator('section').filter({ has: page.getByText('テンプレート管理', { exact: true }) }).last()
    await tplSection.getByPlaceholder('テンプレート名').fill('e2e-261115b テンプレート')
    await tplSection.getByPlaceholder('タイトル').fill('e2e-261115b テンプレート件名')
    const tplRes = page.waitForResponse((r) => r.request().method() === 'POST' && /confirmable-notification-templates$/.test(new URL(r.url()).pathname), { timeout: 30_000 })
    await tplSection.getByRole('button', { name: '作成' }).click()
    const tr = await tplRes
    obs('1604.テンプレート画面作成', { status: tr.status() })
    expect([200, 201]).toContain(tr.status())
    await expect(page.getByText('e2e-261115b テンプレート件名').first()).toBeVisible({ timeout: 30_000 })

    // 設定（初期表示の GET が 200 で、項目が出ていること）
    obs('1604.設定区画', { 初回リマインド表示: await page.getByText('1回目リマインド').first().isVisible() })

    // キャンセル（画面）
    const row = page.getByText(world.titleA2).first().locator('xpath=ancestor::div[.//button[normalize-space()="キャンセル"]][1]')
    await shot(page, '1604-admin-before-cancel', true)
    const cancelRes = page.waitForResponse((r) => r.request().method() === 'PATCH' && /\/confirmable-notifications\/\d+\/cancel$/.test(new URL(r.url()).pathname), { timeout: 30_000 })
    await row.getByRole('button', { name: 'キャンセル' }).first().click()
    const confirmBtn = page.getByRole('button', { name: /^(はい|OK|実行|キャンセルする|取り消す)$/ })
    if (await confirmBtn.first().isVisible({ timeout: 2_000 }).catch(() => false)) await confirmBtn.first().click()
    const cr = await cancelRes
    obs('1604.画面キャンセル', { status: cr.status() })
    expect([200, 204]).toContain(cr.status())
    await page.waitForTimeout(2_000)
    obs('1604.キャンセル後', { toast: await visibleToasts(page), 他の4xx5xx: t.calls.filter((c) => c.status >= 400).map((c) => `${c.status} ${c.code} ${mask(c.path, [[String(world.orgA.numericId), 'ORG_A']])}`) })
    await shot(page, '1604-admin-after-cancel', true)
    expect(t.calls.filter((c) => c.status >= 400 && c.path.includes('/confirmable'))).toEqual([])
  } finally {
    await context.close()
  }
})

rtest('1604 実機: 一般会員・他組織の管理者は設定 URL を直打ちしても画面が出ない', async ({ browser }) => {
  const path1 = `/organizations/${world.orgA.slug}/settings/confirmable-notifications`
  for (const [label, actor] of [['member', world.memA], ['otherOrgAdmin', world.adminB]] as const) {
    const { context, page } = await openBare(browser, actor)
    try {
      const t = tap(page)
      await page.goto(path1, { waitUntil: 'commit' })
      await waitHydrated(page)
      await page.waitForTimeout(6_000)
      const text = await bodyText(page)
      obs(`1604.${label}.直打ち`, { 最終URL: page.url().replace(/^https?:\/\/[^/]+/, '').replace(world.orgA.slug, 'ORG_A').replace(world.orgB.slug, 'ORG_B'), 設定見出し: text.includes('確認通知設定'), A1表示: text.includes(world.titleA1), confirmable系の応答: t.calls.filter((c) => c.path.includes('/confirmable')).map((c) => `${c.status} ${c.code}`) })
      await shot(page, `1604-${label}-direct`)
      expect(text.includes(world.titleA1)).toBe(false)
      await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toHaveCount(0)
    } finally {
      await context.close()
    }
  }
})

rtest('1604 実機: 他組織の確認通知 ID は不在 ID と同じ応答（ブラウザのセッションから）', async ({ browser }) => {
  const { context, page } = await openBare(browser, world.adminA)
  try {
    await page.goto(`/organizations/${world.orgA.slug}/settings/confirmable-notifications`, { waitUntil: 'commit' })
    await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toBeVisible({ timeout: 120_000 })
    const probe = async (id: number) => page.evaluate(async ({ api, orgId, nid }) => {
      const out: Record<string, { status: number; code: string | null; message: string | null }> = {}
      for (const suffix of ['', '/recipients']) {
        const r = await fetch(`${api}/api/v1/organizations/${orgId}/confirmable-notifications/${nid}${suffix}`, { credentials: 'include' })
        let body: { error?: { code?: string; message?: string } } = {}
        try { body = await r.json() } catch { /* 本文なし */ }
        out[suffix || '/'] = { status: r.status, code: body.error?.code ?? null, message: body.error?.message ?? null }
      }
      return out
    }, { api: API, orgId: world.orgA.numericId, nid: id })
    const cross = await probe(world.notifB)
    const absent = await probe(987654321)
    obs('1604.他組織ID応答', cross)
    obs('1604.不在ID応答', absent)
    expect(cross['/']).toEqual(absent['/'])
    expect(cross['/recipients']).toEqual(absent['/recipients'])
  } finally {
    await context.close()
  }
})


rtest('D-F2 切り分け: 確認通知設定を開くと出るエラートーストと、宛先ピッカーの候補（子組織 API）', async ({ browser }) => {
  test.setTimeout(400_000)
  const { context, page } = await openBare(browser, world.adminA)
  try {
    const t = tap(page)
    await page.goto(`/organizations/${world.orgA.slug}/settings/confirmable-notifications`, { waitUntil: 'commit' })
    await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toBeVisible({ timeout: 120_000 })
    await expect(page.getByText(world.titleA1)).toBeVisible({ timeout: 60_000 })
    await page.waitForLoadState('networkidle').catch(() => undefined)
    await page.waitForTimeout(3_000)
    obs('DF2.開いた直後', {
      toast: await visibleToasts(page),
      '4xx5xx': t.calls.filter((c) => c.status >= 400).map((c) => `${c.method} ${c.status} ${c.code} ${mask(c.path, [[String(world.orgA.numericId), 'ORG_A'], [world.orgA.slug, 'ORG_SLUG']])}${c.query} msg=${c.message}`),
      グループ表の対象表記: await page.getByText(/チーム #\d+/).allInnerTexts(),
    })
    await shot(page, 'DF2-open')
    // 画面を通さない比較: 数値 ID と slug で同じ API を叩く
    const probe = await page.evaluate(async ({ api, num, slug }) => {
      const out: Record<string, number> = {}
      for (const [k, v] of [['numeric', num], ['slug', slug]] as const) {
        const r = await fetch(`${api}/api/v1/organizations/${v}/children?size=50`, { credentials: 'include' })
        out[k] = r.status
      }
      return out
    }, { api: API, num: world.orgA.numericId, slug: world.orgA.slug })
    obs('DF2.children API 数値IDとslug', probe)
    // 宛先ピッカー（送信区画の宛先=「宛先指定」）
    await page.getByTestId('sender-audience-mode-select').click()
    obs('DF2.宛先モード候補', await page.getByRole('option').allInnerTexts())
    const targetsOpt = page.getByRole('option', { name: /指定|選択/ }).first()
    if (await targetsOpt.isVisible().catch(() => false)) {
      await targetsOpt.click()
      await page.waitForTimeout(3_000)
      const sendSection = page.locator('section').filter({ has: page.getByRole('heading', { name: '確認通知を送信', level: 2 }) })
      obs('DF2.宛先ピッカー候補', { 本文: (await sendSection.innerText()).replace(/\s+/g, ' ').slice(0, 500), チームA表示: await sendSection.getByText('e2e-261115b チームA').count() })
      await shot(page, 'DF2-picker', true)
    }
  } finally {
    await context.close()
  }
})

// ───────────────────────── 1635 アリシゼーション: 他の組織設定画面 ─────────────────────────

const OTHER_SETTINGS = ['billing', 'faq-settings', 'notification-credits', 'public-settings', 'team-affiliation', 'todo-status-labels'] as const

rtest('1635 アリシゼーション: 他の組織設定画面を直打ちとアプリ内遷移で触る（同型の欠陥・退行・被り）', async ({ browser }) => {
  test.setTimeout(900_000)
  for (const seg of OTHER_SETTINGS) {
    // (a) 直打ち
    const a = await openBare(browser, world.adminA)
    try {
      const t = tap(a.page)
      await a.page.goto(`/organizations/${world.orgA.slug}/settings/${seg}`, { waitUntil: 'commit' })
      await waitHydrated(a.page)
      await a.page.waitForLoadState('networkidle', { timeout: 60_000 }).catch(() => undefined)
      await a.page.waitForTimeout(4_000)
      const bad = t.calls.filter((c) => c.status >= 400).map((c) => `${c.method} ${c.status} ${c.code} ${mask(c.path, [[String(world.orgA.numericId), 'ORG_A'], [world.orgA.slug, 'ORG_SLUG']])}`)
      const empty = t.calls.filter((c) => /\/organizations\/\/|\/organizations\/undefined|\/organizations\/null|\/organizations\/NaN/.test(c.path)).length
      const h1 = await a.page.getByRole('heading', { level: 1 }).allInnerTexts()
      const headerCount = await a.page.locator('header').count()
      obs(`1635.${seg}.直打ち`, {
        URL: a.page.url().replace(/^https?:\/\/[^/]+/, '').replace(world.orgA.slug, 'ORG_A'),
        h1: h1.map((s) => s.slice(0, 30)),
        組織ID空の要求: empty,
        '4xx5xx': Array.from(new Set(bad)),
        toast: await visibleToasts(a.page),
        横スクロールなし: await noHorizontalOverflow(a.page),
        header要素数: headerCount,
        consoleエラー: t.consoleErrs.filter((e) => !/favicon/i.test(e)).slice(0, 3),
        pageerror: t.pageErrs.slice(0, 2),
        本文先頭: (await bodyText(a.page)).slice(0, 90),
      })
      await shot(a.page, `1635-${seg}-direct`)
    } finally {
      await a.context.close()
    }
    // (b) アプリ内遷移（ダッシュボード → ドロワーのリンク）
    const b = await openBare(browser, world.adminA)
    try {
      const t = tap(b.page)
      await b.page.goto(`/organizations/${world.orgA.slug}`, { waitUntil: 'commit' })
      await waitHydrated(b.page)
      await b.page.waitForTimeout(3_000)
      const link = b.page.locator(`a[href$="/settings/${seg}"]`).first()
      let reach = '導線なし'
      if (await link.isVisible()) reach = '最初から表示'
      else {
        await b.page.getByTestId('scope-sidebar-toggle').click({ timeout: 20_000 }).catch(() => undefined)
        if (await link.isVisible({ timeout: 5_000 }).catch(() => false)) reach = 'ドロワーを開いて表示'
      }
      if (reach !== '導線なし') {
        t.calls.length = 0
        await link.click()
        await b.page.waitForURL(new RegExp(`/settings/${seg}$`), { timeout: 60_000 })
        await b.page.waitForLoadState('networkidle', { timeout: 60_000 }).catch(() => undefined)
        await b.page.waitForTimeout(3_000)
        const bad = t.calls.filter((c) => c.status >= 400).map((c) => `${c.method} ${c.status} ${c.code} ${mask(c.path, [[String(world.orgA.numericId), 'ORG_A'], [world.orgA.slug, 'ORG_SLUG']])}`)
        const empty = t.calls.filter((c) => /\/organizations\/\/|\/organizations\/undefined|\/organizations\/null|\/organizations\/NaN/.test(c.path)).length
        obs(`1635.${seg}.アプリ内遷移`, { 導線: reach, h1: (await b.page.getByRole('heading', { level: 1 }).allInnerTexts()).map((s) => s.slice(0, 30)), 組織ID空の要求: empty, '4xx5xx': Array.from(new Set(bad)), toast: await visibleToasts(b.page) })
        await shot(b.page, `1635-${seg}-spa`)
      } else {
        obs(`1635.${seg}.アプリ内遷移`, { 導線: reach })
      }
    } finally {
      await b.context.close()
    }
  }
})

// ───────────────────────── 2008 E-1 ─────────────────────────

rtest('E-1 切り分け: チーム会員の /dashboard で組織告知のタイトルが見えるか', async ({ browser }) => {
  test.setTimeout(600_000)
  for (const [label, actor] of [['teamA会員', world.memA], ['teamA管理者', world.adminA]] as const) {
    const { context, page } = await openBare(browser, actor)
    try {
      const t = tap(page)
      const bodies: Record<string, string> = {}
      page.on('response', async (r) => {
        const p = new URL(r.url()).pathname
        if (/\/dashboard\/(team|organization)\/|feature-visibility|\/features|\/dashboard\/announcements|\/announcements/.test(p) && r.status() < 400) {
          bodies[`${r.request().method()} ${p.replace(world.teamA.slug, 'TEAM_A').replace(world.orgA.slug, 'ORG_A').replace(/\/\d+/g, '/N')}`] = (await r.text().catch(() => '')).slice(0, 1500)
        }
      })
      await page.goto('/dashboard', { waitUntil: 'commit' })
      await waitHydrated(page)
      await page.waitForTimeout(6_000)
      await shot(page, `E1-${label}-landing`)
      const tabsText = await page.locator('[role="tab"], [data-testid*="tag"], [data-testid*="scope"]').allInnerTexts().catch(() => [])
      obs(`E1.${label}.着地`, { URL: page.url().replace(/^https?:\/\/[^/]+/, ''), タブ候補: tabsText.map((s) => s.replace(/\s+/g, ' ').slice(0, 40)).slice(0, 12), 告知タイトル: (await bodyText(page)).includes(world.noticeTitle) })
      // チームのタグ（名前で探す）
      await page.locator('#scope-tab-TEAM').click({ timeout: 30_000 })
      await page.waitForTimeout(10_000)
      await shot(page, `E1-${label}-after-tab`)
      const teamTag = page.getByRole('tab', { name: /e2e-261115b チームA/ }).first()
      obs(`E1.${label}.チームのタグ`, { タグ表示: await teamTag.isVisible().catch(() => false) })
      await shot(page, `E1-${label}-team-panel`)
      const text = await bodyText(page)
      obs(`E1.${label}.チームパネル`, {
        告知タイトル表示: text.includes(world.noticeTitle),
        お知らせ見出し: /お知らせ|告知/.test(text),
        dashboardAPI: Object.fromEntries(Object.entries(bodies).map(([k, v]) => [k, { 告知タイトル含む: v.includes(world.noticeTitle), teamNoticesあり: /"teamNotices"\s*:\s*\[/.test(v) || /"teamNotices":\[/.test(v), 先頭: v.slice(0, 160).replace(/\s+/g, ' ') }])),
        '4xx5xx': t.calls.filter((c) => c.status >= 400).map((c) => `${c.status} ${c.code} ${mask(c.path, [[world.teamA.slug, 'TEAM_A'], [world.orgA.slug, 'ORG_A']])}`),
        consoleエラー: t.consoleErrs.slice(0, 3),
      })
      // 画面を通さない比較（同じセッションからの dashboard API 全文での判定）
      const direct = await page.evaluate(async ({ api, slug }) => {
        const r = await fetch(`${api}/api/v1/dashboard/team/${slug}`, { credentials: 'include' })
        const body = await r.json().catch(() => ({}))
        const d = body?.data ?? {}
        return { status: r.status, teamNotices件数: Array.isArray(d.teamNotices) ? d.teamNotices.length : d.teamNotices === null ? 'null' : 'なし', 先頭タイトル: JSON.stringify(d.teamNotices?.[0] ?? null).slice(0, 220), viewerRole: d.viewerRole }
      }, { api: API, slug: world.teamA.slug })
      obs(`E1.${label}.ブラウザ経由dashboardAPI`, direct)
    } finally {
      await context.close()
    }
  }
})

// ───────────────────────── 0901 O-1 ─────────────────────────

rtest('O-1 切り分け: 新規大会の各画面でトーストが出たときの API の URL・status・error.code', async ({ browser }) => {
  test.setTimeout(900_000)
  const subs = ['communication', 'submissions', 'fees', 'files', 'rosters', 'fixtures', 'standings', 'rankings', 'matrix', 'score-entry'] as const
  for (const [label, actor] of [['admin', world.adminA], ['member', world.memA]] as const) {
    for (const sub of subs) {
      const { context, page } = await openBare(browser, actor)
      try {
        const t = tap(page)
        await page.goto(`/organizations/${world.orgA.slug}/tournaments/${world.tourA}/${sub}`, { waitUntil: 'commit' })
        await waitHydrated(page)
        await page.waitForLoadState('networkidle', { timeout: 60_000 }).catch(() => undefined)
        await page.waitForTimeout(4_000)
        const toasts = await visibleToasts(page)
        const bad = t.calls.filter((c) => c.status >= 400).map((c) => `${c.method} ${c.status} ${c.code} ${mask(c.path, [[String(world.orgA.numericId), 'ORG_A'], [world.orgA.slug, 'ORG_SLUG'], [String(world.tourA), 'T']])}${c.query} msg=${c.message}`)
        obs(`O1.${label}.${sub}`, {
          URL: page.url().replace(/^https?:\/\/[^/]+/, '').replace(world.orgA.slug, 'ORG_A').replace(String(world.tourA), 'T'),
          h1: (await page.getByRole('heading', { level: 1 }).allInnerTexts()).map((s) => s.slice(0, 30)),
          toast: toasts,
          '4xx5xx': Array.from(new Set(bad)),
          消去後: t.calls.length,
          横スクロールなし: await noHorizontalOverflow(page),
          pageerror: t.pageErrs.slice(0, 2),
        })
        if (label === 'admin') await shot(page, `O1-admin-${sub}`)
      } finally {
        await context.close()
      }
    }
  }
})


rtest('O-1 切り分け補足: 管理者の連絡スペース一覧の応答（掲示板参照IDの有無）', async () => {
  const r = await apiAs(world.adminA, 'GET', `/api/v1/tournaments/${world.tourA}/contact-spaces`)
  obs('O1.補足.contact-spaces(管理者)', { status: r.status, 本文: JSON.stringify(r.body).replace(/\d{4,}/g, 'N').slice(0, 700) })
  const m = await apiAs(world.memA, 'GET', `/api/v1/tournaments/${world.tourA}/contact-spaces`)
  obs('O1.補足.contact-spaces(一般会員)', { status: m.status, code: m.body?.error?.code ?? null, message: m.body?.error?.message ?? null })
})

// ───────────────────────── 後始末 ─────────────────────────

test('後始末: 大会を片付ける。組織・チームは globalTeardown が削除', async () => {
  const r = await apiAs(world.adminA, 'DELETE', `/api/v1/organizations/${world.orgA.numericId}/tournaments/${world.tourA}`)
  obs('後始末.大会', { status: r.status })
  obs('観測中の失敗', problems)
  expect(problems, `観測中の失敗: ${problems.join(' || ')}`).toEqual([])
})
