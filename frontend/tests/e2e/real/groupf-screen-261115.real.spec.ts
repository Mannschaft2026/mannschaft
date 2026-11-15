import { appendFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { expect, test, type Page } from '@playwright/test'
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
  openAs,
  FE,
  sql,
  type Actor,
  type Scope,
} from './helpers/f0121-org-groups-world'

/**
 * P1 群F 3戦役の画面層（実機E2E・アリシゼーション）。2026-11-15。
 *
 * - CMP-261002-1604: 確認通知。他組織の確認通知 ID は不在 ID と同じ表示（存在が漏れない）。正規ユーザーは画面で見て操作できる。
 * - CMP-261101-2008: ダッシュボードの組織告知。応答に target_team_ids が無く、画面が崩れない。
 * - CMP-261006-0901: 大会の部門・参加チーム。自組織は表示、他組織 ID を差し替えた URL 直打ちは不在と同じ。
 *
 * 作成するデータはすべて名前/メールに e2e-261115 系の印（RUN 接尾辞）を付け、globalTeardown が組織・チームを削除する。
 * 観測結果は test-results/groupf-obs.jsonl に追記する（md 記録用）。画像は docs/evidence/screenshots/groupF-261115/。
 */

const SHOT_DIR = path.resolve('..', 'docs', 'evidence', 'screenshots', 'groupF-261115')
const OBS = path.resolve('test-results', 'groupf-obs.jsonl')
mkdirSync(SHOT_DIR, { recursive: true })
mkdirSync(path.dirname(OBS), { recursive: true })

function obs(key: string, value: unknown) {
  appendFileSync(OBS, `${JSON.stringify({ key, value })}\n`)
  console.log(`[obs] ${key}: ${JSON.stringify(value)}`)
}

async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(SHOT_DIR, `${name}.png`), fullPage: false })
}

async function bodyText(page: Page): Promise<string> {
  return (await page.locator('body').innerText()).replace(/\s+/g, ' ').trim()
}

async function noHorizontalOverflow(page: Page): Promise<boolean> {
  return page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)
}

test.describe.configure({ mode: 'serial' })

/** 失敗しても後続の観測を続けるための包み。失敗は problems に集め、最後のテストでまとめて落とす（握りつぶさない）。 */
const problems: string[] = []
function rtest(name: string, fn: (args: { browser: import('@playwright/test').Browser }) => Promise<void>) {
  test(name, async ({ browser }) => {
    try {
      await fn({ browser })
    } catch (error) {
      const message = String((error as Error).message).replace(/\[[0-9;]*m/g, '').slice(0, 400)
      problems.push(`${name}: ${message}`)
      obs(`FAIL ${name}`, message)
    }
  })
}

/** ログインだけ済ませた空のページを返す（リスナーを張ってから対象 URL へ移動するため）。 */
async function openBare(browser: import('@playwright/test').Browser, actor: Actor) {
  const context = await browser.newContext({ baseURL: FE, storageState: { cookies: [], origins: [] }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo', viewport: { width: 1280, height: 900 } })
  const page = await context.newPage()
  await page.goto(`${FE}/robots.txt`)
  await loginViaApi(page, actor, { apiBaseUrl: API, deferNavigation: true })
  return { context, page }
}

async function waitHydrated(page: Page) {
  await page.waitForFunction(() => {
    const el = document.querySelector('#__nuxt')
    return el !== null && '__vue_app__' in el && el.childElementCount > 0
  }, undefined, { timeout: 180_000 })
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
  tourA: number
  tourB: number
  divA: number
  titleA1: string
  titleA2: string
  tourNameA: string
  noticeTitle: string
}

const SEED_PASSWORD = 'TestPass2026!'
const seedActor = (label: string, email: string, id: number): Actor => ({ label, email, password: SEED_PASSWORD, id, nickname: label })

test('準備: 検証用の組織・チーム・確認通知・大会を自前で作る', async () => {
  world.adminA = await createActor('AdminA', 'groupf')
  world.memA = await createActor('MemA', 'groupf')
  world.adminB = await createActor('AdminB', 'groupf')
  world.orgA = await createOrg(world.adminA, 'e2e-261115 組織A')
  world.orgB = await createOrg(world.adminB, 'e2e-261115 組織B')
  world.teamA = await createTeam(world.adminA, 'e2e-261115 チームA')
  await joinScope('organizations', world.adminA, world.orgA.slug, world.memA, 'MEMBER')
  await joinScope('teams', world.adminA, world.teamA.slug, world.memA, 'MEMBER')
  await affiliate(world.adminA, world.orgA.slug, world.adminA, world.teamA.slug)

  // 組織告知（組織Aからチームへ一斉送信）。ダッシュボードのお知らせ表示（CMP-261101-2008）の前提。
  world.noticeTitle = 'e2e-261115 組織告知'
  const bc = await apiAs(world.adminA, 'POST', `/api/v1/organizations/${world.orgA.numericId}/broadcast`, {
    channel: 'TIMELINE_POST',
    targetRole: 'MEMBERS_AND_ABOVE',
    targetTeamIds: [world.teamA.slug],
    priority: 'NORMAL',
    content: { title: world.noticeTitle, body: '画面層検証用の組織告知（群F）' },
  })
  obs('準備.組織告知', { status: bc.status, body: JSON.stringify(bc.body).slice(0, 300) })
  await new Promise((resolve) => setTimeout(resolve, 8_000))
  for (const [label, actor, kind, slug] of [['orgA管理者', world.adminA, 'organization', world.orgA.slug], ['teamA会員', world.memA, 'team', world.teamA.slug], ['teamA管理者', world.adminA, 'team', world.teamA.slug]] as const) {
    const d = await apiAs(actor, 'GET', `/api/v1/dashboard/${kind}/${slug}`)
    const raw = JSON.stringify(d.body)
    obs(`準備.告知のダッシュボード到達.${label}`, { 告知欄: JSON.stringify(d.body?.data?.orgNotices ?? d.body?.data?.teamNotices).slice(0, 400), status: d.status, 告知タイトル含む: raw.includes(world.noticeTitle), source_type件数: (raw.match(/"source_type"/g) ?? []).length, target_team_ids含む: /target_?team_?ids/i.test(raw), 最上位キー: Object.keys(d.body?.data ?? {}).slice(0, 25) })
  }

  // 確認通知（組織A 2件・組織B 1件）。送信は非同期（202）。配信完了を待ってから ID を引く。
  world.titleA1 = 'e2e-261115 確認通知A1'
  world.titleA2 = 'e2e-261115 確認通知A2'
  await must(world.adminA, 'POST', `/api/v1/organizations/${world.orgA.numericId}/confirmable-notifications`, {
    title: world.titleA1,
    body: '画面層検証用（群F）',
    priority: 'NORMAL',
    targets: [{ type: 'TEAM', id: world.teamA.numericId }],
  }, [202])
  await must(world.adminA, 'POST', `/api/v1/organizations/${world.orgA.numericId}/confirmable-notifications`, {
    title: world.titleA2,
    body: '画面層検証用（群F）',
    priority: 'NORMAL',
    targets: [{ type: 'TEAM', id: world.teamA.numericId }],
  }, [202])
  await must(world.adminA, 'POST', `/api/v1/teams/${world.teamA.numericId}/confirmable-notifications`, {
    title: 'e2e-261115 確認通知T1',
    body: '画面層検証用（群F・チーム発信）',
    priority: 'NORMAL',
  }, [202])
  const teamB = await createTeam(world.adminB, 'e2e-261115 チームB')
  const memB = await createActor('MemB', 'groupf')
  await joinScope('teams', world.adminB, teamB.slug, memB, 'MEMBER')
  await affiliate(world.adminB, world.orgB.slug, world.adminB, teamB.slug)
  await must(world.adminB, 'POST', `/api/v1/organizations/${world.orgB.numericId}/confirmable-notifications`, {
    title: 'e2e-261115 確認通知B1',
    body: '画面層検証用（群F・他組織）',
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
  world.notifB = await idOf(world.adminB, world.orgB, 'e2e-261115 確認通知B1')

  // 大会（組織A・組織B）。公開・受付中・1部門・参加チーム1。
  world.tourNameA = 'e2e-261115 大会A'
  const mkTour = async (admin: Actor, org: Scope, name: string, withTeam: boolean) => {
    const t = await must(admin, 'POST', `/api/v1/organizations/${org.numericId}/tournaments`, {
      name,
      format: 'LEAGUE',
      visibility: 'PUBLIC',
    })
    const tId = Number(t.data.id)
    await must(admin, 'PATCH', `/api/v1/organizations/${org.numericId}/tournaments/${tId}/status`, { status: 'OPEN' })
    const d = await must(admin, 'POST', `/api/v1/organizations/${org.numericId}/tournaments/${tId}/divisions`, { name: `e2e-261115 部門`, level: 1 })
    const divId = Number(d.data.id)
    if (withTeam) {
      await must(admin, 'POST', `/api/v1/organizations/${org.numericId}/tournaments/${tId}/divisions/${divId}/participants`, {
        teamId: world.teamA.numericId,
        displayName: 'e2e-261115 参加チームA',
      })
    }
    return { tId, divId }
  }
  const a = await mkTour(world.adminA, world.orgA, world.tourNameA, true)
  world.tourA = a.tId
  world.divA = a.divId
  const b = await mkTour(world.adminB, world.orgB, 'e2e-261115 大会B', false)
  world.tourB = b.tId
  obs('準備', { orgA: world.orgA.slug, orgB: world.orgB.slug, notifA1: world.notifA1, notifA2: world.notifA2, notifB: world.notifB, tourA: world.tourA, tourB: world.tourB })
})

// ───────────────────────── 1604 確認通知 ─────────────────────────

rtest('1604 実機: 管理者は確認通知設定画面で自組織の履歴を見てキャンセルできる', async ({ browser }) => {
  test.setTimeout(300_000)
  const path1 = `/organizations/${world.orgA.slug}/settings/confirmable-notifications`
  const { context, page } = await openBare(browser, world.adminA)
  try {
    const calls: Array<{ path: string; status: number; numericId?: unknown }> = []
    const consoleErrs: string[] = []
    page.on('console', (m) => { if (m.type() === 'error') consoleErrs.push(m.text().slice(0, 140)) })
    page.on('response', async (r) => {
      const u = new URL(r.url())
      if (u.pathname.startsWith('/api/v1/organizations/')) {
        let numericId: unknown
        if (u.pathname === `/api/v1/organizations/${world.orgA.slug}`) numericId = (await r.json().catch(() => ({})))?.data?.numericId
        calls.push({ path: u.pathname.replace(world.orgA.slug, 'ORG_A'), status: r.status(), numericId })
      }
    })
    // (a) 設定 URL の直打ち（フルロード）。
    await page.goto(path1, { waitUntil: 'commit' })
    await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toBeVisible({ timeout: 120_000 })
    await page.waitForTimeout(15_000)
    const directBody = await bodyText(page)
    obs('1604.admin.直打ち診断', { orgDetail取得: calls.some((c) => c.path === '/api/v1/organizations/ORG_A'), 空scopeIdへの要求: consoleErrs.filter((e) => e.includes('organizations//')).length, 履歴なし表示: directBody.includes('発信履歴がありません'), A1表示: directBody.includes(world.titleA1) })
    await shot(page, '1604-admin-direct-url')
    // (b) 組織ダッシュボードからサイドバーのリンクで移動（アプリ内遷移）。
    obs('step', '設定URL直打ちの観測を終えた。ダッシュボードへ移動')
    await page.goto(`/organizations/${world.orgA.slug}`, { waitUntil: 'commit' })
    await waitHydrated(page)
    obs('step', 'ダッシュボード水和済み')
    const link = page.locator(`a[href$="/settings/confirmable-notifications"]`).first()
    let viaDrawer = false
    await page.waitForTimeout(5_000)
    if (!(await link.isVisible())) {
      // 組織サイドバーはドロワー。開くボタンの名前を観測し、あれば開く（導線の観測）。
      const names = await page.getByRole('button').evaluateAll((els) => els.map((e) => (e.getAttribute('aria-label') ?? e.textContent ?? '').trim()).filter((n) => /ナビ|メニュー|サイドバー|ひろげる/.test(n)))
      obs('1604.admin.導線.ドロワー候補ボタン', names)
      const opener = page.getByTestId('scope-sidebar-toggle')
      await opener.click({ timeout: 20_000 })
      viaDrawer = true
    }
    await expect(link).toBeVisible({ timeout: 30_000 })
    obs('1604.admin.導線', { サイドバーのリンクまで: viaDrawer ? 'ドロワーを開く操作が必要' : '最初から表示' })
    await shot(page, '1604-admin-sidebar-drawer')
    const errsBefore = consoleErrs.length
    await link.click()
    await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toBeVisible({ timeout: 60_000 })
    await page.waitForTimeout(10_000)
    const spaText = await bodyText(page)
    obs('1604.admin.アプリ内遷移診断', { 履歴なし表示: spaText.includes('発信履歴がありません'), A1表示: spaText.includes(world.titleA1), 遷移後のconsoleエラー: consoleErrs.slice(errsBefore).filter((e) => e.includes('organizations//')).length, 遷移後のconsoleエラー先頭: consoleErrs.slice(errsBefore, errsBefore + 3), URL: page.url().replace(/^https?:\/\/[^/]+/, '').replace(world.orgA.slug, 'ORG_A') })
    await expect(page.getByText(world.titleA1)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(world.titleA2)).toBeVisible()
    await expect(page.getByText('e2e-261115 確認通知B1')).toHaveCount(0)
    obs('1604.admin.履歴', { A1: true, A2: true, B1_表示なし: true, 横スクロールなし: await noHorizontalOverflow(page) })
    await shot(page, '1604-admin-history')

    // A2 をキャンセル（画面操作）
    const row = page.locator('li, tr, div').filter({ hasText: world.titleA2 }).filter({ has: page.locator('button') }).last()
    const cancelRes = page.waitForResponse((r) => r.request().method() === 'POST' && /\/confirmable-notifications\/\d+\/cancel$/.test(new URL(r.url()).pathname), { timeout: 30_000 })
    await row.getByRole('button', { name: /キャンセル|取り消し|取消/ }).first().click()
    const confirmBtn = page.getByRole('button', { name: /はい|OK|実行|キャンセルする|取り消す/ })
    if (await confirmBtn.first().isVisible({ timeout: 2_000 }).catch(() => false)) await confirmBtn.first().click()
    const res = await cancelRes
    obs('1604.admin.cancel', { status: res.status() })
    expect([200, 204]).toContain(res.status())
    await shot(page, '1604-admin-after-cancel')
  } finally {
    await context.close()
  }
})

rtest('1604 実機: チーム側の確認通知設定画面（直打ち）で自チームの履歴が見える', async ({ browser }) => {
  const { context, page } = await openBare(browser, world.adminA)
  try {
    const consoleErrs: string[] = []
    page.on('console', (m) => { if (m.type() === 'error') consoleErrs.push(m.text().slice(0, 140)) })
    await page.goto(`/teams/${world.teamA.slug}/settings/confirmable-notifications`, { waitUntil: 'commit' })
    await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toBeVisible({ timeout: 120_000 })
    await page.waitForTimeout(10_000)
    const text = await bodyText(page)
    obs('1604.team.直打ち', { T1表示: text.includes('e2e-261115 確認通知T1'), 履歴なし表示: text.includes('発信履歴がありません'), 空scopeId要求: consoleErrs.filter((e) => /scopeId|teams\/\/|undefined/.test(e)).length, consoleErrs: consoleErrs.slice(0, 3) })
    await shot(page, '1604-team-direct-url')
  } finally {
    await context.close()
  }
})

rtest('1604 実機: 一般会員は管理画面を直打ちしても確認通知設定を表示しない', async ({ browser }) => {
  const path1 = `/organizations/${world.orgA.slug}/settings/confirmable-notifications`
  const { context, page } = await openAs(browser, world.memA, path1)
  try {
    await page.waitForTimeout(3_000)
    const text = await bodyText(page)
    obs('1604.member.直打ち', { url: page.url().replace(/^https?:\/\/[^/]+/, ''), heading表示: text.includes('確認通知設定'), タイトル表示: text.includes(world.titleA1) })
    await shot(page, '1604-member-direct')
    await expect(page.getByText(world.titleA1)).toHaveCount(0)
    await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toHaveCount(0)
  } finally {
    await context.close()
  }
})

rtest('1604 実機: 他組織の管理者が組織Aの設定URLを直打ちしても何も見えない', async ({ browser }) => {
  const path1 = `/organizations/${world.orgA.slug}/settings/confirmable-notifications`
  const { context, page } = await openAs(browser, world.adminB, path1)
  try {
    await page.waitForTimeout(3_000)
    const text = await bodyText(page)
    obs('1604.otherOrgAdmin.直打ち', { url: page.url().replace(/^https?:\/\/[^/]+/, ''), A1表示: text.includes(world.titleA1), heading表示: text.includes('確認通知設定') })
    await shot(page, '1604-otherorg-direct')
    await expect(page.getByText(world.titleA1)).toHaveCount(0)
    await expect(page.getByText(world.titleA2)).toHaveCount(0)
  } finally {
    await context.close()
  }
})

rtest('1604 実機: 他組織の確認通知 ID は不在 ID と同じ応答（画面のブラウザセッションから確認）', async ({ browser }) => {
  const { context, page } = await openAs(browser, world.adminA, `/organizations/${world.orgA.slug}/settings/confirmable-notifications`)
  try {
    await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toBeVisible({ timeout: 60_000 })
    const probe = async (id: number) =>
      page.evaluate(
        async ({ api, orgId, nid }) => {
          const out: Record<string, { status: number; code: string | null; message: string | null }> = {}
          for (const suffix of ['', '/recipients']) {
            const r = await fetch(`${api}/api/v1/organizations/${orgId}/confirmable-notifications/${nid}${suffix}`, { credentials: 'include' })
            let body: { error?: { code?: string; message?: string }; code?: string; message?: string } = {}
            try { body = await r.json() } catch { /* 本文なし */ }
            out[suffix || '/'] = { status: r.status, code: body.error?.code ?? body.code ?? null, message: body.error?.message ?? body.message ?? null }
          }
          return out
        },
        { api: API, orgId: world.orgA.numericId, nid: id },
      )
    const cross = await probe(world.notifB)
    const absent = await probe(987654321)
    obs('1604.他組織ID応答', cross)
    obs('1604.不在ID応答', absent)
    // 画面セッション（Cookie）で通らない場合に備え、同一の比較を Bearer でも行う
    const crossB = await apiAs(world.adminA, 'GET', `/api/v1/organizations/${world.orgA.numericId}/confirmable-notifications/${world.notifB}`)
    const absentB = await apiAs(world.adminA, 'GET', `/api/v1/organizations/${world.orgA.numericId}/confirmable-notifications/987654321`)
    obs('1604.Bearer比較', { cross: crossB.status, absent: absentB.status, crossBody: JSON.stringify(crossB.body).replace(/\d{6,}/g, 'N'), absentBody: JSON.stringify(absentB.body).replace(/\d{6,}/g, 'N') })
    expect(crossB.status).toBe(absentB.status)
    expect(JSON.stringify(crossB.body).replace(/\d+/g, 'N')).toBe(JSON.stringify(absentB.body).replace(/\d+/g, 'N'))
    expect(cross['/']).toEqual(absent['/'])
    expect(cross['/recipients']).toEqual(absent['/recipients'])
  } finally {
    await context.close()
  }
})

// ───────────────────────── 0901 大会 ─────────────────────────

rtest('0901 実機: 自組織の大会ページに部門と参加チームが表示される（管理者・一般会員）', async ({ browser }) => {
  for (const [label, actor] of [['admin', world.adminA], ['member', world.memA]] as const) {
    const apiCalls: Array<{ path: string; status: number }> = []
    const { context, page } = await openBare(browser, actor)
    try {
      page.on('response', (r) => {
        const p = new URL(r.url()).pathname
        if (/\/tournaments\/\d+\/divisions/.test(p)) apiCalls.push({ path: p.replace(/\d+/g, 'N'), status: r.status() })
      })
      await page.goto(`/organizations/${world.orgA.slug}/tournaments/${world.tourA}`, { waitUntil: 'commit' })
      await expect(page.getByText('e2e-261115 部門').first()).toBeVisible({ timeout: 120_000 })
      await expect(page.getByText(/e2e-261115 (参加)?チームA/).first()).toBeVisible({ timeout: 60_000 })
      obs(`0901.${label}.大会ページ`, { 部門: true, 参加チーム: true, API: apiCalls, 横スクロールなし: await noHorizontalOverflow(page) })
      await shot(page, `0901-${label}-tournament`)
    } finally {
      await context.close()
    }
  }
})

rtest('0901 実機: 他組織の管理者が組織Bの URL に組織Aの大会IDを差し替えても、不在IDと同じ表示', async ({ browser }) => {
  const results: Record<string, { text: string; hasDivision: boolean; hasTeam: boolean }> = {}
  for (const [label, tId] of [['cross', world.tourA], ['absent', 987654]] as const) {
    const { context, page } = await openBare(browser, world.adminB)
    try {
      await page.goto(`/organizations/${world.orgB.slug}/tournaments/${tId}`, { waitUntil: 'commit' })
      await waitHydrated(page)
      await expect(page.getByText('大会一覧に戻る')).toBeVisible({ timeout: 120_000 })
      await page.waitForTimeout(8_000)
      const text = (await bodyText(page)).replace(/\d+/g, 'N')
      results[label] = { text, hasDivision: text.includes('e2e-261115 部門'), hasTeam: /e2e-261115 (参加)?チームA/.test(text) }
      await shot(page, `0901-crossorg-${label}`)
    } finally {
      await context.close()
    }
  }
  obs('0901.他組織ID差替', results)
  expect(results.cross.hasDivision).toBe(false)
  expect(results.cross.hasTeam).toBe(false)
  expect(results.cross.text).toBe(results.absent.text)
})

rtest('0901 実機: 他組織の管理者が組織Aの大会ページを直打ちしても、非会員の通常表示を超えない（部門API を組織B経由で叩いても404）', async ({ browser }) => {
  const { context, page } = await openBare(browser, world.adminB)
  try {
    const probe = (orgId: number, tId: number, tail: string) =>
      page.evaluate(
        async ({ api, o, t, tl }) => {
          const r = await fetch(`${api}/api/v1/organizations/${o}/tournaments/${t}${tl}`, { credentials: 'include' })
          let code: string | null = null
          try { code = (await r.json())?.error?.code ?? null } catch { /* 本文なし */ }
          return { status: r.status, code }
        },
        { api: API, o: orgId, t: tId, tl: tail },
      )
    // 組織B の orgId に組織A の大会IDを当てる（ブラウザのセッション経由）。
    const crossDiv = await probe(world.orgB.numericId, world.tourA, '/divisions')
    const absentDiv = await probe(world.orgB.numericId, 987654, '/divisions')
    const crossPart = await probe(world.orgB.numericId, world.tourA, `/divisions/${world.divA}/participants`)
    const absentPart = await probe(world.orgB.numericId, 987654, `/divisions/${world.divA}/participants`)
    obs('0901.越境API(ブラウザ経由)', { crossDiv, absentDiv, crossPart, absentPart })
    expect(crossDiv).toEqual(absentDiv)
    expect(crossPart).toEqual(absentPart)
  } finally {
    await context.close()
  }
})

// ───────────────────────── 2008 ダッシュボード ─────────────────────────

rtest('2008 実機: チーム/組織ダッシュボードが崩れず、応答に target_team_ids が無い（管理者・会員・部外者）', async ({ browser }) => {
  const seedTeamSlug = 'fc-u-18'
  const seedOrgSlug = sql('SELECT slug FROM organizations WHERE id=71;').trim()
  const cases: Array<{ label: string; actor: Actor; path: string }> = [
    { label: 'team-admin', actor: seedActor('seedAdmin', 'e2e-admin@test.mannschaft.local', 24), path: `/teams/${seedTeamSlug}` },
    { label: 'team-member', actor: seedActor('seedUser', 'e2e-user@test.mannschaft.local', 23), path: `/teams/${seedTeamSlug}` },
    { label: 'team-outsider', actor: seedActor('seedOutsider', 'e2e-outsider@test.mannschaft.local', 90245), path: `/teams/${seedTeamSlug}` },
    { label: 'org-member', actor: seedActor('seedUser', 'e2e-user@test.mannschaft.local', 23), path: `/organizations/${seedOrgSlug}` },
    { label: 'org-outsider', actor: seedActor('seedOutsider', 'e2e-outsider@test.mannschaft.local', 90245), path: `/organizations/${seedOrgSlug}` },
    // 自前の組織A/チームA（お知らせなしの空状態）。管理者・会員。
    { label: 'ownorg-admin', actor: world.adminA, path: `/organizations/${world.orgA.slug}` },
    { label: 'ownteam-member', actor: world.memA, path: `/teams/${world.teamA.slug}` },
  ]
  for (const c of cases) {
    const apiSeen: Array<{ path: string; status: number; hasTargetTeamIds: boolean }> = []
    const errors: string[] = []
    const { context, page } = await openBare(browser, c.actor)
    try {
      page.on('pageerror', (e) => errors.push(String(e.message).slice(0, 120)))
      page.on('response', async (r) => {
        const p = new URL(r.url()).pathname
        if (!p.startsWith('/api/v1/')) return
        const body = await r.text().catch(() => '')
        apiSeen.push({ path: p.replace(/\/(team|organization)\/[^/]+/, '/$1/X').replace(/\d+/g, 'N'), status: r.status(), hasTargetTeamIds: /target_?team_?ids/i.test(body) })
      })
      await page.goto(c.path, { waitUntil: 'commit' })
      await page.waitForFunction(() => {
        const el = document.querySelector('#__nuxt')
        return el !== null && '__vue_app__' in el && el.childElementCount > 0
      }, undefined, { timeout: 180_000 })
      await page.waitForTimeout(12_000)
      const text = await bodyText(page)
      const kind = c.path.startsWith('/teams/') ? 'team' : 'organization'
      const slugOf = c.path.split('/')[2]
      const dashProbe = await page.evaluate(async ({ api, k, slug }) => {
        const r = await fetch(`${api}/api/v1/dashboard/${k}/${slug}`, { credentials: 'include' })
        const t = await r.text()
        return { status: r.status, hasTargetTeamIds: /target_?team_?ids/i.test(t), 告知項目数: (t.match(/"source_type"/g) ?? []).length }
      }, { api: API, k: kind, slug: slugOf })
      obs(`2008.${c.label}`, {
        url: page.url().replace(/^https?:\/\/[^/]+/, '').replace(seedOrgSlug, 'SEEDORG'),
        api総数: apiSeen.length,
        dashboard系API: Array.from(new Set(apiSeen.filter((d) => /dashboard|announce/.test(d.path)).map((d) => `${d.status} ${d.path}`))),
        告知タイトル表示: text.includes(world.noticeTitle),
        target_team_ids含む応答: apiSeen.filter((d) => d.hasTargetTeamIds).map((d) => d.path),
        ブラウザ経由ダッシュボードAPI: dashProbe,
        横スクロールなし: await noHorizontalOverflow(page),
        pageerror: errors,
        本文先頭: text.slice(0, 80),
      })
      await shot(page, `2008-${c.label}`)
      expect(dashProbe.hasTargetTeamIds, `${c.label}: ブラウザ経由ダッシュボードAPI に target_team_ids`).toBe(false)
      expect(apiSeen.filter((d) => d.hasTargetTeamIds), `${c.label}: target_team_ids を含む応答`).toEqual([])
    } finally {
      await context.close()
    }
  }
})

// ───────────────────────── アリシゼーション（住民の目線で自由に触る） ─────────────────────────

/** 画面に出ているエラー表示（トースト・エラー文言）を拾う。 */
async function visibleErrorTexts(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const out: string[] = []
    document.querySelectorAll('[role="alert"], .p-toast-message, .p-message-error, [data-severity="error"]').forEach((e) => {
      const t = (e.textContent ?? '').replace(/\s+/g, ' ').trim()
      if (t) out.push(t.slice(0, 80))
    })
    return out
  })
}

rtest('アリシゼーション 大会: 住民として大会の各画面を巡回する（導線の欠落・エラー・表示崩れ）', async ({ browser }) => {
  test.setTimeout(600_000)
  for (const [label, actor] of [['admin', world.adminA], ['member', world.memA]] as const) {
    const { context, page } = await openBare(browser, actor)
    try {
      const pageErrors: string[] = []
      page.on('pageerror', (e) => pageErrors.push(String(e.message).slice(0, 100)))
      await page.goto(`/organizations/${world.orgA.slug}/tournaments`, { waitUntil: 'commit' })
      await waitHydrated(page)
      await expect(page.getByText(world.tourNameA).first()).toBeVisible({ timeout: 120_000 })
      await shot(page, `explore-tournament-list-${label}`)
      await page.getByText(world.tourNameA).first().click()
      await expect(page.getByText('e2e-261115 部門').first()).toBeVisible({ timeout: 120_000 })
      const hrefs = await page.locator(`a[href*="/tournaments/${world.tourA}/"]`).evaluateAll((els) => Array.from(new Set(els.map((e) => e.getAttribute('href') ?? ''))))
      const results: Array<{ path: string; error表示: string[]; 横スクロールなし: boolean; 先頭見出し: string }> = []
      for (const href of hrefs) {
        await page.goto(href, { waitUntil: 'commit' })
        await waitHydrated(page)
        await page.waitForTimeout(6_000)
        const h = await page.locator('h1, h2').first().innerText().catch(() => '')
        results.push({ path: href.replace(world.orgA.slug, 'ORG_A').replace(String(world.tourA), 'T'), error表示: await visibleErrorTexts(page), 横スクロールなし: await noHorizontalOverflow(page), 先頭見出し: h.slice(0, 30) })
      }
      obs(`EXPLORE.大会巡回.${label}`, { 遷移先数: hrefs.length, results, pageerror: pageErrors })
    } finally {
      await context.close()
    }
  }
})

rtest('アリシゼーション 確認通知: 住民（管理者）が設定画面を触り、履歴の行を開き、キャンセルする', async ({ browser }) => {
  test.setTimeout(400_000)
  const { context, page } = await openBare(browser, world.adminA)
  try {
    await page.goto(`/organizations/${world.orgA.slug}/circulation`, { waitUntil: 'commit' })
    await waitHydrated(page)
    const guideLink = page.locator('a[href$="/settings/confirmable-notifications"]').first()
    await expect(guideLink).toBeVisible({ timeout: 90_000 })
    obs('EXPLORE.回覧板→確認通知の導線', { リンク表示: true })
    await shot(page, 'explore-circulation-guide')
    await guideLink.click()
    await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(world.titleA1)).toBeVisible({ timeout: 60_000 })
    obs('EXPLORE.回覧板から設定へ（アプリ内遷移）', { A1表示: true, A2表示: await page.getByText(world.titleA2).isVisible() })
    // 履歴の行を開いて受信者パネルを見る
    await page.getByText(world.titleA1).first().click()
    await page.waitForTimeout(4_000)
    const text = await bodyText(page)
    obs('EXPLORE.履歴の行クリック', { 受信者表示らしき文言: /受信者|確認済|未確認/.test(text), error表示: await visibleErrorTexts(page) })
    await shot(page, 'explore-confirmable-row-open')
  } finally {
    await context.close()
  }
})

rtest('アリシゼーション ダッシュボード: 住民として組織告知を探す（チーム会員・組織管理者）', async ({ browser }) => {
  test.setTimeout(400_000)
  for (const [label, actor, path] of [['teamA会員', world.memA, `/teams/${world.teamA.slug}`], ['orgA管理者', world.adminA, `/organizations/${world.orgA.slug}`]] as const) {
    const { context, page } = await openBare(browser, actor)
    try {
      await page.goto(path, { waitUntil: 'commit' })
      await waitHydrated(page)
      await page.waitForTimeout(8_000)
      const visibleAtLanding = (await bodyText(page)).includes(world.noticeTitle)
      let afterExpand = visibleAtLanding
      const clicked: string[] = []
      for (const name of ['連絡・共有', 'お知らせ', '連絡', 'ニュース']) {
        if (afterExpand) break
        const target = page.getByText(name, { exact: false }).first()
        if (await target.isVisible()) {
          await target.click({ timeout: 10_000 }).catch(() => undefined)
          clicked.push(name)
          await page.waitForTimeout(4_000)
          afterExpand = (await bodyText(page)).includes(world.noticeTitle)
        }
      }
      obs(`EXPLORE.告知の見つけやすさ.${label}`, { 着地時に告知タイトル: visibleAtLanding, 展開後に告知タイトル: afterExpand, 触った見出し: clicked, error表示: await visibleErrorTexts(page), 横スクロールなし: await noHorizontalOverflow(page) })
      await shot(page, `explore-dashboard-${label}`)
    } finally {
      await context.close()
    }
  }
})

// ───────────────────────── 後始末 ─────────────────────────

test('後始末: 作成した大会・確認通知（自前データ）を片付ける。組織・チームは globalTeardown が削除', async () => {
  for (const [admin, org, tId] of [[world.adminA, world.orgA, world.tourA], [world.adminB, world.orgB, world.tourB]] as const) {
    const r = await apiAs(admin, 'DELETE', `/api/v1/organizations/${org.numericId}/tournaments/${tId}`)
    obs('後始末.大会', { org: org.slug, status: r.status })
  }
  obs('後始末.確認通知', '組織削除時に論理/カスケード処理。残存は後続で確認')
  expect(problems, `観測中の失敗: ${problems.join(' || ')}`).toEqual([])
})
