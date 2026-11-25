/**
 * CMP-260930-0228 実機E2E: 大会エントリーモーダルの「背番号・ポジション入力 → エントリー保存 →
 * テンプレート保存 → 別参加枠へ適用」と、保存不可条件（名前空・エントリー0件・上限5件）、ロール横断。
 *
 * APIはログイン・前提データ（組織/チーム/大会/部門/参加枠/招待参加）・上限用テンプレートの下準備・後始末だけに使う。
 * 対象操作（入力・保存・適用）は画面のクリック・入力で行い、永続化はAPIで読み戻して確認する。応答stubなし。
 * 共有seedアカウントの設定は変更しない（e2e-user はチーム招待で一時参加させ、後始末で組織/チームごと撤去する）。
 */
import { test, expect, request, type APIRequestContext, type Page, type Locator } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import ja from '../../../../app/locales/ja/tournament.json' with { type: 'json' }

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial', timeout: 120_000 })

const API = process.env.API_BASE_URL ?? 'http://localhost:8080'
const BASE = process.env.BASE_URL ?? 'http://localhost:3001'
const password = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
const adminEmail = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const memberEmail = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const outsiderEmail = process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local'
const RUN_TAG = `R${Date.now().toString(36)}`
const prefix = `CMP0228-${RUN_TAG}`
const text = ja.tournament
const tpl = text.entry.template

let api: APIRequestContext
let memberApi: APIRequestContext
let outsiderApi: APIRequestContext
let adminId: number
let memberId: number
let orgId: number
let orgSlug: string
let teamId: number
let teamSlug: string
let tournamentId: number
let divA: number
let divB: number
let partA: number
let partB: number
let inviteId: number | undefined
let memberJoined = false
let affiliationActive = false
const templateIds = new Set<string>()

const tournamentApi = () => `/api/v1/organizations/${orgId}/tournaments/${tournamentId}`
const entryApi = (div: number, part: number) =>
  `${tournamentApi()}/divisions/${div}/participants/${part}/entry-members`
const templateApi = () => `/api/v1/organizations/${orgId}/teams/${teamId}/entry-templates`
const detailPath = () => `/organizations/${orgSlug}/tournaments/${tournamentId}`

async function apiLogin(email: string): Promise<APIRequestContext> {
  for (let i = 0; i < 4; i++) {
    const ctx = await request.newContext({ baseURL: API })
    const res = await ctx.post('/api/v1/auth/login', { data: { email, password } })
    if (res.status() === 200) return ctx
    await ctx.dispose()
    if (res.status() !== 429) throw new Error(`login ${email}: ${res.status()}`)
    await new Promise((r) => setTimeout(r, 15_000))
  }
  throw new Error(`login ${email}: 429 が解消しない`)
}

async function meId(ctx: APIRequestContext): Promise<number> {
  const res = await ctx.get('/api/v1/users/me')
  expect(res.status()).toBe(200)
  return (await res.json()).data.id as number
}

test.beforeAll(async () => {
  api = await apiLogin(adminEmail)
  memberApi = await apiLogin(memberEmail)
  outsiderApi = await apiLogin(outsiderEmail)
  adminId = await meId(api)
  memberId = await meId(memberApi)
  const ts = Date.now()
  const org = await api.post('/api/v1/organizations', {
    data: { name: `${prefix}-org`, slug: `cmp0228-o-${ts}`, orgType: 'OTHER', visibility: 'PUBLIC' },
  })
  expect(org.status()).toBe(201)
  const orgData = (await org.json()).data as { numericId: number; slug: string }
  orgId = orgData.numericId
  orgSlug = orgData.slug
  const team = await api.post('/api/v1/teams', {
    data: { name: `${prefix}-team`, slug: `cmp0228-t-${ts}`, template: 'SPORTS', visibility: 'PUBLIC' },
  })
  expect(team.status()).toBe(201)
  const teamData = (await team.json()).data as { numericId: number; slug: string }
  teamId = teamData.numericId
  teamSlug = teamData.slug
  const invite = await api.post(`/api/v1/organizations/${orgSlug}/team-invites`, { data: { teamSlug } })
  expect(invite.status()).toBe(201)
  const membershipId = (await invite.json()).data.id as number
  const accept = await api.post(`/api/v1/teams/${teamSlug}/org-invites/${membershipId}/accept`)
  expect(accept.status()).toBe(200)
  affiliationActive = true
  // 一般メンバー: チーム招待トークン(roleId=4 MEMBER)で一時参加
  const token = await api.post(`/api/v1/teams/${teamSlug}/invite-tokens`, {
    data: { roleId: 4, expiresIn: '1d', maxUses: 1 },
  })
  expect(token.status()).toBe(201)
  const tokenData = (await token.json()).data as { id: number; token: string }
  inviteId = tokenData.id
  const joined = await memberApi.post(`/api/v1/invite/${tokenData.token}/join`)
  expect(joined.status(), 'e2e-user のチーム一時参加').toBeLessThan(300)
  memberJoined = true

  const tournament = await api.post(`/api/v1/organizations/${orgId}/tournaments`, {
    data: { name: prefix, format: 'LEAGUE', visibility: 'PUBLIC', sport: 'SOCCER' },
  })
  expect(tournament.status()).toBe(201)
  tournamentId = (await tournament.json()).data.id as number
  const mk = async (name: string) => {
    const d = await api.post(`${tournamentApi()}/divisions`, {
      data: { name: `${prefix}-${name}`, minEntryCount: 1, maxEntryCount: 10 },
    })
    expect(d.status()).toBe(201)
    const id = (await d.json()).data.id as number
    const p = await api.post(`${tournamentApi()}/divisions/${id}/participants`, { data: { teamId } })
    expect(p.status()).toBe(201)
    return [id, (await p.json()).data.id as number] as const
  }
  ;[divA, partA] = await mk('A')
  ;[divB, partB] = await mk('B')
  expect((await api.patch(`${tournamentApi()}/status`, { data: { status: 'OPEN' } })).status()).toBe(200)
  console.log('CMP0228 fixture', JSON.stringify({ prefix, orgId, orgSlug, teamId, teamSlug, tournamentId, divA, partA, divB, partB }))
})

test.afterAll(async () => {
  const failures: string[] = []
  const clean = async (name: string, run: () => Promise<void>) => {
    try {
      await run()
    } catch (e) {
      failures.push(`${name}: ${String(e)}`)
    }
  }
  try {
    if (api && teamId) {
      // 画面操作で作ったテンプレートも含め、チームの全テンプレート（本spec作成分のみ）を撤去
      const list = await api.get(templateApi())
      const rows = list.status() === 200 ? ((await list.json()).data as Array<{ id: string; name: string }>) : []
      for (const r of rows) {
        if (!r.name.startsWith(prefix) && !templateIds.has(r.id)) continue
        await clean(`template ${r.id}`, async () => {
          expect((await api.delete(`${templateApi()}/${r.id}`)).status()).toBe(204)
        })
      }
      await clean('template-remaining', async () => {
        const after = await api.get(templateApi())
        expect(((await after.json()).data as unknown[]).length).toBe(0)
      })
    }
    if (api && tournamentId)
      await clean('tournament', async () => {
        expect((await api.delete(tournamentApi())).status()).toBe(204)
        expect((await api.get(tournamentApi())).status()).toBe(404)
      })
    if (api && memberJoined)
      await clean('member-leave', async () => {
        const r = await api.delete(`/api/v1/teams/${teamSlug}/members/${memberId}`)
        console.log('CMP0228 member removal status', r.status())
      })
    if (api && inviteId)
      await clean('invite', async () => {
        await api.delete(`/api/v1/teams/${teamSlug}/invite-tokens/${inviteId}`)
      })
    if (api && affiliationActive)
      await clean('affiliation', async () => {
        expect((await api.delete(`/api/v1/teams/${teamSlug}/organizations/${orgSlug}`)).status()).toBe(204)
      })
    if (api && teamSlug)
      await clean('team', async () => {
        expect((await api.delete(`/api/v1/teams/${teamSlug}`)).status()).toBe(204)
        expect((await api.get(`/api/v1/teams/${teamSlug}`)).status()).toBe(404)
      })
    if (api && orgSlug)
      await clean('organization', async () => {
        expect((await api.delete(`/api/v1/organizations/${orgSlug}`)).status()).toBe(204)
        expect((await api.get(`/api/v1/organizations/${orgSlug}`)).status()).toBe(404)
      })
  } finally {
    await api?.dispose()
    await memberApi?.dispose()
    await outsiderApi?.dispose()
  }
  expect(failures, '所有fixtureの後始末').toEqual([])
})

async function openDetail(page: Page, email: string) {
  await loginViaApi(page, { email, password }, { apiBaseUrl: API, deferNavigation: true })
  await page.context().addCookies([{ name: 'i18n_locale', value: 'ja', url: BASE }])
  await page.goto(`${BASE}${detailPath()}`, { waitUntil: 'domcontentloaded' })
  await expect(page.getByTestId('tournament-detail-title')).toHaveText(prefix, { timeout: 60_000 })
}

async function openEntryModal(page: Page, div: number): Promise<Locator> {
  await page.getByTestId(`tournament-division-tab-${div}`).click()
  // 初回管理者ダイアログ等が覆う場合に備え、押せるまで待つ
  await page.getByRole('button', { name: text.detail.manageEntry, exact: true }).first().click()
  const dialog = page.getByRole('dialog', { name: text.entry.title })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('button', { name: text.entry.saveMembers, exact: true })).toBeVisible()
  return dialog
}

function memberRow(page: Page, dialog: Locator, userId: number) {
  return dialog.locator('div.rounded.border').filter({ has: page.locator(`#member-${userId}`) })
}

async function closeDialog(dialog: Locator) {
  await dialog.getByRole('button', { name: '閉じる', exact: true }).first().click()
  await expect(dialog).toBeHidden()
}

async function saveTemplateViaUi(page: Page, dialog: Locator, name: string, description: string) {
  await dialog.getByPlaceholder(tpl.name, { exact: true }).fill(name)
  await dialog.getByPlaceholder(tpl.description, { exact: true }).fill(description)
  const created = page.waitForResponse(
    (r) => r.url().endsWith(`/entry-templates`) && r.request().method() === 'POST',
  )
  await dialog.getByRole('button', { name: tpl.create, exact: true }).click()
  const res = await created
  expect(res.status()).toBe(201)
  const id = (await res.json()).data.id as string
  templateIds.add(id)
  await expect(page.getByText(tpl.createSuccess, { exact: true }).first()).toBeVisible()
  return id
}

const listTemplates = async (ctx: APIRequestContext) =>
  ((await (await ctx.get(templateApi())).json()).data as Array<{ id: string; name: string; description: string | null; memberCount: number }>)

test('F1 管理者: 背番号・ポジション入力→エントリー保存→テンプレート保存→一覧表示', async ({ page }) => {
  await openDetail(page, adminEmail)
  const dialog = await openEntryModal(page, divA)
  // エントリー0件ではテンプレート保存不可（名前を入れても押せない）
  await dialog.getByPlaceholder(tpl.name, { exact: true }).fill(`${prefix}-empty`)
  await expect(dialog.getByRole('button', { name: tpl.create, exact: true })).toBeDisabled()
  await dialog.getByPlaceholder(tpl.name, { exact: true }).fill('')

  // 背番号・ポジション入力 → エントリー保存（管理者本人＋一般メンバー）
  const adminRow = memberRow(page, dialog, adminId)
  await adminRow.locator(`label[for="member-${adminId}"]`).click()
  await adminRow.getByPlaceholder(text.entry.jersey, { exact: true }).fill('10')
  await adminRow.getByPlaceholder(text.entry.position, { exact: true }).fill('FW')
  const memRow = memberRow(page, dialog, memberId)
  await memRow.locator(`label[for="member-${memberId}"]`).click()
  await memRow.getByPlaceholder(text.entry.jersey, { exact: true }).fill('7')
  await memRow.getByPlaceholder(text.entry.position, { exact: true }).fill('MF')
  const put = page.waitForResponse((r) => r.url().endsWith(entryApi(divA, partA).replace(`/organizations/${orgId}/`, `/organizations/${orgSlug}/`)) && r.request().method() === 'PUT')
  await dialog.getByRole('button', { name: text.entry.saveMembers, exact: true }).last().click()
  expect((await put).status()).toBe(200)
  await expect(page.getByText(text.entry.saveSuccess, { exact: true }).first()).toBeVisible()
  const saved = (await (await api.get(entryApi(divA, partA))).json()).data.entryMembers as Array<{ userId: number; jerseyNumber: number; position: string }>
  expect(saved.map((m) => [m.userId, m.jerseyNumber, m.position]).sort()).toEqual(
    [[adminId, 10, 'FW'], [memberId, 7, 'MF']].sort(),
  )

  // エントリー保存済みでも名前が空では保存不可
  await expect(dialog.getByPlaceholder(tpl.name, { exact: true })).toHaveValue('')
  await expect(dialog.getByRole('button', { name: tpl.create, exact: true })).toBeDisabled()
  // 保存
  const id = await saveTemplateViaUi(page, dialog, `${prefix}-T1`, '説明テキスト')
  const rows = await listTemplates(api)
  const row = rows.find((r) => r.id === id)!
  expect(row.name).toBe(`${prefix}-T1`)
  expect(row.description).toBe('説明テキスト')
  expect(row.memberCount).toBe(2)
  // 入力欄は保存後クリアされ、一覧(セレクト)に出る
  await expect(dialog.getByPlaceholder(tpl.name, { exact: true })).toHaveValue('')
  await dialog.getByRole('combobox').click()
  await expect(page.getByRole('option', { name: `${prefix}-T1`, exact: true })).toBeVisible()
  await page.screenshot({ path: 'test-results/cmp0228-f1.png' })
})

test('F2 管理者: 別参加枠(部門B)でテンプレートを適用→背番号・ポジションが反映', async ({ page }) => {
  await openDetail(page, adminEmail)
  const dialog = await openEntryModal(page, divB)
  await expect(dialog.getByText(tpl.noTemplates)).toHaveCount(0)
  await dialog.getByRole('combobox').click()
  await page.getByRole('option', { name: `${prefix}-T1`, exact: true }).click()
  const post = page.waitForResponse((r) => r.url().endsWith('/apply-template') && r.request().method() === 'POST')
  await dialog.getByRole('button', { name: tpl.apply, exact: true }).click()
  expect((await post).status()).toBe(200)
  await expect(page.getByText('2名を追加しました', { exact: false }).first()).toBeVisible()
  const table = dialog.locator('table')
  await expect(table.getByRole('row', { name: /10\s*FW/ })).toBeVisible()
  await expect(table.getByRole('row', { name: /7\s*MF/ })).toBeVisible()
  const saved = (await (await api.get(entryApi(divB, partB))).json()).data.entryMembers as Array<{ userId: number; jerseyNumber: number; position: string }>
  expect(saved.map((m) => [m.userId, m.jerseyNumber, m.position]).sort()).toEqual(
    [[adminId, 10, 'FW'], [memberId, 7, 'MF']].sort(),
  )
})

test('F3 管理者: 同データ保持で更新（登録済みを残したまま位置だけ変更→再保存で背番号保持）', async ({ page }) => {
  await openDetail(page, adminEmail)
  const dialog = await openEntryModal(page, divA)
  const row = memberRow(page, dialog, adminId)
  await expect(row.getByText(text.entry.alreadyEntered)).toBeVisible()
  await expect(row.getByPlaceholder(text.entry.jersey, { exact: true })).toHaveValue('10')
  await row.getByPlaceholder(text.entry.position, { exact: true }).fill('GK')
  const put = page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().includes('/entry-members'))
  await dialog.getByRole('button', { name: text.entry.saveMembers, exact: true }).last().click()
  expect((await put).status()).toBe(200)
  const saved = (await (await api.get(entryApi(divA, partA))).json()).data.entryMembers as Array<{ userId: number; jerseyNumber: number; position: string }>
  const a = saved.find((m) => m.userId === adminId)!
  expect([a.jerseyNumber, a.position]).toEqual([10, 'GK'])
  expect(saved.find((m) => m.userId === memberId)!.jerseyNumber).toBe(7)
  // 閉じて開き直しても保持
  await closeDialog(dialog)
  const again = await openEntryModal(page, divA)
  await expect(memberRow(page, again, adminId).getByPlaceholder(text.entry.position, { exact: true })).toHaveValue('GK')
  // 再保存したテンプレートは追加で作成でき、元のテンプレートは壊れない
  const id = await saveTemplateViaUi(page, again, `${prefix}-T2`, '')
  const rows = await listTemplates(api)
  expect(rows.find((r) => r.id === id)!.description).toBeNull()
  expect(rows.find((r) => r.name === `${prefix}-T1`)!.memberCount).toBe(2)
})

test('F4 上限5件: 4件目まで保存可・5件目で画面が保存不可、BEは6件目をTOUR_025で拒否', async ({ page }) => {
  // 現在2件(T1,T2)。API下準備で4件にし、画面から5件目を保存する
  for (const n of [3, 4]) {
    const r = await api.post(templateApi(), { data: { name: `${prefix}-T${n}`, members: [] } })
    expect(r.status()).toBe(201)
    templateIds.add((await r.json()).data.id as string)
  }
  await openDetail(page, adminEmail)
  const dialog = await openEntryModal(page, divA)
  await expect(dialog.getByText(tpl.maxCount)).toHaveCount(0)
  await saveTemplateViaUi(page, dialog, `${prefix}-T5`, '5件目')
  expect((await listTemplates(api)).filter((r) => r.name.startsWith(prefix))).toHaveLength(5)
  // 5件で保存不可・上限文言
  await dialog.getByPlaceholder(tpl.name, { exact: true }).fill(`${prefix}-T6`)
  await expect(dialog.getByText(tpl.maxCount)).toBeVisible()
  await expect(dialog.getByRole('button', { name: tpl.create, exact: true })).toBeDisabled()
  await page.screenshot({ path: 'test-results/cmp0228-f4-limit.png' })
  // BE: 6件目は 422 TOUR_025
  const sixth = await api.post(templateApi(), { data: { name: `${prefix}-T6`, members: [] } })
  expect(sixth.status()).toBe(422)
  expect((await sixth.json()).error.code).toBe('TOUR_025')
  expect(await listTemplates(api)).toHaveLength(5)
})

test('F5 上限の競合: 画面を開いた後に他経路で5件に達した場合、BEが拒否し文言を表示（テンプレートは増えない）', async ({ page }) => {
  // 後始末のうえ4件に戻し、画面を開いてから5件目をAPIで作る
  const rows = await listTemplates(api)
  const t5 = rows.find((r) => r.name === `${prefix}-T5`)!
  expect((await api.delete(`${templateApi()}/${t5.id}`)).status()).toBe(204)
  await openDetail(page, adminEmail)
  const dialog = await openEntryModal(page, divA)
  const stale = await api.post(templateApi(), { data: { name: `${prefix}-T5b`, members: [] } })
  expect(stale.status()).toBe(201)
  templateIds.add((await stale.json()).data.id as string)
  await dialog.getByPlaceholder(tpl.name, { exact: true }).fill(`${prefix}-T6`)
  const post = page.waitForResponse((r) => r.url().endsWith('/entry-templates') && r.request().method() === 'POST')
  await dialog.getByRole('button', { name: tpl.create, exact: true }).click()
  const res = await post
  expect(res.status()).toBe(422)
  expect((await res.json()).error.code).toBe('TOUR_025')
  await expect(page.getByText(text.entry.error.TOUR_025, { exact: true }).first()).toBeVisible()
  await expect(page.getByText(tpl.createSuccess, { exact: true })).toHaveCount(0)
  expect(await listTemplates(api)).toHaveLength(5)
})

test('R1 一般メンバー(チームMEMBER): エントリー管理導線もテンプレート保存UIも出ない／作成・適用APIは403', async ({ page }) => {
  await openDetail(page, memberEmail)
  await page.getByTestId(`tournament-division-tab-${divA}`).click()
  await expect(page.getByText(`${prefix}-team`).first()).toBeVisible()
  await expect(page.getByRole('button', { name: text.detail.manageEntry, exact: true })).toHaveCount(0)
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByText(tpl.create)).toHaveCount(0)
  await page.screenshot({ path: 'test-results/cmp0228-r1-member.png' })
  const get = await memberApi.get(templateApi())
  const post = await memberApi.post(templateApi(), { data: { name: `${prefix}-evil`, members: [] } })
  const apply = await memberApi.post(`${entryApi(divB, partB)}/apply-template`, { data: { templateId: (await listTemplates(api))[0]!.id, overwriteExisting: true } })
  console.log('CMP0228 R1 member api', get.status(), post.status(), apply.status())
  // 一覧参照はチームメンバーに許可（BE checkTemplateViewable の設計）。作成・適用は拒否される
  expect(get.status()).toBe(200)
  expect([post.status(), apply.status()]).toEqual([403, 403])
  expect((await listTemplates(api)).some((r) => r.name === `${prefix}-evil`)).toBe(false)
})

test('R2 他組織・非所属ユーザー: 導線なし／API 403・404／テンプレート一覧に出ない', async ({ page }) => {
  await openDetail(page, outsiderEmail)
  await page.getByTestId(`tournament-division-tab-${divA}`).click()
  await expect(page.getByRole('button', { name: text.detail.manageEntry, exact: true })).toHaveCount(0)
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.screenshot({ path: 'test-results/cmp0228-r2-outsider.png' })
  const before = (await listTemplates(api)).length
  const get = await outsiderApi.get(templateApi())
  const post = await outsiderApi.post(templateApi(), { data: { name: `${prefix}-evil`, members: [] } })
  const del = await outsiderApi.delete(`${templateApi()}/${(await listTemplates(api))[0]!.id}`)
  const put = await outsiderApi.put(entryApi(divB, partB), { data: { members: [] } })
  const apply = await outsiderApi.post(`${entryApi(divB, partB)}/apply-template`, { data: { templateId: (await listTemplates(api))[0]!.id, overwriteExisting: true } })
  console.log('CMP0228 R2 outsider api', get.status(), post.status(), del.status(), put.status(), apply.status())
  for (const s of [get.status(), post.status(), del.status(), put.status(), apply.status()]) expect([403, 404]).toContain(s)
  expect((await listTemplates(api)).length).toBe(before)
})

test('R3 未認証の直URLはログインへ遷移し導線なし', async ({ page }) => {
  await page.goto(`${BASE}${detailPath()}`, { waitUntil: 'domcontentloaded' })
  await expect(page).toHaveURL(/\/login(?:\?|$)/)
  await expect(page.getByRole('button', { name: text.detail.manageEntry, exact: true })).toHaveCount(0)
})
