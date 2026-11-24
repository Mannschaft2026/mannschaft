import { appendFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { expect, test, type Page } from '@playwright/test'
import {
  API,
  apiAs,
  createActor,
  createOrg,
  createTeam,
  joinScope,
  must,
  openAs,
  sql,
  type Actor,
  type Scope,
} from './helpers/f0121-org-groups-world'

/**
 * CMP-260930-0233 駐車場申請の閲覧範囲・申請画面 実機E2E（モックなし・実BE・実FE・実ブラウザ）。
 *
 * - API を使うのは「ログイン・前提データ作成（区画・車両・他人の申請・権限グループ）・観測（DB）・後始末」だけ。
 *   対象操作（申請・取消・一覧の閲覧）は画面のクリック・入力・送信で行う。
 * - 認可境界の「API 直打ち」は、そのユーザーのログイン済みブラウザで URL を開く（GET）か、
 *   ブラウザ文脈の request（Cookie 共有）で行い、FE の出し分けではなく BE が弾くことを確認する。
 * - 専用設定 playwright-parking-application.config.ts から実行する（F0121 土台の後始末印を共用）。
 */

const EVIDENCE =
  process.env.E2E_EVIDENCE_DIR ??
  '/tmp/claude-0/-home-user/08337c2f-0ee1-5cd2-9adc-35efb3089852/scratchpad/e2e-evidence'
mkdirSync(EVIDENCE, { recursive: true })

function note(id: string, text: string): void {
  appendFileSync(path.join(EVIDENCE, 'log.txt'), `[${id}] ${text}\n`)
  console.log(`[${id}] ${text}`)
}
async function shot(page: Page, name: string): Promise<void> {
  await page.screenshot({ path: path.join(EVIDENCE, `${name}.png`), fullPage: true })
}

const SPACE_COUNT = 23
let team: Scope
let org: Scope
let admin: Actor
let depPerm: Actor
let depNo: Actor
let memA: Actor
let memB: Actor
let memC: Actor // 車両未登録
let supporter: Actor
let outsider: Actor
let orgAdmin: Actor
let orgMemA: Actor
let orgMemB: Actor

const spaceIds: Record<string, number> = {}
const orgSpaceIds: Record<string, number> = {}
const vehicleOf = new Map<string, number>()
let appA = 0 // memA の申請 ID
let appB = 0
let appAdmin = 0
let appDepPerm = 0
let appDepNo = 0

const T = () => `/api/v1/teams/${team.slug}/parking`
const O = () => `/api/v1/organizations/${org.slug}/parking`

async function newSpace(base: string, owner: Actor, num: string, type = 'OUTDOOR', accept = true): Promise<number> {
  const body = await must(owner, 'POST', `${base}/spaces`, { spaceNumber: num, spaceType: type })
  const id = body.data.id as number
  if (accept) await must(owner, 'PATCH', `${base}/spaces/${id}/accept-applications`, { allocationMethod: 'FIRST_COME' })
  return id
}
async function newVehicle(actor: Actor, plate: string): Promise<number> {
  const body = await must(actor, 'POST', '/api/v1/users/me/vehicles', {
    vehicleType: 'CAR',
    plateNumber: plate,
    nickname: `n-${plate}`,
  })
  vehicleOf.set(actor.label, body.data.id)
  return body.data.id as number
}
async function applyByApi(actor: Actor, base: string, spaceId: number): Promise<number> {
  const body = await must(actor, 'POST', `${base}/applications`, { spaceId, vehicleId: vehicleOf.get(actor.label) })
  return body.data.id as number
}

test.describe.configure({ mode: 'serial' })

test.beforeAll(async () => {
  test.setTimeout(600_000)
  const S = 'pkapp'
  admin = await createActor('ADMIN', S)
  depPerm = await createActor('DEPPERM', S)
  depNo = await createActor('DEPNO', S)
  memA = await createActor('MEMA', S)
  memB = await createActor('MEMB', S)
  memC = await createActor('MEMC', S)
  supporter = await createActor('SUPP', S)
  outsider = await createActor('OUTSIDER', S)
  orgAdmin = await createActor('ORGADMIN', S)
  orgMemA = await createActor('ORGMEMA', S)
  orgMemB = await createActor('ORGMEMB', S)

  team = await createTeam(admin, `PKAPP チーム ${Date.now().toString(36)}`)
  await joinScope('teams', admin, team.slug, depPerm, 'DEPUTY_ADMIN')
  await joinScope('teams', admin, team.slug, depNo, 'DEPUTY_ADMIN')
  await joinScope('teams', admin, team.slug, memA, 'MEMBER')
  await joinScope('teams', admin, team.slug, memB, 'MEMBER')
  await joinScope('teams', admin, team.slug, memC, 'MEMBER')
  await joinScope('teams', admin, team.slug, supporter, 'SUPPORTER')

  // DEPUTY_ADMIN へ MANAGE_PARKING を権限グループ経由で付与
  const catalog = await must(admin, 'GET', `/api/v1/permissions?scopeType=TEAM&scopeId=${team.numericId}`)
  const perm = (catalog.data as Array<{ id: number; name: string }>).find((p) => p.name === 'MANAGE_PARKING')
  if (!perm) throw new Error('MANAGE_PARKING が権限カタログに無い')
  const group = await must(admin, 'POST', `/api/v1/teams/${team.slug}/permission-groups`, {
    name: '実機駐車場管理',
    targetRole: 'DEPUTY_ADMIN',
    permissionIds: [perm.id],
  })
  await must(admin, 'PUT', `/api/v1/teams/${team.slug}/members/${depPerm.id}/permission-groups`, {
    groupIds: [group.data.id],
  })

  // 区画: 受付中 23 件（P-01..P-23）、受付停止 1 件、VISITOR（受付中）1 件
  for (let i = 1; i <= SPACE_COUNT; i++) {
    const num = `P-${String(i).padStart(2, '0')}`
    spaceIds[num] = await newSpace(T(), admin, num)
  }
  spaceIds['STOP-1'] = await newSpace(T(), admin, 'STOP-1', 'OUTDOOR', false)
  spaceIds['VIS-1'] = await newSpace(T(), admin, 'VIS-1', 'VISITOR', true)

  // 車両（memC は未登録のまま）
  const run = Date.now().toString(36).slice(-5)
  for (const a of [admin, depPerm, depNo, memA, memB, supporter]) await newVehicle(a, `PK-${a.label}-${run}`)

  // 他人の申請（前提）。memA・memB の申請は画面で作るため、ここでは作らない
  appAdmin = await applyByApi(admin, T(), spaceIds['P-10']!)
  appDepPerm = await applyByApi(depPerm, T(), spaceIds['P-11']!)
  appDepNo = await applyByApi(depNo, T(), spaceIds['P-12']!)

  // 組織スコープ
  org = await createOrg(orgAdmin, `PKAPP 組織 ${Date.now().toString(36)}`)
  await joinScope('organizations', orgAdmin, org.slug, orgMemA, 'MEMBER')
  await joinScope('organizations', orgAdmin, org.slug, orgMemB, 'MEMBER')
  orgSpaceIds['O-01'] = await newSpace(O(), orgAdmin, 'O-01')
  orgSpaceIds['O-02'] = await newSpace(O(), orgAdmin, 'O-02')
  await newVehicle(orgMemA, `PKO-A-${run}`)
  await newVehicle(orgMemB, `PKO-B-${run}`)
  note('SETUP', `team=${team.slug}(${team.numericId}) org=${org.slug}(${org.numericId}) spaces=${Object.keys(spaceIds).length}`)
})

test.afterAll(async () => {
  // 組織・チームの削除（所有者の API）は f0121 の globalTeardown が担う。ここでは作成した車両・申請の残りを DB から消す
  const emails = [admin, depPerm, depNo, memA, memB, memC, supporter, outsider, orgAdmin, orgMemA, orgMemB]
    .filter(Boolean)
    .map((a) => `'${a.email}'`)
    .join(',')
  if (!emails) return
  try {
    sql(`DELETE v FROM registered_vehicles v JOIN users u ON u.id=v.user_id WHERE u.email IN (${emails});`)
  } catch (e) {
    note('CLEANUP', `車両の DB 削除に失敗（参照が残っている可能性）: ${String(e).slice(0, 200)}`)
  }
})

async function applyDialogOpen(page: Page) {
  await page.getByTestId('parking-apply-open').click()
  await expect(page.getByTestId('parking-apply-dialog')).toBeVisible()
}

test('PK-01 MEMBER A が画面から申請し、自分の一覧に出る（永続化確認）', async ({ browser }) => {
  const { context, page } = await openAs(browser, memA, `/teams/${team.slug}/parking`)
  note('PK-01', `開始URL=/teams/${team.slug}/parking 入口=[data-testid=parking-apply-open]`)
  await expect(page.getByTestId('parking-apply-open')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByTestId('parking-application')).toHaveCount(0)
  await applyDialogOpen(page)
  await page.getByTestId('parking-apply-space').click()
  // 区画の選択肢: 受付中のみ。VISITOR・受付停止は出ない。23 件目（2 ページ目相当）も出る
  const opts = page.getByRole('option')
  await expect(opts.first()).toBeVisible()
  const texts = (await opts.allInnerTexts()).map((s) => s.trim())
  note('PK-01', `区画選択肢 ${texts.length} 件 先頭=${texts[0]} 末尾=${texts[texts.length - 1]}`)
  expect(texts.some((s) => s.startsWith('VIS-1'))).toBe(false)
  expect(texts.some((s) => s.startsWith('STOP-1'))).toBe(false)
  expect(texts.some((s) => s.startsWith('P-23'))).toBe(true)
  await shot(page, 'PK-01a-space-options')
  await page.getByRole('option', { name: /^P-01/ }).click()
  // 車両は 1 台なので自動選択される
  await expect(page.getByTestId('parking-apply-vehicle')).toContainText(`PK-MEMA`)
  await page.getByTestId('parking-apply-message').fill('e2e application A')
  const resp = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/parking/applications'))
  await page.getByTestId('parking-apply-submit').click()
  expect((await resp).status()).toBe(201)
  await expect(page.getByTestId('parking-apply-dialog')).toBeHidden()
  await expect(page.getByTestId('parking-application')).toHaveCount(1)
  await expect(page.getByTestId('parking-application').first()).toContainText('P-01')
  await expect(page.getByTestId('parking-application-status').first()).toHaveText('審査待ち')
  await shot(page, 'PK-01b-after-apply')
  await page.reload()
  await expect(page.getByTestId('parking-application')).toHaveCount(1, { timeout: 60_000 })
  const row = sql(
    `SELECT a.id FROM parking_applications a JOIN users u ON u.id=a.user_id WHERE u.email='${memA.email}' AND a.status='PENDING';`,
  )
  appA = Number(row)
  expect(appA).toBeGreaterThan(0)
  note('PK-01', `DB永続化 parking_applications.id=${appA} status=PENDING。API補助: ログイン・前提のみ`)
  await context.close()
})

test('PK-02 MEMBER B に A の申請は出ない（画面と URL 直打ち API）', async ({ browser }) => {
  const { context, page } = await openAs(browser, memB, `/teams/${team.slug}/parking`)
  await expect(page.getByTestId('parking-apply-open')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByText('申請はありません')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId('parking-application')).toHaveCount(0)
  await shot(page, 'PK-02a-memB-empty')
  // B が自分の申請を 1 件作る（画面）
  await applyDialogOpen(page)
  await page.getByTestId('parking-apply-space').click()
  await page.getByRole('option', { name: /^P-02/ }).click()
  const resp = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/parking/applications'))
  await page.getByTestId('parking-apply-submit').click()
  expect((await resp).status()).toBe(201)
  await expect(page.getByTestId('parking-application')).toHaveCount(1)
  await expect(page.getByTestId('parking-application').first()).toContainText('P-02')
  appB = Number(
    sql(`SELECT a.id FROM parking_applications a JOIN users u ON u.id=a.user_id WHERE u.email='${memB.email}' AND a.status='PENDING';`),
  )
  // API 一覧を URL 直打ち
  const url = `${API}/api/v1/teams/${team.slug}/parking/applications?page=0&size=100`
  await page.goto(url)
  const json = JSON.parse(await page.locator('body').innerText())
  note('PK-02', `直打ち GET ${url} total=${json.meta?.total} ids=${(json.data ?? []).map((a: { id: number }) => a.id)}`)
  expect(json.meta.total).toBe(1)
  expect(json.data.map((a: { id: number }) => a.id)).toEqual([appB])
  expect(json.data.some((a: { id: number }) => a.id === appA)).toBe(false)
  await shot(page, 'PK-02b-memB-api-direct')
  await context.close()
})

test('PK-03 ADMIN と MANAGE_PARKING 付与 DEPUTY は全員分、権限なし DEPUTY は本人分のみ', async ({ browser }) => {
  const expectedAll = 5 // admin, depPerm, depNo, memA, memB
  for (const [label, actor, all] of [
    ['admin', admin, true],
    ['depPerm', depPerm, true],
    ['depNo', depNo, false],
  ] as const) {
    const { context, page } = await openAs(browser, actor, `/teams/${team.slug}/parking`)
    await expect(page.getByTestId('parking-apply-open')).toBeVisible({ timeout: 60_000 })
    if (all) {
      await expect(page.getByText('申請一覧', { exact: true })).toBeVisible()
      await expect(page.getByTestId('parking-application')).toHaveCount(expectedAll, { timeout: 30_000 })
    } else {
      await expect(page.getByText('自分の申請', { exact: true })).toBeVisible()
      await expect(page.getByTestId('parking-application')).toHaveCount(1, { timeout: 30_000 })
    }
    const body = JSON.parse(
      await (async () => {
        await page.goto(`${API}/api/v1/teams/${team.slug}/parking/applications?page=0&size=100`)
        return page.locator('body').innerText()
      })(),
    )
    note('PK-03', `${label} 画面件数 + API直打ち total=${body.meta.total}（期待 ${all ? expectedAll : 1}）`)
    expect(body.meta.total).toBe(all ? expectedAll : 1)
    await shot(page, `PK-03-${label}-api-direct`)
    await page.goBack()
    await shot(page, `PK-03-${label}-list`)
    await context.close()
  }
})

test('PK-04 取消（確認ダイアログ）と他人による取消の拒否', async ({ browser }) => {
  // 先に B・ADMIN が A の申請 ID へ DELETE → 403（ブラウザ文脈の request。Cookie 共有）
  for (const [label, actor] of [
    ['memB', memB],
    ['admin', admin],
  ] as const) {
    const { context, page } = await openAs(browser, actor, `/teams/${team.slug}/parking`)
    const r = await context.request.delete(`${API}/api/v1/teams/${team.slug}/parking/applications/${appA}`)
    note('PK-04', `${label} が A の申請 ${appA} を DELETE -> ${r.status()} ${(await r.text()).slice(0, 160)}`)
    expect(r.status()).toBe(403)
    // 画面上も他人の申請には取消ボタンが出ない（ADMIN の全件表示）
    if (label === 'admin') {
      await expect(page.getByTestId('parking-application')).toHaveCount(5, { timeout: 30_000 })
      await expect(page.getByTestId('parking-application-cancel')).toHaveCount(1) // 自分の 1 件だけ
      await shot(page, 'PK-04a-admin-cancel-only-own')
    }
    await context.close()
  }
  expect(sql(`SELECT status FROM parking_applications WHERE id=${appA};`)).toBe('PENDING')

  const { context, page } = await openAs(browser, memA, `/teams/${team.slug}/parking`)
  await expect(page.getByTestId('parking-application')).toHaveCount(1, { timeout: 60_000 })
  await page.getByTestId('parking-application-cancel').click()
  const dialog = page.getByRole('alertdialog').or(page.getByRole('dialog')).filter({ hasText: '申請の取消' })
  await expect(dialog).toBeVisible()
  await shot(page, 'PK-04b-confirm-dialog')
  const resp = page.waitForResponse((r) => r.request().method() === 'DELETE' && r.url().includes('/parking/applications/'))
  await dialog.getByRole('button', { name: '取り消す' }).click()
  expect((await resp).status()).toBe(204)
  await expect(page.getByTestId('parking-application-status').first()).toHaveText('取消済み', { timeout: 30_000 })
  await shot(page, 'PK-04c-cancelled')
  expect(sql(`SELECT status FROM parking_applications WHERE id=${appA};`)).toBe('CANCELLED')
  note('PK-04', 'A が画面から取消 -> DB status=CANCELLED')
  await context.close()
})

test('PK-05 重複申請は 409 文言（i18n）が出てフォームが壊れない', async ({ browser }) => {
  const { context, page } = await openAs(browser, memB, `/teams/${team.slug}/parking`)
  await expect(page.getByTestId('parking-application')).toHaveCount(1, { timeout: 60_000 })
  await applyDialogOpen(page)
  await page.getByTestId('parking-apply-space').click()
  // P-02 は B が申請済みだが空きのまま選択肢に残る
  await page.getByRole('option', { name: /^P-02/ }).click()
  const resp = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/parking/applications'))
  await page.getByTestId('parking-apply-submit').click()
  const status = (await resp).status()
  note('PK-05', `重複申請 POST -> ${status}`)
  expect(status).toBe(409)
  await expect(page.getByText('この区画には既に申請済みです')).toBeVisible({ timeout: 10_000 })
  await shot(page, 'PK-05-duplicate')
  // フォームが壊れていない: ダイアログが生きており、別の区画を選んで送信できる
  await expect(page.getByTestId('parking-apply-dialog')).toBeVisible()
  await page.getByTestId('parking-apply-space').click()
  await page.getByRole('option', { name: /^P-03/ }).click()
  const resp2 = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/parking/applications'))
  await page.getByTestId('parking-apply-submit').click()
  expect((await resp2).status()).toBe(201)
  await expect(page.getByTestId('parking-application')).toHaveCount(2)
  await context.close()
})

test('PK-06 SUPPORTER は申請ボタンが出ず API 直打ちも 403', async ({ browser }) => {
  const { context, page } = await openAs(browser, supporter, `/teams/${team.slug}/parking`)
  await expect(page.getByText('区画一覧', { exact: true })).toBeVisible({ timeout: 60_000 })
  await expect(page.getByTestId('parking-apply-open')).toHaveCount(0)
  await shot(page, 'PK-06a-supporter-no-button')
  const r = await context.request.post(`${API}${T()}/applications`, {
    data: { spaceId: spaceIds['P-20'], vehicleId: vehicleOf.get('SUPP') },
  })
  note('PK-06', `SUPPORTER POST 申請 -> ${r.status()} ${(await r.text()).slice(0, 160)}`)
  expect(r.status()).toBe(403)
  await context.close()
})

test('PK-07 別チームのユーザー X は駐車場ページ・申請一覧 API を弾かれる', async ({ browser }) => {
  const { context, page } = await openAs(browser, outsider, `/teams/${team.slug}/parking`)
  await page.waitForTimeout(5_000)
  note('PK-07', `X の駐車場ページ URL=${page.url()} 申請ボタン=${await page.getByTestId('parking-apply-open').count()}`)
  await shot(page, 'PK-07a-outsider-page')
  await expect(page.getByTestId('parking-apply-open')).toHaveCount(0)
  await expect(page.getByTestId('parking-application')).toHaveCount(0)
  const body = await page.locator('body').innerText()
  note('PK-07', `画面本文(先頭200字)=${body.replace(/\s+/g, ' ').slice(0, 200)}`)
  await page.goto(`${API}${T()}/applications`)
  const text = await page.locator('body').innerText()
  note('PK-07', `X の API 直打ち GET 申請一覧 本文=${text.slice(0, 200)}`)
  await shot(page, 'PK-07b-outsider-api-direct')
  expect(text).not.toContain('"total"')
  const r = await context.request.get(`${API}${T()}/applications`)
  expect([403, 404]).toContain(r.status())
  note('PK-07', `status=${r.status()}`)
  await context.close()
})

test('PK-08 車両未登録ユーザーがフォーム内の車両登録から申請完了まで通る', async ({ browser }) => {
  const { context, page } = await openAs(browser, memC, `/teams/${team.slug}/parking`)
  await expect(page.getByTestId('parking-apply-open')).toBeVisible({ timeout: 60_000 })
  await applyDialogOpen(page)
  await expect(page.getByTestId('parking-no-vehicle')).toBeVisible()
  await shot(page, 'PK-08a-no-vehicle')
  await page.getByTestId('parking-register-vehicle-open').click()
  await page.getByTestId('parking-vehicle-plate').fill(`NEW-${Date.now().toString(36).slice(-5)}`)
  const reg = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/users/me/vehicles'))
  await page.getByTestId('parking-register-vehicle-save').click()
  expect((await reg).status()).toBe(201)
  await expect(page.getByTestId('parking-apply-vehicle')).toBeVisible()
  await page.getByTestId('parking-apply-space').click()
  await page.getByRole('option', { name: /^P-04/ }).click()
  const resp = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/parking/applications'))
  await page.getByTestId('parking-apply-submit').click()
  expect((await resp).status()).toBe(201)
  await expect(page.getByTestId('parking-application')).toHaveCount(1)
  await shot(page, 'PK-08b-applied')
  await context.close()
})

test('PK-09 言語 en で駐車場ページに日本語・生キーが出ない', async ({ browser }) => {
  // 前提: アカウント言語を en にする（FE は設定画面ではなくアカウント言語・Cookie で表示言語を決める）
  sql(`UPDATE users SET locale='en' WHERE email='${memA.email}';`)
  const { context, page } = await openAs(browser, memA, `/teams/${team.slug}/parking`)
  try {
    await expect(page.getByTestId('parking-apply-open')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('parking-apply-open')).toHaveText(/Apply/i)
    await shot(page, 'PK-09a-en-page')
    // 駐車場ページ本体（共通レイアウトの nav は対象外）
    const pageText = await page
      .getByTestId('parking-apply-open')
      .evaluate((el) => (el.closest('.space-y-4') as HTMLElement).innerText)
    await page.getByTestId('parking-apply-open').click()
    await expect(page.getByTestId('parking-apply-dialog')).toBeVisible()
    await page.getByTestId('parking-register-vehicle-open').click()
    await shot(page, 'PK-09b-en-dialog')
    const dialogText = await page.getByTestId('parking-apply-dialog').innerText()
    // 申請取消の確認ダイアログも英語か
    await page.getByTestId('parking-apply-dialog').getByRole('button', { name: /close/i }).first().click()
    const texts = `${pageText}\n${dialogText}`
    const jp = texts.match(/[ぁ-んァ-ヶ一-龠]+/g) ?? []
    const rawKeys = texts.match(/\bparking\.[a-zA-Z.]+/g) ?? []
    note('PK-09', `en 本文の日本語断片=${JSON.stringify(jp)} 生キー=${JSON.stringify(rawKeys)}`)
    expect(rawKeys).toEqual([])
    expect(jp).toEqual([])
  } finally {
    await context.close()
  }
})

test('PK-10 MEMBER の approve 直打ちは存在する ID も存在しない ID も同一の 403', async ({ browser }) => {
  const { context } = await openAs(browser, memB, `/teams/${team.slug}/parking`)
  const real = await context.request.patch(`${API}${T()}/applications/${appAdmin}/approve`)
  const ghost = await context.request.patch(`${API}${T()}/applications/99999999/approve`)
  const rt = await real.text()
  const gt = await ghost.text()
  note('PK-10', `存在ID ${appAdmin} -> ${real.status()} ${rt.slice(0, 140)} / 不存在 -> ${ghost.status()} ${gt.slice(0, 140)}`)
  expect(real.status()).toBe(403)
  expect(ghost.status()).toBe(403)
  const strip = (s: string) => s.replace(/"(timestamp|path|traceId|requestId)":"[^"]*"/g, '')
  expect(strip(gt)).toBe(strip(rt))
  expect(sql(`SELECT status FROM parking_applications WHERE id=${appAdmin};`)).toBe('PENDING')
  await context.close()
})

test('PK-ORG 組織スコープ: 本人分のみ／ADMIN は全件／申請と取消', async ({ browser }) => {
  // memA（org）が画面で申請
  const a = await openAs(browser, orgMemA, `/organizations/${org.slug}/parking`)
  await expect(a.page.getByTestId('parking-apply-open')).toBeVisible({ timeout: 60_000 })
  await applyDialogOpen(a.page)
  await a.page.getByTestId('parking-apply-space').click()
  await a.page.getByRole('option', { name: /^O-01/ }).click()
  const r1 = a.page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/parking/applications'))
  await a.page.getByTestId('parking-apply-submit').click()
  expect((await r1).status()).toBe(201)
  await expect(a.page.getByTestId('parking-application')).toHaveCount(1)
  await shot(a.page, 'PKORG-01-memA-applied')
  await a.context.close()

  // memB（org）には A の申請が出ない（画面＋API 直打ち）
  const b = await openAs(browser, orgMemB, `/organizations/${org.slug}/parking`)
  await expect(b.page.getByTestId('parking-apply-open')).toBeVisible({ timeout: 60_000 })
  await expect(b.page.getByTestId('parking-application')).toHaveCount(0)
  await b.page.goto(`${API}${O()}/applications?page=0&size=100`)
  const json = JSON.parse(await b.page.locator('body').innerText())
  note('PKORG', `orgMemB 直打ち total=${json.meta?.total}`)
  expect(json.meta.total).toBe(0)
  await shot(b.page, 'PKORG-02-memB-api-direct')
  await b.context.close()

  // 組織 ADMIN は全件
  const ad = await openAs(browser, orgAdmin, `/organizations/${org.slug}/parking`)
  await expect(ad.page.getByTestId('parking-application')).toHaveCount(1, { timeout: 60_000 })
  await expect(ad.page.getByText('申請一覧', { exact: true })).toBeVisible()
  await shot(ad.page, 'PKORG-03-admin-all')
  await ad.context.close()

  // orgMemA が取消
  const c = await openAs(browser, orgMemA, `/organizations/${org.slug}/parking`)
  await expect(c.page.getByTestId('parking-application')).toHaveCount(1, { timeout: 60_000 })
  await c.page.getByTestId('parking-application-cancel').click()
  const dialog = c.page.getByRole('alertdialog').or(c.page.getByRole('dialog')).filter({ hasText: '申請の取消' })
  await dialog.getByRole('button', { name: '取り消す' }).click()
  await expect(c.page.getByTestId('parking-application-status').first()).toHaveText('取消済み', { timeout: 30_000 })
  await shot(c.page, 'PKORG-04-cancelled')
  await c.context.close()
  void apiAs
  void appDepPerm
  void appDepNo
})
