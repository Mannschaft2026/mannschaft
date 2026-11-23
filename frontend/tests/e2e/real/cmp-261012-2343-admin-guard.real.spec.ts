import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import {
  expect,
  test,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

/**
 * CMP-261012-2343 実機E2E: `pages/admin/` のうち SYSTEM_ADMIN 専用 23 ページの入口ガード
 * （`middleware: ['auth', 'system-admin']`）を、実 BE・実 FE・実ブラウザで確認する。
 *
 * 読み取り専用（データを作成・更新しない）。ログインはロールごとに 1 回だけ行い、コンテキストを使い回す
 * （ログインは email+IP で 1 分 10 回の制限があるため）。API は「ログイン」と「BE 側の 403 確認」だけに使い、
 * 対象操作（URL 直打ち・メニュー経由の遷移）はブラウザで行う。
 *
 * 実行: BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8080 \
 *   npx playwright test -c playwright.cloud.local.config.ts tests/e2e/real/cmp-261012-2343-admin-guard.real.spec.ts
 */

const API = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PASSWORD = 'TestPass2026!'
const ACCOUNTS = {
  sysadmin: { email: 'e2e-admin@test.mannschaft.local', password: PASSWORD },
  user: { email: 'e2e-user@test.mannschaft.local', password: PASSWORD },
  teamAdmin: { email: 'e2e-dummy-1@test.mannschaft.local', password: PASSWORD },
} as const
type Role = keyof typeof ACCOUNTS

/** 番人（admin-pages-guard.spec.ts）の SYSTEM_ADMIN_PAGES と同じ一覧を、そのソースから読む（一致を検証するため）。 */
function readGuardSpecPages(): string[] {
  const src = readFileSync(
    join(process.cwd(), 'tests', 'unit', 'middleware', 'admin-pages-guard.spec.ts'),
    'utf8',
  )
  const block = /const SYSTEM_ADMIN_PAGES = \[([\s\S]*?)\]/.exec(src)?.[1] ?? ''
  return [...block.matchAll(/'([^']+)\.vue'/g)].map((m) => m[1]!)
}

const PAGES = readGuardSpecPages()
const TITLE = 'アクセスできません'
const BODY = '管理者権限が必要です'

const contexts = {} as Record<Role, BrowserContext>
let anonContext: BrowserContext
/** 管理者で各ページを開いたときの初回 GET API（権限なしの BE 確認に使う）。 */
const pageApis = new Map<string, string[]>()
/** ページ非依存（ログイン直後の共通シェル）が呼ぶ GET API。ページ固有の API から除く。 */
const baselineApis = new Set<string>()

const norm = (url: string) => url.replace(/[?#].*$/, '')

async function loginWithRetry(page: Page, role: Role): Promise<void> {
  for (let attempt = 0; attempt < 8; attempt++) {
    try {
      await loginViaApi(page, ACCOUNTS[role], { apiBaseUrl: API })
      return
    } catch (e) {
      if (!String(e).includes('429')) throw e
      await page.waitForTimeout(20_000)
    }
  }
  throw new Error(`ログインが 429 のまま完了しません: ${role}`)
}

async function newPageIn(role: Role | 'anon'): Promise<Page> {
  const ctx = role === 'anon' ? anonContext : contexts[role]
  const page = await ctx.newPage()
  // SSR/初期描画を含め、/admin 配下にいる間に h1（ページ本体）が一瞬でも DOM に載ったかを記録する。
  await page.addInitScript(() => {
    const w = window as unknown as { __leak?: string[] }
    w.__leak = []
    const scan = () => {
      if (!location.pathname.startsWith('/admin/')) return
      document.querySelectorAll('h1').forEach((h) => {
        const text = (h.textContent ?? '').trim()
        if (text && !w.__leak!.includes(text)) w.__leak!.push(text)
      })
    }
    new MutationObserver(scan).observe(document, { childList: true, subtree: true, characterData: true })
    document.addEventListener('DOMContentLoaded', scan)
  })
  return page
}

async function expectDeniedToast(page: Page): Promise<void> {
  await expect(page.getByText(TITLE, { exact: true }).first()).toBeVisible({ timeout: 30_000 })
  await expect(page.getByText(BODY, { exact: true }).first()).toBeVisible()
}

test.setTimeout(240_000)

test.beforeAll(async ({ browser }: { browser: Browser }) => {
  for (const role of Object.keys(ACCOUNTS) as Role[]) {
    contexts[role] = await browser.newContext({ baseURL: process.env.BASE_URL, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
    const p = await contexts[role].newPage()
    await loginWithRetry(p, role)
    await p.close()
  }
  anonContext = await browser.newContext({ baseURL: process.env.BASE_URL, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
})

test.afterAll(async () => {
  for (const c of [...Object.values(contexts), anonContext]) await c?.close()
})

test('番人テストの一覧と実機で回す一覧が一致する（23ページ）', async () => {
  expect(PAGES).toHaveLength(23)
  expect(new Set(PAGES).size).toBe(23)
})

test.describe('A. 正の視点: SYSTEM_ADMIN は各ページの本体が表示される', () => {
  test('A0 基準: /dashboard が呼ぶ共通 API を記録する', async () => {
    const page = await newPageIn('sysadmin')
    page.on('response', (r) => {
      if (r.request().method() === 'GET' && r.url().includes('/api/v1/')) baselineApis.add(norm(r.url()))
    })
    const navLoaded = page.waitForResponse((r) => norm(r.url()) === `${API}/api/v1/settings/nav`, { timeout: 120_000 })
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await navLoaded
    expect(page.url()).toContain('/dashboard')
    await page.close()
  })

  for (const p of PAGES) {
    test(`A /admin/${p}`, async () => {
      const page = await newPageIn('sysadmin')
      const apis: string[] = []
      page.on('response', (r) => {
        if (r.request().method() === 'GET' && r.url().includes('/api/v1/')) apis.push(norm(r.url()))
      })
      // 初回 API の応答を条件待ちにする（G の対象表と実測の一致も、ここで確認する）。
      const expectedResponses = (ADMIN_APIS[p] ?? []).map((path) =>
        page.waitForResponse((r) => norm(r.url()) === `${API}${path}`, { timeout: 120_000 }),
      )
      await Promise.all([page.goto(`/admin/${p}`, { waitUntil: 'domcontentloaded' }), ...expectedResponses])
      await expect(page.locator('h1').first()).toBeVisible({ timeout: 120_000 })
      expect(new URL(page.url()).pathname).toBe(`/admin/${p}`)
      await expect(page.getByText(TITLE, { exact: true })).toHaveCount(0)
      const h1 = ((await page.locator('h1').first().textContent()) ?? '').trim()
      const specific = [...new Set(apis)].filter((u) => !baselineApis.has(u))
      pageApis.set(p, specific)
      console.log(`PAGE|${p}|h1=${h1}|apis=${specific.join(',')}`)
      await page.close()
    })
  }

  test('A+ アプリ内遷移: /system-admin のクイックリンクをクリックして /admin/users へ入れる', async () => {
    const page = await newPageIn('sysadmin')
    await page.goto('/system-admin', { waitUntil: 'domcontentloaded' })
    const link = page.locator('a[href="/admin/users"]').first()
    await expect(link).toBeVisible({ timeout: 120_000 })
    await link.click()
    await expect(page).toHaveURL(/\/admin\/users$/, { timeout: 60_000 })
    await expect(page.locator('h1').first()).toBeVisible({ timeout: 60_000 })
    await page.close()
  })
})

for (const role of ['user', 'teamAdmin'] as const) {
  test.describe(`B. 負の視点 (${role}): URL 直打ちで /dashboard へ戻されトーストが出る`, () => {
    for (const p of PAGES) {
      test(`B-${role} 直打ち /admin/${p}`, async () => {
        const page = await newPageIn(role)
        await page.goto(`/admin/${p}`, { waitUntil: 'domcontentloaded' })
        await expect(page).toHaveURL(/\/dashboard/, { timeout: 120_000 })
        await expectDeniedToast(page)
        await page.close()
      })
    }
  })
}

for (const role of ['user', 'teamAdmin'] as const) {
  test.describe(`C. ページ本体の漏れ (${role}): /admin 配下にいる間に h1 が DOM に載らない`, () => {
    for (const p of PAGES) {
      test(`C-${role} 本体が描画されない /admin/${p}`, async () => {
        const page = await newPageIn(role)
        await page.goto(`/admin/${p}`, { waitUntil: 'domcontentloaded' })
        await expect(page).toHaveURL(/\/dashboard/, { timeout: 120_000 })
        const leak = await page.evaluate(() => (window as unknown as { __leak?: string[] }).__leak ?? [])
        console.log(`LEAK|${role}|${p}|${JSON.stringify(leak)}`)
        expect(leak, `/admin/${p} の本体 h1 が権限なしユーザーに描画された`).toEqual([])
        await page.close()
      })
    }
  })
}

test.describe('C2. SSR の生 HTML に本体が含まれない（権限なしユーザーの Cookie 付き）', () => {
  for (const p of PAGES) {
    test(`C2 SSR /admin/${p}`, async () => {
      const expected = ((await (async () => {
        // 管理者で見たときの h1 を基準にする
        const page = await newPageIn('sysadmin')
        await page.goto(`/admin/${p}`, { waitUntil: 'domcontentloaded' })
        await expect(page.locator('h1').first()).toBeVisible({ timeout: 120_000 })
        const t = await page.locator('h1').first().textContent()
        await page.close()
        return t
      })()) ?? '').trim()
      expect(expected).not.toBe('')
      const res = await contexts.user.request.get(`/admin/${p}`)
      const html = await res.text()
      console.log(`SSR|${p}|status=${res.status()}|containsH1=${html.includes(expected)}`)
      expect(html.includes(`>${expected}<`), `SSR HTML に本体 h1「${expected}」が含まれる`).toBe(false)
    })
  }
})

test.describe('D. アプリ内遷移（クライアント遷移）でも止まる', () => {
  for (const role of ['user', 'teamAdmin'] as const) {
    for (const p of ['users', 'audit-logs']) {
      test(`D-${role} /dashboard から router.push /admin/${p}`, async () => {
        const page = await newPageIn(role)
        await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
        await page.waitForFunction(() => {
          const el = document.querySelector('#__nuxt') as (HTMLElement & { __vue_app__?: unknown }) | null
          return !!el?.__vue_app__
        }, undefined, { timeout: 120_000 })
        await page.evaluate((path) => {
          const el = document.querySelector('#__nuxt') as unknown as {
            __vue_app__: { config: { globalProperties: { $router: { push: (p: string) => Promise<unknown> } } } }
          }
          void el.__vue_app__.config.globalProperties.$router.push(path)
        }, `/admin/${p}`)
        await expectDeniedToast(page)
        await expect(page).toHaveURL(/\/dashboard/)
        const leak = await page.evaluate(() => (window as unknown as { __leak?: string[] }).__leak ?? [])
        expect(leak).toEqual([])
        await page.close()
      })
    }
  }
})

test.describe('E. 導線: 一般ユーザー・チーム ADMIN の画面に SYSTEM_ADMIN 専用ページへのリンクが出ない', () => {
  for (const role of ['user', 'teamAdmin'] as const) {
    test(`E-${role} /dashboard に 23 ページへのリンクが無い`, async () => {
      const page = await newPageIn(role)
      // ナビ設定の応答を待ってから導線を数える（固定待ちにしない）。
      const navLoaded = page.waitForResponse((r) => norm(r.url()) === `${API}/api/v1/settings/nav`, { timeout: 120_000 })
      await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
      await navLoaded
      await expect(page.locator('#__nuxt *').first()).toBeVisible()
      const hrefs = await page.locator('a[href^="/admin/"]').evaluateAll((as) =>
        as.map((a) => (a as HTMLAnchorElement).getAttribute('href')),
      )
      const sysAdminHrefs = hrefs.filter((h) => PAGES.some((p) => h === `/admin/${p}`))
      expect(sysAdminHrefs).toEqual([])
      await page.close()
    })
    test(`E-${role} /system-admin 直打ちも /dashboard へ戻る（クイックリンク一覧に到達できない）`, async () => {
      const page = await newPageIn(role)
      await page.goto('/system-admin', { waitUntil: 'domcontentloaded' })
      await expect(page).toHaveURL(/\/dashboard/, { timeout: 120_000 })
      await expect(page.locator('a[href="/admin/users"]')).toHaveCount(0)
      await page.close()
    })
  }
})

test.describe('F. 未ログイン: 直打ちで /login へ送られる', () => {
  for (const p of PAGES) {
    test(`F /admin/${p}`, async () => {
      const page = await newPageIn('anon')
      await page.goto(`/admin/${p}`, { waitUntil: 'domcontentloaded' })
      await expect(page).toHaveURL(/\/login/, { timeout: 120_000 })
      await page.close()
    })
  }
})

async function statusOf(req: APIRequestContext, url: string): Promise<number> {
  return (await req.get(url)).status()
}

/**
 * 各ページが初回表示で呼ぶ管理系 GET API（A で管理者が開いたときに実測した一覧。A が同じ一覧を再確認する）。
 * G は A に依存させない（別ワーカーで単独実行しても動く）ため、一覧を固定値で持つ。
 * templates は一覧 API が無く初回取得をしないため、存在しない ID への PATCH（管理者なら 404 で何も変更されない）で認可だけを見る。
 */
const ADMIN_APIS: Record<string, string[]> = {
  'ad-credit-limit-requests': ['/api/v1/system-admin/ad-credit-limit-requests'],
  'ad-rate-cards': ['/api/v1/system-admin/ad-rate-cards'],
  'advertiser-accounts': ['/api/v1/system-admin/advertiser-accounts'],
  'affiliate-settings': ['/api/v1/system-admin/affiliate-configs'],
  announcements: ['/api/v1/system-admin/announcements'],
  appeals: ['/api/v1/system-admin/appeals'],
  'audit-logs': ['/api/v1/admin/audit-logs'],
  campaigns: ['/api/v1/system-admin/discount-campaigns'],
  'feature-flags': ['/api/v1/system-admin/feature-flags'],
  maintenance: ['/api/v1/system-admin/maintenance-schedules'],
  moderation: ['/api/v1/admin/moderation/reports'],
  'module-pricing': ['/api/v1/system-admin/module-pricing'],
  modules: ['/api/v1/system-admin/modules'],
  organizations: ['/api/v1/system-admin/dashboard/organizations'],
  packages: ['/api/v1/system-admin/packages', '/api/v1/system-admin/modules'],
  'point-card-synonyms': ['/api/v1/admin/point-cards/synonyms'],
  reports: ['/api/v1/system-admin/reports', '/api/v1/admin/reports/stats'],
  'seasonal-wallpapers': ['/api/v1/system-admin/template-wallpapers'],
  'storage-plans': ['/api/v1/system-admin/storage-plans', '/api/v1/system-admin/storage-usage'],
  'tax-settings': ['/api/v1/system-admin/tax-settings'],
  users: ['/api/v1/system-admin/dashboard/users'],
  'villages/creation-requests': ['/api/v1/admin/village-creation-requests'],
}
const WRITE_ONLY_PROBES: Record<string, string> = {
  templates: '/api/v1/system-admin/templates/999999999',
}

test('G0 BE 確認の対象表が 23 ページを過不足なく覆っている', () => {
  const covered = new Set([...Object.keys(ADMIN_APIS), ...Object.keys(WRITE_ONLY_PROBES)])
  expect([...covered].sort()).toEqual([...PAGES].sort())
})

test.describe('G. BE 側: 権限なしでは各ページの管理系 API が 403（未ログインは 401）', () => {
  for (const p of PAGES) {
    test(`G /admin/${p} の管理系 API`, async ({ playwright }) => {
      const anon = await playwright.request.newContext()
      const probe = WRITE_ONLY_PROBES[p]
      if (probe) {
        const url = `${API}${probe}`
        const send = async (req: APIRequestContext) => (await req.patch(url, { data: {} })).status()
        const adminStatus = await send(contexts.sysadmin.request)
        const userStatus = await send(contexts.user.request)
        const teamStatus = await send(contexts.teamAdmin.request)
        const anonStatus = await send(anon)
        console.log(`BE|${p}|PATCH ${url}|admin=${adminStatus}|user=${userStatus}|teamAdmin=${teamStatus}|anon=${anonStatus}`)
        expect.soft(adminStatus, '管理者は認可を通る（存在しない ID なので 404）').toBe(404)
        expect.soft(userStatus).toBe(403)
        expect.soft(teamStatus).toBe(403)
        expect.soft(anonStatus).toBe(401)
      }
      for (const path of ADMIN_APIS[p] ?? []) {
        const url = `${API}${path}`
        const adminStatus = await statusOf(contexts.sysadmin.request, url)
        const userStatus = await statusOf(contexts.user.request, url)
        const teamStatus = await statusOf(contexts.teamAdmin.request, url)
        const anonStatus = await statusOf(anon, url)
        console.log(`BE|${p}|${url}|admin=${adminStatus}|user=${userStatus}|teamAdmin=${teamStatus}|anon=${anonStatus}`)
        // 権限なしの 403/401 を先に検査する（BE が素通りしていないかが本題）。
        expect.soft(userStatus).toBe(403)
        expect.soft(teamStatus).toBe(403)
        expect.soft(anonStatus).toBe(401)
      }
      await anon.dispose()
    })
  }
})

/**
 * 既知の実装欠陥: 管理者でも初回 API が 404（BE に Controller が無い）。入口ガードとは別件で、台帳の別行で追跡する。
 * test.fail() で固定してあるため、BE が実装されて 2xx になるとこのテストが「想定外の成功」で赤になり、固定を外す合図になる。
 * 入口ガード側の検査（G の 403/401、B/C/F の直打ち）は通常のテストのまま。
 */
const KNOWN_ADMIN_API_DEFECTS: Record<string, string> = {
  campaigns: 'CMP-260917-0121: BE に system-admin/discount-campaigns の Controller が無い',
  'tax-settings': 'CMP-260917-0123: BE に system-admin/tax-settings の Controller が無い（税設定は billing/tax-codes のみ）',
  'module-pricing': 'CMP-261123-1833: BE に system-admin/module-pricing の Controller が無い',
  packages: 'CMP-261123-1833: BE に system-admin/packages の Controller が無い',
  'storage-plans': 'CMP-261123-1833: BE に system-admin/storage-plans・storage-usage の Controller が無い',
}

test.describe('H. BE 側: 管理者では各ページの初回 API が 2xx（既知の欠陥 5 ページは test.fail で固定）', () => {
  for (const p of PAGES.filter((x) => ADMIN_APIS[x])) {
    test(`H /admin/${p} の管理者 API が成功する`, async () => {
      const known = KNOWN_ADMIN_API_DEFECTS[p]
      if (known) test.fail(true, known)
      for (const path of ADMIN_APIS[p]!) {
        const status = await statusOf(contexts.sysadmin.request, `${API}${path}`)
        expect(status, `管理者で ${path} が成功しない`).toBeLessThan(300)
      }
    })
  }
})
