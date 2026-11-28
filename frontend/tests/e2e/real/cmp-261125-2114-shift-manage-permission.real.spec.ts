import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { test } from '@playwright/test'
import type { Browser, BrowserContext, Page } from '@playwright/test'
import {
  FE, apiAs, expect, gotoAndHydrate, openAs, responseOf, type Actor,
} from './helpers/n1p1-world'
import { buildWorld, teardownWorld, createdSchedules, type World } from './helpers/cmp261125-2114-world'

/**
 * 戦役 CMP-261125-2114「シフト管理を ADMIN と MANAGE_SHIFTS 付き DEPUTY_ADMIN だけに許す」（PR #4924）の実機E2E。
 *
 * 仕様:
 *  - シフト管理（作成・更新・削除・公開・割当・承認・自動割当・時給設定・他人の勤務制約）は、在籍中のチーム ADMIN と、
 *    MANAGE_SHIFTS を持つ在籍中の DEPUTY_ADMIN だけ。MANAGE_SHIFTS は権限グループで管理者が付与する。
 *  - チームロールの無い SYSTEM_ADMIN は拒否。
 *  - MANAGE_SHIFTS の無い DEPUTY_ADMIN は、チームのシフト設定の切替 deputyShiftViewEnabled（未公開・希望一覧など）と
 *    deputyHourlyRateViewEnabled（他人の時給。切替の変更は ADMIN だけ）が ON のときだけ閲覧できる。
 *  - 越境は 404、同チームの権限不足は 403。
 *
 * 入口: /teams/{slug}/shifts（シフト管理）・/shift/{id}（詳細・edit・requests・work-constraints）・
 *       /teams/{slug}/shifts/{scheduleId}/board・/change-requests・/teams/{slug}/settings/shift・/settings/hourly-rate・
 *       /teams/{slug}/permission-groups と /members（権限グループの付与・剥奪）。
 * API を使うのは、ログイン・前提データ（利用者・チーム・所属・権限グループ・シフト表・枠）の作成・後始末と、
 * 画面から到達できない攻撃（権限の無い者が直接叩く）の裏取りだけ。対象操作そのものは画面のクリック・入力で行う。
 *
 * ロール: ADMIN / DEPUTY＋MANAGE_SHIFTS / DEPUTY（切替 OFF）/ DEPUTY（切替 ON）/ MEMBER / 他チーム ADMIN / チームロール無し SYSTEM_ADMIN
 *
 * 実行（cd frontend。BE 8081・FE 3001。FE は NUXT_PUBLIC_API_BASE=http://localhost:8081 NUXT_INTERNAL_API_BASE=http://localhost:8081 で起動）:
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 \
 *     npx playwright test -c playwright-cmp261125-2114.config.ts cmp-261125-2114-shift-manage-permission.real.spec.ts
 * 後始末: 作成したシフト表・ポジション・チームと SYSTEM_ADMIN ロール行を afterAll で消し、残件 0 を SQL（読み取りのみ）で確かめる。
 * 利用者はテスト専用ドメインのまま残る（削除 API が無い。他の real spec と同じ）。
 */
const EVID_DIR = process.env.EVID_DIR || path.resolve('..', 'docs', 'evidence', 'CMP-261125-2114')

let W: World
const sessions = new Map<string, { context: BrowserContext; page: Page }>()

async function sessionOf(browser: Browser, actor: Actor): Promise<Page> {
  const hit = sessions.get(actor.email)
  if (hit) return hit.page
  const { context, page } = await openAs(browser, actor)
  sessions.set(actor.email, { context, page })
  return page
}

async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(EVID_DIR, `${name}.png`) })
}

const PERM_RE = /\/api\/v1\/teams\/[^/]+\/me\/permissions$/

/** 権限取得の応答を待ちつつ画面を開く（認可判定が終わった証拠を受け取ってから表示を確かめる）。 */
async function visit(page: Page, p: string, opts: { perm?: boolean } = { perm: true }): Promise<void> {
  const permP = opts.perm === false ? null : responseOf(page, PERM_RE)
  await gotoAndHydrate(page, p)
  if (permP) await permP
  await page.waitForTimeout(1200)
}

const slotUrl = () => `/teams/${W.teamA.slug}/shifts`
const settingsUrl = () => `/teams/${W.teamA.slug}/settings/shift`
const hourlyUrl = () => `/teams/${W.teamA.slug}/settings/hourly-rate`
const boardUrl = () => `/teams/${W.teamA.slug}/shifts/${W.scheduleA}/board`
const crUrl = () => `/teams/${W.teamA.slug}/shifts/${W.scheduleA}/change-requests`
const detail = (suffix = '') => `/shift/${W.scheduleA}${suffix}`

const bodyText = (page: Page) => page.locator('body').innerText()

async function noHorizontalScroll(page: Page, label: string) {
  const [sw, iw] = await page.evaluate(() => [document.documentElement.scrollWidth, window.innerWidth])
  expect(sw, `${label}: 横スクロールが出ていない（scrollWidth=${sw} innerWidth=${iw}）`).toBeLessThanOrEqual(iw + 1)
}

/** 設定画面で切替を操作して保存する（管理者が画面から行う）。 */
async function setToggleViaUi(page: Page, testId: string, on: boolean) {
  await visit(page, settingsUrl())
  const toggle = page.getByTestId(testId)
  await expect(toggle).toBeVisible()
  const input = toggle.locator('input[type="checkbox"]')
  if ((await input.isChecked()) !== on) await toggle.click()
  const saved = page.waitForResponse((r) => r.request().method() === 'PATCH' && new URL(r.url()).pathname === `/api/v1/teams/${W.teamA.slug}/shift-settings`)
  await page.getByTestId('reminder-settings-save-btn').click()
  expect((await saved).status(), '切替の保存は 200').toBe(200)
  const state = await apiAs(W.admin, 'GET', `/api/v1/teams/${W.teamA.slug}/shift-settings`)
  const key = testId === 'deputy-shift-view-toggle' ? 'deputyShiftViewEnabled' : 'deputyHourlyRateViewEnabled'
  expect(state.body[key], `${key} が永続化された`).toBe(on)
}

/** 管理者の形で見えるべき画面要素（シフト管理ページ）。 */
async function expectManagerShiftsPage(page: Page, who: string, notice: 'admin' | 'none') {
  await expect(page.getByRole('heading', { name: 'シフト管理' })).toBeVisible()
  await expect(page.getByRole('button', { name: '新規作成' }), `${who}: 新規作成が出る`).toBeVisible()
  await expect(page.getByRole('tab', { name: 'ポジション管理' }), `${who}: ポジション管理タブ`).toBeVisible()
  await expect(page.getByTestId('shift-manage-notice-deputy'), `${who}: 権限なし案内は出ない`).toHaveCount(0)
  if (notice === 'admin') {
    await expect(page.getByTestId('shift-manage-notice-admin')).toBeVisible()
    await expect(page.getByTestId('shift-manage-notice-link')).toBeVisible()
  } else {
    await expect(page.getByTestId('shift-manage-notice-admin'), `${who}: 管理者告知は管理者だけ`).toHaveCount(0)
  }
}

test.describe.configure({ mode: 'serial' })

test.beforeAll(async () => {
  test.setTimeout(900_000)
  mkdirSync(EVID_DIR, { recursive: true })
  W = await buildWorld()
})

test.afterAll(async () => {
  test.setTimeout(300_000)
  for (const { context } of sessions.values()) {
    try {
      await context.close()
    } catch (error) {
      console.log(`[cmp-261125-2114] context close 失敗: ${String(error)}`)
    }
  }
  const r = await teardownWorld()
  console.log(`[cmp-261125-2114] 後始末 残件: チーム=${r.teams} シフト表=${r.schedules} SYSTEM_ADMIN行=${r.sysAdminRows} 失敗=${r.failures.length}`)
  expect(r.failures, `削除失敗: ${r.failures.join(' / ')}`).toEqual([])
  expect(r.teams, '残ったチーム').toBe(0)
  expect(r.schedules, '残ったシフト表').toBe(0)
  expect(r.sysAdminRows, '残した SYSTEM_ADMIN ロール行').toBe(0)
})

// ---- 正の視点: ADMIN ------------------------------------------------------------------------------

test('ADMIN: シフト管理の全画面が操作できる。告知（権限グループへの導線）が出て、画面からシフト表を作成・割当・時給設定・削除できる', async ({ browser }) => {
  test.setTimeout(600_000)
  const page = await sessionOf(browser, W.admin)

  // 1. シフト管理ページ（一覧・作成・告知・ポジション）
  await visit(page, slotUrl())
  await expectManagerShiftsPage(page, 'ADMIN', 'admin')
  await shot(page, 'admin-shifts')

  // 告知の導線: 権限グループ画面へ遷移できる
  await page.getByTestId('shift-manage-notice-link').click()
  await expect(page).toHaveURL(new RegExp(`/teams/${W.teamA.slug}/permission-groups$`), { timeout: 60_000 })
  await expect(page.getByTestId('permission-group-create')).toBeVisible({ timeout: 60_000 })
  await shot(page, 'admin-permission-groups-from-notice')

  // 2. 画面からシフト表を作成 → 一覧に出る → 詳細から削除
  await visit(page, slotUrl())
  await page.getByRole('button', { name: '新規作成' }).click()
  const title = `2114画面作成${Date.now().toString(36)}`
  const dialog = page.getByRole('dialog', { name: 'シフト表を作成' })
  await dialog.getByPlaceholder('例: 2026年4月第1週').fill(title)
  const dates = dialog.locator('input.p-datepicker-input')
  await dates.nth(0).fill('2027/03/01')
  await dates.nth(0).press('Escape')
  await dates.nth(1).fill('2027/03/07')
  await dates.nth(1).press('Escape')
  const created = page.waitForResponse((r) => r.request().method() === 'POST' && /\/api\/v1\/shifts\/schedules$/.test(new URL(r.url()).pathname))
  await dialog.getByRole('button', { name: '作成' }).click()
  const createdRes = await created
  expect(createdRes.status(), '画面からのシフト表作成は 201').toBe(201)
  const createdId = (await createdRes.json()).data.id as number
  createdSchedules.push({ id: createdId, owner: W.admin })
  await expect(page.getByText(title)).toBeVisible({ timeout: 30_000 })
  await shot(page, 'admin-shifts-created')

  await visit(page, `/shift/${createdId}`)
  await expect(page.getByTestId('shift-detail-actions')).toBeVisible()
  await page.getByTestId('shift-schedule-delete').click()
  const del = page.waitForResponse((r) => r.request().method() === 'DELETE' && new URL(r.url()).pathname === `/api/v1/shifts/schedules/${createdId}`)
  await page.locator('.p-confirmdialog').getByRole('button', { name: '削除', exact: true }).click()
  expect((await del).status(), '画面からの削除は成功').toBeLessThan(300)
  await expect.poll(async () => (await apiAs(W.admin, 'GET', `/api/v1/shifts/schedules/${createdId}`)).status, { timeout: 30_000 }).toBe(404)

  // 3. シフト詳細・編集・希望一覧・勤務制約・ボード・変更依頼
  await visit(page, detail())
  await expect(page.getByTestId('shift-detail-actions')).toBeVisible()
  await expect(page.getByTestId('shift-manage-notice-deputy')).toHaveCount(0)
  for (const tab of ['edit', 'requests', 'constraints', 'board', 'change-requests']) {
    await expect(page.getByTestId(`shift-tab-${tab}`), `ADMIN の詳細タブ ${tab}`).toBeVisible()
  }
  await visit(page, detail('/edit'))
  await expect(page.getByRole('button', { name: 'スロットを作成' })).toBeVisible()
  await visit(page, detail('/requests'))
  await expect(page).toHaveURL(new RegExp(`/shift/${W.scheduleA}/requests$`))
  await visit(page, detail('/work-constraints'))
  await expect(page).toHaveURL(new RegExp(`/shift/${W.scheduleA}/work-constraints$`))
  await shot(page, 'admin-work-constraints')

  // 4. ボード: 画面から割当（メンバーを追加ダイアログ）。自動割当ボタンも出る
  await visit(page, boardUrl())
  await expect(page.getByText('シフトボード')).toBeVisible()
  await expect(page.getByTestId('auto-assign-button')).toBeVisible()
  await page.locator('tbody tr').first().getByRole('button', { name: /^\+/ }).click()
  const patch = page.waitForResponse((r) => r.request().method() === 'PATCH' && /\/api\/v1\/shifts\/slots\/\d+\/assignments$/.test(new URL(r.url()).pathname))
  await page.getByRole('dialog', { name: 'メンバーを追加' }).getByText(W.member.nickname).click()
  expect((await patch).status(), 'ADMIN の画面からの割当は 200').toBe(200)
  await expect(page.locator('tbody tr').first().getByText(W.member.nickname)).toBeVisible()
  await shot(page, 'admin-board-assigned')

  // 5. 設定: 切替2つが操作できる（時給の切替も ADMIN は変更可）
  await visit(page, settingsUrl())
  await expect(page.getByTestId('shift-manage-notice-admin')).toBeVisible()
  await expect(page.getByTestId('deputy-shift-view-toggle').locator('input')).toBeEnabled()
  await expect(page.getByTestId('deputy-hourly-rate-view-toggle').locator('input')).toBeEnabled()
  await expect(page.getByTestId('reminder-settings-save-btn')).toBeVisible()
  await expect(page.getByTestId('deputy-hourly-rate-admin-only-notice')).toHaveCount(0)
  await shot(page, 'admin-settings-shift')

  // 6. 時給: 画面から時給を設定できる（後続の閲覧確認用の前提にもなる）
  await visit(page, hourlyUrl())
  await expect(page.getByTestId('hourly-rate-table')).toBeVisible()
  await expect(page.getByTestId('hourly-rate-readonly-notice')).toHaveCount(0)
  await page.getByTestId(`hourly-rate-edit-${W.member.id}`).click()
  await page.getByTestId('hourly-rate-input').locator('input').fill('1234')
  const rate = page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/v1/shifts/hourly-rate')
  await page.getByTestId('hourly-rate-save-btn').click()
  expect((await rate).status(), 'ADMIN の画面からの時給設定は 201').toBe(201)
  await expect(page.getByText('1,234').first()).toBeVisible({ timeout: 30_000 })
  await shot(page, 'admin-hourly-rate')
})

// ---- 正の視点: MANAGE_SHIFTS 付き DEPUTY_ADMIN --------------------------------------------------

test('DEPUTY＋MANAGE_SHIFTS: 管理者と同じ操作ができる。ただし時給の閲覧切替は押せない（ADMIN だけ）', async ({ browser }) => {
  test.setTimeout(600_000)
  const page = await sessionOf(browser, W.depMng)

  await visit(page, slotUrl())
  await expectManagerShiftsPage(page, 'DEPUTY+MANAGE_SHIFTS', 'none')
  await shot(page, 'depmng-shifts')

  await visit(page, detail())
  await expect(page.getByTestId('shift-detail-actions')).toBeVisible()
  await expect(page.getByTestId('shift-manage-notice-deputy')).toHaveCount(0)
  for (const tab of ['edit', 'requests', 'constraints', 'board']) {
    await expect(page.getByTestId(`shift-tab-${tab}`), `DEPUTY+MANAGE の詳細タブ ${tab}`).toBeVisible()
  }
  await visit(page, detail('/requests'))
  await expect(page).toHaveURL(new RegExp(`/shift/${W.scheduleA}/requests$`))
  await visit(page, detail('/work-constraints'))
  await expect(page).toHaveURL(new RegExp(`/shift/${W.scheduleA}/work-constraints$`))

  // ボードで画面から割当（2 人目）
  await visit(page, boardUrl())
  await expect(page.getByTestId('auto-assign-button')).toBeVisible()
  await expect(page.getByTestId('shift-manage-notice-deputy')).toHaveCount(0)
  await page.locator('tbody tr').first().getByRole('button', { name: /^\+/ }).click()
  const patch = page.waitForResponse((r) => r.request().method() === 'PATCH' && /\/api\/v1\/shifts\/slots\/\d+\/assignments$/.test(new URL(r.url()).pathname))
  await page.getByRole('dialog', { name: 'メンバーを追加' }).getByText(W.depOff.nickname).click()
  expect((await patch).status(), 'MANAGE_SHIFTS 付き DEPUTY の画面からの割当は 200').toBe(200)
  await shot(page, 'depmng-board-assigned')

  // 設定: シフト閲覧切替は操作できて保存できる。時給の閲覧切替は押せず、理由が出る
  await visit(page, settingsUrl())
  await expect(page.getByTestId('shift-manage-notice-admin')).toHaveCount(0)
  await expect(page.getByTestId('shift-manage-notice-deputy')).toHaveCount(0)
  await expect(page.getByTestId('deputy-shift-view-toggle').locator('input')).toBeEnabled()
  await expect(page.getByTestId('deputy-hourly-rate-view-toggle').locator('input')).toBeDisabled()
  await expect(page.getByTestId('deputy-hourly-rate-admin-only-notice')).toBeVisible()
  await expect(page.getByTestId('reminder-settings-save-btn')).toBeVisible()
  await shot(page, 'depmng-settings-shift')
  // 保存（リマインド設定の変更は MANAGE_SHIFTS 付き副管理者に許される）。時給切替は送らず、値は変わらない
  const before = await apiAs(W.admin, 'GET', `/api/v1/teams/${W.teamA.slug}/shift-settings`)
  await page.getByTestId('reminder-24h-toggle').click()
  const saved = page.waitForResponse((r) => r.request().method() === 'PATCH' && new URL(r.url()).pathname === `/api/v1/teams/${W.teamA.slug}/shift-settings`)
  await page.getByTestId('reminder-settings-save-btn').click()
  expect((await saved).status(), 'MANAGE_SHIFTS 付き DEPUTY の設定保存は 200').toBe(200)
  const after = await apiAs(W.admin, 'GET', `/api/v1/teams/${W.teamA.slug}/shift-settings`)
  expect(after.body.reminder24hEnabled).toBe(!before.body.reminder24hEnabled)
  expect(after.body.deputyHourlyRateViewEnabled, '時給切替は変わらない').toBe(before.body.deputyHourlyRateViewEnabled)
  await page.getByTestId('reminder-24h-toggle').click()
  await page.getByTestId('reminder-settings-save-btn').click()
  await expect.poll(async () => (await apiAs(W.admin, 'GET', `/api/v1/teams/${W.teamA.slug}/shift-settings`)).body.reminder24hEnabled).toBe(before.body.reminder24hEnabled)

  // 時給: 閲覧と設定ができる
  await visit(page, hourlyUrl())
  await expect(page.getByTestId('hourly-rate-table')).toBeVisible()
  await expect(page.getByTestId('hourly-rate-readonly-notice')).toHaveCount(0)
  await expect(page.getByText('1,234').first()).toBeVisible()
  await page.getByTestId(`hourly-rate-edit-${W.depOff.id}`).click()
  await page.getByTestId('hourly-rate-input').locator('input').fill('1500')
  const rate = page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/v1/shifts/hourly-rate')
  await page.getByTestId('hourly-rate-save-btn').click()
  expect((await rate).status(), 'MANAGE_SHIFTS 付き DEPUTY の画面からの時給設定は 201').toBe(201)
  await shot(page, 'depmng-hourly-rate')

  // 画面から到達できない攻撃: 時給の閲覧切替の変更は ADMIN だけ（BE が 403）
  const attack = await apiAs(W.depMng, 'PATCH', `/api/v1/teams/${W.teamA.slug}/shift-settings`, { deputyHourlyRateViewEnabled: true })
  expect(attack.status, '時給切替を MANAGE_SHIFTS 付き DEPUTY が変更しようとすると 403').toBe(403)
  const state = await apiAs(W.admin, 'GET', `/api/v1/teams/${W.teamA.slug}/shift-settings`)
  expect(state.body.deputyHourlyRateViewEnabled).toBe(false)
})

// ---- 負の視点: MANAGE_SHIFTS 無しの DEPUTY（切替 OFF）-----------------------------------------------

test('DEPUTY（権限なし・切替 OFF）: 操作の導線が出ず、案内が出る。URL 直打ちでも弾かれ、割当は masked', async ({ browser }) => {
  test.setTimeout(600_000)
  const page = await sessionOf(browser, W.depOff)

  await visit(page, slotUrl())
  await expect(page.getByRole('heading', { name: 'シフト管理' })).toBeVisible()
  await expect(page.getByTestId('shift-manage-notice-deputy'), '権限なし案内').toBeVisible()
  await expect(page.getByTestId('shift-manage-notice-admin')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新規作成' })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: 'ポジション管理' }), '管理者専用のポジション管理タブは出ない').toHaveCount(0)
  await expect(page.getByTestId(`shift-board-${W.scheduleA}`), 'ボードへの導線は出ない').toHaveCount(0)
  await shot(page, 'depoff-shifts')

  // 詳細: 管理操作・希望一覧・勤務制約・ボードのタブは出ず、案内が出る
  await visit(page, detail())
  await expect(page.getByTestId('shift-detail-actions')).toHaveCount(0)
  await expect(page.getByTestId('shift-manage-notice-deputy')).toBeVisible()
  for (const tab of ['requests', 'constraints', 'board']) {
    await expect(page.getByTestId(`shift-tab-${tab}`), `DEPUTY(OFF) の詳細タブ ${tab} は出ない`).toHaveCount(0)
  }
  await shot(page, 'depoff-detail')

  // 編集画面: 管理ボタンが出ない
  await visit(page, detail('/edit'))
  await expect(page.getByTestId('shift-manage-notice-deputy')).toBeVisible()
  await expect(page.getByRole('button', { name: 'スロットを作成' })).toHaveCount(0)

  // URL 直打ち: 希望一覧・勤務制約は詳細へ戻される
  for (const sub of ['/requests', '/work-constraints']) {
    await visit(page, detail(sub))
    await expect(page, `直打ち ${sub}`).toHaveURL(new RegExp(`/shift/${W.scheduleA}$`), { timeout: 30_000 })
    await expect(page.getByTestId('shift-detail-actions')).toHaveCount(0)
  }
  // 時給: URL 直打ちで画面が出ない（表も設定ボタンも無い）
  await visit(page, hourlyUrl())
  await expect(page.getByTestId('hourly-rate-table')).toHaveCount(0)
  await expect(page.locator('[data-testid^="hourly-rate-edit-"]')).toHaveCount(0)
  expect(await bodyText(page)).not.toContain('1,234')
  await shot(page, 'depoff-hourly-direct')

  // 設定: 閲覧のみ（切替は disabled・保存ボタンなし・読み取り専用の説明）
  await visit(page, settingsUrl())
  await expect(page.getByTestId('shift-manage-notice-deputy')).toBeVisible()
  await expect(page.getByTestId('deputy-shift-view-toggle').locator('input')).toBeDisabled()
  await expect(page.getByTestId('deputy-hourly-rate-view-toggle').locator('input')).toBeDisabled()
  await expect(page.getByTestId('reminder-settings-save-btn')).toHaveCount(0)
  await expect(page.getByTestId('shift-settings-readonly-notice')).toBeVisible()
  await shot(page, 'depoff-settings-shift')

  // 変更依頼: 案内が出て、審査（承認・却下）の操作は出ない
  await visit(page, crUrl())
  await expect(page.getByTestId('shift-manage-notice-deputy')).toBeVisible()

  // ボード: 案内が出て、自動割当は出ない。割当は masked（ADMIN・MANAGE_SHIFTS 付き DEPUTY が割り当てた人が見えない）
  await visit(page, boardUrl())
  await expect(page.getByTestId('shift-manage-notice-deputy')).toBeVisible()
  await expect(page.getByTestId('auto-assign-button')).toHaveCount(0)
  await expect(page.locator('tbody tr').first().getByText(W.member.nickname), '切替 OFF では未公開の割当が見えない').toHaveCount(0)
  await expect(page.locator('tbody tr').first().getByRole('button', { name: /^\+/ }), '割当の追加導線（+ 作成）は出ない').toHaveCount(0)
  await expect(page.locator('[draggable="true"]'), 'メンバーのドラッグ割当はできない').toHaveCount(0)
  await shot(page, 'depoff-board')

  // 画面から到達できない攻撃: 管理操作は同チーム権限不足の 403
  const create = await apiAs(W.depOff, 'POST', `/api/v1/shifts/schedules?teamId=${W.teamA.numericId}`, { title: 'x', startDate: '2027-05-01', endDate: '2027-05-02' })
  expect(create.status, '権限なし DEPUTY のシフト表作成').toBe(403)
  const patchSettings = await apiAs(W.depOff, 'PATCH', `/api/v1/teams/${W.teamA.slug}/shift-settings`, { reminder24hEnabled: false })
  expect(patchSettings.status, '権限なし DEPUTY の設定変更').toBe(403)
  const del = await apiAs(W.depOff, 'DELETE', `/api/v1/shifts/schedules/${W.scheduleA}`)
  expect(del.status, '権限なし DEPUTY のシフト表削除').toBe(403)
  const rates = await apiAs(W.depOff, 'GET', `/api/v1/shifts/hourly-rates?teamId=${W.teamA.numericId}&date=2027-02-02`)
  expect(rates.status, '切替 OFF の時給一覧').toBe(403)
  const reqs = await apiAs(W.depOff, 'GET', `/api/v1/shifts/requests?scheduleId=${W.scheduleA}`)
  expect(reqs.status, '切替 OFF の希望一覧').toBe(403)
  // 自動割当: 実行・履歴一覧は同チーム権限不足の 403。run を指す操作（詳細・確定・破棄）は同チームの非管理者にも run の実在を隠して 404
  const run = await apiAs(W.depOff, 'POST', `/api/v1/shifts/schedules/${W.scheduleA}/auto-assign`, { strategy: 'GREEDY_V1' })
  expect([run.status, run.body?.error?.code], '権限なし DEPUTY の自動割当の実行').toEqual([403, 'COMMON_002'])
  const runs = await apiAs(W.depOff, 'GET', `/api/v1/shifts/schedules/${W.scheduleA}/assignment-runs`)
  expect(runs.status, '権限なし DEPUTY の自動割当の履歴一覧').toBe(403)
  const adminRun = await apiAs(W.admin, 'POST', `/api/v1/shifts/schedules/${W.scheduleA}/auto-assign`, { strategy: 'GREEDY_V1' })
  expect(adminRun.status, `前提: ADMIN は自動割当を実行できる ${JSON.stringify(adminRun.body)}`).toBe(201)
  const runId = adminRun.body.data.id as number
  const real = await apiAs(W.depOff, 'GET', `/api/v1/shifts/assignment-runs/${runId}`)
  const absent = await apiAs(W.depOff, 'GET', '/api/v1/shifts/assignment-runs/999999999')
  expect([real.status, real.body?.error?.code], '実在する run の詳細は、不在と同じ 404（同チームの非管理者にも実在を隠す）').toEqual([absent.status, absent.body?.error?.code])
  expect(real.status).toBe(404)
  const revoke = await apiAs(W.depOff, 'DELETE', `/api/v1/shifts/schedules/${W.scheduleA}/auto-assign`, runId)
  expect(revoke.status, '権限なし DEPUTY の run 破棄は不在と同じ 404').toBe(404)
  // 前提の後始末: ADMIN が run を破棄して、後続の画面確認に影響を残さない
  const cleanup = await apiAs(W.admin, 'DELETE', `/api/v1/shifts/schedules/${W.scheduleA}/auto-assign`, runId)
  expect(cleanup.status, 'ADMIN の run 破棄').toBeLessThan(300)
})

// ---- 切替: 管理者が画面から ON/OFF し、権限なし DEPUTY の見え方が変わる ----------------------------

test('切替: 管理者が画面でシフト閲覧を ON にすると権限なし DEPUTY に希望一覧・割当が見える（操作は出ない）。時給は別切替。OFF で元に戻る', async ({ browser }) => {
  test.setTimeout(600_000)
  const admin = await sessionOf(browser, W.admin)
  const dep = await sessionOf(browser, W.depOff)

  // シフト閲覧 ON
  await setToggleViaUi(admin, 'deputy-shift-view-toggle', true)
  await visit(dep, detail())
  await expect(dep.getByTestId('shift-tab-requests'), '切替 ON で希望一覧タブが出る').toBeVisible()
  await expect(dep.getByTestId('shift-tab-constraints'), '勤務制約は管理操作なので出ない').toHaveCount(0)
  await expect(dep.getByTestId('shift-detail-actions'), '操作は出ない').toHaveCount(0)
  await expect(dep.getByTestId('shift-manage-notice-deputy')).toBeVisible()
  await visit(dep, detail('/requests'))
  await expect(dep, '切替 ON の希望一覧は開ける').toHaveURL(new RegExp(`/shift/${W.scheduleA}/requests$`))
  await shot(dep, 'depoff-requests-view-on')
  await visit(dep, detail('/work-constraints'))
  await expect(dep, '勤務制約は切替 ON でも詳細へ戻される').toHaveURL(new RegExp(`/shift/${W.scheduleA}$`), { timeout: 30_000 })
  await visit(dep, boardUrl())
  await expect(dep.locator('tbody tr').first().getByText(W.member.nickname), '切替 ON で割当が見える').toBeVisible()
  await expect(dep.getByTestId('auto-assign-button'), '自動割当は出ない').toHaveCount(0)
  await expect(dep.locator('tbody tr').first().getByRole('button', { name: /^\+/ }), '切替 ON でも割当の追加導線は出ない').toHaveCount(0)
  await expect(dep.locator('[draggable="true"]')).toHaveCount(0)
  await shot(dep, 'depoff-board-view-on')
  // 時給は別切替なので、まだ見えない
  await visit(dep, hourlyUrl())
  await expect(dep.getByTestId('hourly-rate-table'), '時給切替 OFF のうちは時給画面が出ない').toHaveCount(0)
  // 切替 ON でも操作は常に MANAGE_SHIFTS が必要（直接叩いても 403）
  const create = await apiAs(W.depOff, 'POST', `/api/v1/shifts/schedules?teamId=${W.teamA.numericId}`, { title: 'x', startDate: '2027-05-01', endDate: '2027-05-02' })
  expect(create.status, '切替 ON でも作成は 403').toBe(403)

  // 時給閲覧 ON
  await setToggleViaUi(admin, 'deputy-hourly-rate-view-toggle', true)
  await visit(dep, hourlyUrl())
  await expect(dep.getByTestId('hourly-rate-table')).toBeVisible()
  await expect(dep.getByTestId('hourly-rate-readonly-notice'), '閲覧のみの説明').toBeVisible()
  await expect(dep.locator('[data-testid^="hourly-rate-edit-"]'), '登録・変更の導線は出ない').toHaveCount(0)
  await expect(dep.getByText('1,234').first(), '他メンバーの時給が見える').toBeVisible()
  await shot(dep, 'depoff-hourly-view-on')
  const set = await apiAs(W.depOff, 'POST', `/api/v1/shifts/hourly-rate?teamId=${W.teamA.numericId}`, { userId: W.member.id, hourlyRate: 9999, effectiveFrom: '2027-02-02' })
  expect(set.status, '時給の登録は切替 ON でも 403').toBe(403)

  // OFF に戻す → 再び見えない
  await setToggleViaUi(admin, 'deputy-hourly-rate-view-toggle', false)
  await setToggleViaUi(admin, 'deputy-shift-view-toggle', false)
  await visit(dep, hourlyUrl())
  await expect(dep.getByTestId('hourly-rate-table')).toHaveCount(0)
  await visit(dep, detail('/requests'))
  await expect(dep, 'OFF に戻すと希望一覧は詳細へ戻される').toHaveURL(new RegExp(`/shift/${W.scheduleA}$`), { timeout: 30_000 })
})

// ---- 権限グループの付与・剥奪（画面）----------------------------------------------------------

test('権限グループ: 管理者が画面で MANAGE_SHIFTS を付与すると DEPUTY が即座に操作でき、外すと即座に操作できなくなる', async ({ browser }) => {
  test.setTimeout(600_000)
  const admin = await sessionOf(browser, W.admin)
  const dep = await sessionOf(browser, W.depGrant)

  // 付与前: 操作できない
  await visit(dep, slotUrl())
  await expect(dep.getByTestId('shift-manage-notice-deputy')).toBeVisible()
  await expect(dep.getByRole('button', { name: '新規作成' })).toHaveCount(0)

  // 管理者が権限グループを画面から作成（対象ロール: 副管理者・権限: MANAGE_SHIFTS）
  await visit(admin, `/teams/${W.teamA.slug}/permission-groups`, { perm: false })
  await admin.getByTestId('permission-group-create').click()
  const groupName = `画面付与${Date.now().toString(36)}`
  await admin.getByTestId('pf-name').fill(groupName)
  await admin.getByTestId('pf-target-role').click()
  await admin.getByRole('option', { name: '副管理者' }).click()
  await admin.getByTestId(`pf-perm-${W.permissionId}`).click()
  const createdGroup = admin.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === `/api/v1/teams/${W.teamA.slug}/permission-groups`)
  await admin.getByTestId('pf-submit').click()
  const groupRes = await createdGroup
  expect(groupRes.status(), '権限グループの作成').toBe(201)
  const groupId = (await groupRes.json()).data.id as number
  await expect(admin.getByTestId('pf-saved')).toBeVisible()
  await shot(admin, 'admin-permission-group-created')

  // メンバー一覧から depGrant に割り当てる
  await visit(admin, `/teams/${W.teamA.slug}/members`, { perm: false })
  await admin.getByTestId(`member-permission-groups-${W.depGrant.id}`).click()
  await admin.getByTestId(`pg-check-${groupId}`).click()
  const assign = admin.waitForResponse((r) => r.request().method() === 'PUT' && new URL(r.url()).pathname === `/api/v1/teams/${W.teamA.slug}/members/${W.depGrant.id}/permission-groups`)
  await admin.getByTestId('pg-assign-submit').click()
  expect((await assign).status(), '権限グループの割当').toBe(200)

  // 付与直後: 再読込みだけで操作できる（キャッシュで古い権限が残らない）
  await visit(dep, slotUrl())
  await expectManagerShiftsPage(dep, 'depGrant(付与後)', 'none')
  await shot(dep, 'depgrant-after-grant')
  await visit(dep, detail())
  await expect(dep.getByTestId('shift-detail-actions')).toBeVisible()
  const okCreate = await apiAs(W.depGrant, 'POST', `/api/v1/shifts/schedules?teamId=${W.teamA.numericId}`, { title: `2114付与後${Date.now().toString(36)}`, startDate: '2027-06-01', endDate: '2027-06-02' })
  expect(okCreate.status, '付与後は作成できる').toBe(201)
  createdSchedules.push({ id: okCreate.body.data.id, owner: W.admin })

  // 管理者が権限グループを外す
  await visit(admin, `/teams/${W.teamA.slug}/members`, { perm: false })
  await admin.getByTestId(`member-permission-groups-${W.depGrant.id}`).click()
  await admin.getByTestId(`pg-check-${groupId}`).click()
  const revoke = admin.waitForResponse((r) => r.request().method() === 'PUT' && new URL(r.url()).pathname === `/api/v1/teams/${W.teamA.slug}/members/${W.depGrant.id}/permission-groups`)
  await admin.getByTestId('pg-assign-submit').click()
  expect((await revoke).status(), '権限グループの剥奪').toBe(200)

  // 剥奪直後: 再読込みだけで操作できなくなる（画面と BE の両方）
  await visit(dep, slotUrl())
  await expect(dep.getByTestId('shift-manage-notice-deputy')).toBeVisible()
  await expect(dep.getByRole('button', { name: '新規作成' })).toHaveCount(0)
  await visit(dep, detail())
  await expect(dep.getByTestId('shift-detail-actions')).toHaveCount(0)
  const denied = await apiAs(W.depGrant, 'POST', `/api/v1/shifts/schedules?teamId=${W.teamA.numericId}`, { title: 'x', startDate: '2027-06-01', endDate: '2027-06-02' })
  expect(denied.status, '剥奪後は作成できない').toBe(403)
  await shot(dep, 'depgrant-after-revoke')
  const g = await apiAs(W.admin, 'DELETE', `/api/v1/teams/${W.teamA.slug}/permission-groups/${groupId}`)
  expect([200, 204]).toContain(g.status)
})

// ---- 負の視点: MEMBER --------------------------------------------------------------------------

test('MEMBER: 管理の導線も案内も出ず、直打ちでも弾かれ、直接叩いても 403', async ({ browser }) => {
  test.setTimeout(600_000)
  const page = await sessionOf(browser, W.member)

  await visit(page, slotUrl())
  await expect(page.getByRole('heading', { name: 'シフト管理' })).toBeVisible()
  await expect(page.getByTestId('shift-manage-notice-deputy'), 'MEMBER に権限なし案内は出ない（副管理者向け）').toHaveCount(0)
  await expect(page.getByTestId('shift-manage-notice-admin')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新規作成' })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: 'ポジション管理' }), '管理者専用のポジション管理タブは出ない').toHaveCount(0)
  await shot(page, 'member-shifts')

  await visit(page, detail())
  await expect(page.getByTestId('shift-detail-actions')).toHaveCount(0)
  for (const tab of ['requests', 'constraints', 'board']) {
    await expect(page.getByTestId(`shift-tab-${tab}`)).toHaveCount(0)
  }
  for (const sub of ['/requests', '/work-constraints']) {
    await visit(page, detail(sub))
    await expect(page, `直打ち ${sub}`).toHaveURL(new RegExp(`/shift/${W.scheduleA}$`), { timeout: 30_000 })
  }
  await visit(page, hourlyUrl())
  await expect(page.getByTestId('hourly-rate-table')).toHaveCount(0)
  await visit(page, settingsUrl())
  await expect(page.getByTestId('reminder-settings-save-btn')).toHaveCount(0)
  await expect(page.getByTestId('deputy-shift-view-toggle').locator('input')).toBeDisabled()
  await shot(page, 'member-settings-shift')

  await visit(page, boardUrl())
  await expect(page.getByTestId('auto-assign-button')).toHaveCount(0)
  await expect(page.locator('tbody tr').first().getByText(W.depOff.nickname), '未公開の割当は見えない').toHaveCount(0)
  await expect(page.locator('tbody tr').first().getByRole('button', { name: /^\+/ }), '割当の追加導線は出ない').toHaveCount(0)
  await expect(page.locator('[draggable="true"]')).toHaveCount(0)
  await shot(page, 'member-board')

  for (const [label, r] of [
    ['作成', await apiAs(W.member, 'POST', `/api/v1/shifts/schedules?teamId=${W.teamA.numericId}`, { title: 'x', startDate: '2027-05-01', endDate: '2027-05-02' })],
    ['設定変更', await apiAs(W.member, 'PATCH', `/api/v1/teams/${W.teamA.slug}/shift-settings`, { reminder24hEnabled: false })],
    ['時給一覧', await apiAs(W.member, 'GET', `/api/v1/shifts/hourly-rates?teamId=${W.teamA.numericId}&date=2027-02-02`)],
    ['希望一覧', await apiAs(W.member, 'GET', `/api/v1/shifts/requests?scheduleId=${W.scheduleA}`)],
    ['勤務制約の他人分', await apiAs(W.member, 'GET', `/api/v1/shifts/teams/${W.teamA.numericId}/work-constraints/members/${W.depOff.id}`)],
  ] as const) {
    expect(r.status, `MEMBER の ${label} は 403`).toBe(403)
  }
})

// ---- 他テナント・チームロール無し SYSTEM_ADMIN ------------------------------------------------

test('他チーム ADMIN と チームロール無し SYSTEM_ADMIN: URL 直打ちで画面が出ず、API は 404（越境）／403', async ({ browser }) => {
  test.setTimeout(600_000)
  for (const [who, actor] of [['他チーム ADMIN', W.bAdmin], ['SYSTEM_ADMIN(チームロール無し)', W.sysAdmin]] as const) {
    const page = await sessionOf(browser, actor)
    const tag = actor === W.bAdmin ? 'badmin' : 'sysadmin'

    await visit(page, slotUrl(), { perm: false })
    await expect(page.getByRole('button', { name: '新規作成' }), `${who}: 新規作成が出ない`).toHaveCount(0)
    await expect(page.getByTestId('shift-manage-notice-admin')).toHaveCount(0)
    expect(await bodyText(page), `${who}: 他チームのシフト表名が出ない`).not.toContain('2114シフトA')
    await shot(page, `${tag}-shifts-direct`)

    await visit(page, detail(), { perm: false })
    expect(await bodyText(page), `${who}: 詳細に他チームのシフト表名が出ない`).not.toContain('2114シフトA')
    await expect(page.getByTestId('shift-detail-actions')).toHaveCount(0)
    await shot(page, `${tag}-detail-direct`)

    for (const [label, p] of [['edit', detail('/edit')], ['requests', detail('/requests')], ['work-constraints', detail('/work-constraints')], ['board', boardUrl()], ['change-requests', crUrl()]] as const) {
      await visit(page, p, { perm: false })
      expect(await bodyText(page), `${who}: ${label} に他チームのシフト表名が出ない`).not.toContain('2114シフトA')
      await expect(page.getByTestId('shift-detail-actions')).toHaveCount(0)
      await expect(page.getByTestId('auto-assign-button')).toHaveCount(0)
    }
    await visit(page, settingsUrl(), { perm: false })
    await expect(page.getByTestId('reminder-settings-save-btn'), `${who}: 設定の保存ボタンが出ない`).toHaveCount(0)
    await visit(page, hourlyUrl(), { perm: false })
    await expect(page.getByTestId('hourly-rate-table')).toHaveCount(0)
    expect(await bodyText(page)).not.toContain('1,234')
    await shot(page, `${tag}-hourly-direct`)
  }

  // API の拒否の作り分け（存在オラクルの封鎖）: 越境・チームロール無しは「存在しない」と同じ応答
  const absent = await apiAs(W.admin, 'GET', '/api/v1/shifts/schedules/999999999')
  for (const [who, actor] of [['他チーム ADMIN', W.bAdmin], ['SYSTEM_ADMIN', W.sysAdmin]] as const) {
    const get = await apiAs(actor, 'GET', `/api/v1/shifts/schedules/${W.scheduleA}`)
    expect(get.status, `${who} の他チームのシフト表取得`).toBe(absent.status)
    const del = await apiAs(actor, 'DELETE', `/api/v1/shifts/schedules/${W.scheduleA}`)
    expect(del.status, `${who} の他チームのシフト表削除`).toBe(absent.status)
    const patch = await apiAs(actor, 'PATCH', `/api/v1/teams/${W.teamA.slug}/shift-settings`, { reminder24hEnabled: false })
    expect([403, 404], `${who} の他チームの設定変更`).toContain(patch.status)
    const create = await apiAs(actor, 'POST', `/api/v1/shifts/schedules?teamId=${W.teamA.numericId}`, { title: 'x', startDate: '2027-05-01', endDate: '2027-05-02' })
    expect([403, 404], `${who} の他チームへのシフト表作成`).toContain(create.status)
    const rates = await apiAs(actor, 'GET', `/api/v1/shifts/hourly-rates?teamId=${W.teamA.numericId}&date=2027-02-02`)
    expect([403, 404], `${who} の他チームの時給一覧`).toContain(rates.status)
    const run = await apiAs(actor, 'POST', `/api/v1/shifts/schedules/${W.scheduleA}/auto-assign`, { strategy: 'GREEDY_V1' })
    expect(run.status, `${who} の他チームの自動割当`).toBe(404)
  }
  // 他チームの勤務制約を自チームの権限で作る攻撃（B の ADMIN が A のメンバーの制約を作る）は拒否
  const cross = await apiAs(W.bAdmin, 'PUT', `/api/v1/shifts/teams/${W.teamB.numericId}/work-constraints/members/${W.member.id}`, { maxHoursPerWeek: 10 })
  expect([403, 404], 'B の ADMIN が A のメンバーの勤務制約を作れない').toContain(cross.status)
})

// ---- スマホ幅（390px）-------------------------------------------------------------------------

test('スマホ幅 390px: 主要画面で横スクロールが出ず、案内と操作が見える', async ({ browser }) => {
  test.setTimeout(600_000)
  for (const [who, actor] of [['ADMIN', W.admin], ['DEPUTY(権限なし)', W.depOff]] as const) {
    const context = await browser.newContext({ baseURL: FE, viewport: { width: 390, height: 844 }, storageState: { cookies: [], origins: [] }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
    const page = await context.newPage()
    try {
      const { loginViaApi } = await import('../fixtures/auth')
      await page.goto(`${FE}/robots.txt`)
      await loginViaApi(page, actor, { apiBaseUrl: process.env.API_BASE_URL || 'http://localhost:8081', deferNavigation: true })
      const tag = actor === W.admin ? 'admin' : 'depoff'
      await visit(page, slotUrl())
      await expect(page.getByTestId(actor === W.admin ? 'shift-manage-notice-admin' : 'shift-manage-notice-deputy')).toBeVisible()
      if (actor === W.admin) await expect(page.getByRole('button', { name: '新規作成' })).toBeVisible()
      await noHorizontalScroll(page, `${who} シフト管理`)
      await shot(page, `mobile-${tag}-shifts`)
      await visit(page, detail())
      await noHorizontalScroll(page, `${who} 詳細`)
      await shot(page, `mobile-${tag}-detail`)
      await visit(page, settingsUrl())
      await expect(page.getByTestId('deputy-shift-view-toggle')).toBeVisible()
      await noHorizontalScroll(page, `${who} 設定`)
      await shot(page, `mobile-${tag}-settings`)
      await visit(page, hourlyUrl())
      if (actor === W.admin) await expect(page.getByTestId('hourly-rate-table')).toBeVisible()
      await noHorizontalScroll(page, `${who} 時給`)
      await shot(page, `mobile-${tag}-hourly`)
    } finally {
      await context.close()
    }
  }
})
