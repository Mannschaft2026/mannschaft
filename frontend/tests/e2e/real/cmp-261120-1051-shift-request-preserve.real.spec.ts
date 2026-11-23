/**
 * 実機E2E: /my/shift-request が既存の提出済み希望を初期値として表示し、
 * 未変更の既存希望を再送（上書き）しない（CMP-261120-1051）
 *
 * 受け入れ条件:
 *   AC1 既存の提出済み希望が初期値として画面に表示される
 *   AC2 一部の日だけ変更して送信すると、変更した日だけ PATCH され、
 *       触っていない既存希望は再送されず、別経路で更新された値が保持される
 *   AC3 作成→同データ保持で更新（保存後に開き直し、何も変えずに送信しても書き込みが発生しない）
 *   AC4 ロール横断: 同チームの別メンバー・チーム管理者・他チーム（非所属）ユーザーに
 *       他人の希望が初期値として出ない／シフト表が見えない／URL 直打ちで操作できない
 *
 * API の用途: ログイン・前提データ作成・「別経路での更新」の再現・永続化の読み取り検証・後始末のみ。
 * 対象操作（初期表示の確認・希望の変更・提出）はすべて画面で行う。
 * 前提データ（ポジション・シフト表・枠）は e2e-admin の API で作り、afterAll で自分が作った分だけ消す。
 */
import {
  test as base,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const BE_API = `${API_BASE_URL}/api/v1`
const PASSWORD = 'TestPass2026!'

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
/** fc-u-18 の素の ADMIN */
const TEAM_ADMIN_EMAIL = 'e2e-dummy-1@test.mannschaft.local'
/** 提出者（fc-u-18 の MEMBER） */
const MEMBER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
/** 同チームの別メンバー */
const OTHER_MEMBER_EMAIL = 'e2e-dummy-2@test.mannschaft.local'
/** どのチームにも非所属 */
const OUTSIDER_EMAIL = 'e2e-outsider@test.mannschaft.local'

const TEAM_SLUG = 'fc-u-18'
const RUN_TAG = `R${Date.now().toString(36)}`
const TITLE = `希望保持1051_${RUN_TAG}`
const POSITION_NAME = `希望保持1051_${RUN_TAG}`
const NOTE_1 = `夜勤可_${RUN_TAG}`

type Pref = 'PREFERRED' | 'AVAILABLE' | 'WEAK_REST' | 'STRONG_REST' | 'ABSOLUTE_REST'
const LABEL: Record<Pref, string> = {
  PREFERRED: '出勤希望',
  AVAILABLE: '指定なし',
  WEAK_REST: '出れなくはない',
  STRONG_REST: 'できれば休み',
  ABSOLUTE_REST: '絶対休み',
}

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

// ログインは email+IP で 1 分 10 回までのレート制限があり、他の実機E2Eとも共有されるため、
// トークンは email ごとに 1 回だけ取得して使い回し、429 のときは待って再試行する。
const tokenCache = new Map<string, string>()
async function login(ctx: APIRequestContext, email: string): Promise<string> {
  const cached = tokenCache.get(email)
  if (cached) return cached
  for (let attempt = 0; attempt < 8; attempt++) {
    const res = await ctx.post(`${BE_API}/auth/login`, {
      headers: { 'Content-Type': 'application/json' },
      data: { email, password: PASSWORD },
    })
    if (res.status() === 429) {
      await new Promise((r) => setTimeout(r, 15_000))
      continue
    }
    if (!res.ok()) throw new Error(`ログイン失敗(${email}): ${res.status()} ${await res.text()}`)
    const token = (await res.json()).data.accessToken as string
    tokenCache.set(email, token)
    return token
  }
  throw new Error(`ログインがレート制限で通らない(${email})`)
}

function todayIsoJst(): string {
  return new Date(Date.now() + 9 * 60 * 60 * 1000).toISOString().slice(0, 10)
}
function addDaysIso(baseIso: string, days: number): string {
  const [y, m, d] = baseIso.split('-').map(Number)
  const dt = new Date(Date.UTC(y!, m! - 1, d!))
  dt.setUTCDate(dt.getUTCDate() + days)
  return dt.toISOString().slice(0, 10)
}

interface MyRequest {
  id: number
  scheduleId: number
  slotId: number | null
  preference: Pref
  note: string | null
}

let apiCtx: APIRequestContext
let adminToken: string
let memberCtx: APIRequestContext
let memberToken: string
let teamName: string
let teamNumericId: number
let positionId: number
let scheduleId: number
const slotIds: number[] = []
let dates: string[] = []
const existingIds: Record<'s1' | 's2', number> = { s1: 0, s2: 0 }

base.use({ storageState: { cookies: [], origins: [] } })
const test = base
test.describe.configure({ mode: 'serial' })
test.setTimeout(180_000)

const sessionCache = new Map<string, Awaited<ReturnType<BrowserContext['storageState']>>>()
async function openAs(browser: Browser, email: string): Promise<Page> {
  const saved = sessionCache.get(email)
  const context = await browser.newContext({
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    ...(saved ? { storageState: saved } : {}),
  })
  const page = await context.newPage()
  if (!saved) {
    for (let attempt = 0; attempt < 8; attempt++) {
      try {
        await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE_URL })
        break
      } catch (e) {
        if (attempt === 7 || !String(e).includes('429')) throw e
        await new Promise((r) => setTimeout(r, 15_000))
      }
    }
    sessionCache.set(email, await context.storageState())
  }
  return page
}

async function myRequestsOfSchedule(): Promise<MyRequest[]> {
  const res = await memberCtx.get(`${BE_API}/shifts/my/requests`, {
    headers: authHeaders(memberToken),
  })
  expect(res.status()).toBe(200)
  const all = (await res.json()).data as MyRequest[]
  return all.filter((r) => r.scheduleId === scheduleId)
}

test.beforeAll(async () => {
  apiCtx = await playwrightRequest.newContext()
  adminToken = await login(apiCtx, ADMIN_EMAIL)
  memberCtx = await playwrightRequest.newContext()
  memberToken = await login(memberCtx, MEMBER_EMAIL)

  const teamRes = await apiCtx.get(`${BE_API}/teams/${TEAM_SLUG}`, { headers: authHeaders(adminToken) })
  expect(teamRes.status(), 'チーム取得').toBe(200)
  const team = (await teamRes.json()).data as {
    numericId: number
    basicInfo?: { name?: string }
    name?: string
  }
  teamNumericId = team.numericId
  teamName = team.basicInfo?.name ?? team.name!

  // 役者の実効ロールを実測で裏取りする
  for (const [email, expected] of [
    [TEAM_ADMIN_EMAIL, 'ADMIN'],
    [MEMBER_EMAIL, 'MEMBER'],
    [OTHER_MEMBER_EMAIL, 'MEMBER'],
  ] as const) {
    const ctx = await playwrightRequest.newContext()
    const token = await login(ctx, email)
    const res = await ctx.get(`${BE_API}/teams/${TEAM_SLUG}/me/permissions`, {
      headers: authHeaders(token),
    })
    expect(res.status(), `${email} 権限取得`).toBe(200)
    expect(((await res.json()).data as { roleName: string }).roleName, `${email} の実効ロール`).toBe(expected)
    await ctx.dispose()
  }

  // 前回中断の残骸（本 spec の接頭辞のみ）を掃除
  const listRes = await apiCtx.get(`${BE_API}/shifts/schedules?teamId=${teamNumericId}`, {
    headers: authHeaders(adminToken),
  })
  for (const s of ((await listRes.json()).data ?? []) as Array<{ id: number; content: { title: string } }>) {
    if (s.content?.title?.startsWith('希望保持1051_')) {
      await apiCtx.delete(`${BE_API}/shifts/schedules/${s.id}`, { headers: authHeaders(adminToken) })
    }
  }

  const posRes = await apiCtx.post(`${BE_API}/shifts/positions?teamId=${teamNumericId}`, {
    headers: authHeaders(adminToken),
    data: { name: POSITION_NAME, displayOrder: 1 },
  })
  expect(posRes.status(), `ポジション作成: ${await posRes.text()}`).toBeLessThan(300)
  positionId = ((await posRes.json()).data as { id: number }).id

  const start = addDaysIso(todayIsoJst(), 3)
  dates = [start, addDaysIso(start, 1), addDaysIso(start, 2)]
  const schRes = await apiCtx.post(`${BE_API}/shifts/schedules?teamId=${teamNumericId}`, {
    headers: authHeaders(adminToken),
    data: { title: TITLE, startDate: dates[0], endDate: dates[2] },
  })
  expect(schRes.status(), `シフト表作成: ${await schRes.text()}`).toBeLessThan(300)
  scheduleId = ((await schRes.json()).data as { id: number }).id

  for (const d of dates) {
    const r = await apiCtx.post(`${BE_API}/shifts/schedules/${scheduleId}/slots`, {
      headers: authHeaders(adminToken),
      data: { slotDate: d, startTime: '09:00:00', endTime: '12:00:00', positionId, requiredCount: 2, note: null },
    })
    expect(r.status(), `枠作成: ${await r.text()}`).toBeLessThan(300)
    slotIds.push(((await r.json()).data as { id: number }).id)
  }
  // 画面が使う枠の日付は一覧 API の値。作成レスポンスの値と一致しない環境があるため、一覧から取り直す。
  const listSlots = await apiCtx.get(`${BE_API}/shifts/schedules/${scheduleId}/slots`, {
    headers: authHeaders(adminToken),
  })
  const listed = ((await listSlots.json()).data as Array<{ id: number; time: { slotDate: string } }>)
  const dateById = new Map(listed.map((x) => [x.id, x.time.slotDate]))
  console.log(`[FIXTURE] 作成した日付=${dates} 一覧の日付=${slotIds.map((i) => dateById.get(i))}`)
  dates = slotIds.map((i) => dateById.get(i)!)
  const tr = await apiCtx.post(`${BE_API}/shifts/schedules/${scheduleId}/transition?status=COLLECTING`, {
    headers: authHeaders(adminToken),
  })
  expect(tr.status(), `COLLECTING 遷移: ${await tr.text()}`).toBeLessThan(300)

  // 前提: 提出者の既存希望（枠1=出勤希望+メモ、枠2=指定なし）。枠3は未提出。
  for (const [key, idx, pref, note] of [
    ['s1', 0, 'PREFERRED', NOTE_1],
    ['s2', 1, 'AVAILABLE', null],
  ] as const) {
    const r = await memberCtx.post(`${BE_API}/shifts/requests`, {
      headers: authHeaders(memberToken),
      data: { scheduleId, slotId: slotIds[idx], slotDate: dates[idx], preference: pref, note },
    })
    expect(r.status(), `既存希望作成(${key} slot=${slotIds[idx]} date=${dates[idx]} slots=${slotIds} dates=${dates}): ${await r.text()}`).toBeLessThan(300)
    existingIds[key] = ((await r.json()).data as { id: number }).id
  }
  console.log(`[FIXTURE] team=${teamNumericId} schedule=${scheduleId} slots=${slotIds} req=${JSON.stringify(existingIds)}`)
})

test.afterAll(async () => {
  if (scheduleId) {
    // 希望はシフト表の削除に連なるが、念のため自分が作った希望を先に消す
    try {
      for (const r of await myRequestsOfSchedule()) {
        await memberCtx.delete(`${BE_API}/shifts/requests/${r.id}`, { headers: authHeaders(memberToken) })
      }
    } catch (e) {
      console.log(`[CLEANUP] 希望の個別削除に失敗（シフト表削除で連なる）: ${String(e)}`)
    }
    const d = await apiCtx.delete(`${BE_API}/shifts/schedules/${scheduleId}`, { headers: authHeaders(adminToken) })
    console.log(`[CLEANUP] DELETE schedule ${scheduleId} -> ${d.status()}`)
  }
  if (positionId) {
    const d = await apiCtx.delete(`${BE_API}/shifts/positions/${positionId}`, { headers: authHeaders(adminToken) })
    console.log(`[CLEANUP] DELETE position ${positionId} -> ${d.status()}`)
  }
  await memberCtx?.dispose()
  await apiCtx?.dispose()
})

/** /my/shift-request を開き、チーム選択→シフト表選択まで画面で進む。 */
async function gotoSlotFill(page: Page): Promise<void> {
  await page.goto('/my/shift-request')
  await waitForHydration(page)
  const teamCard = page.getByText(teamName, { exact: true }).first()
  if (await teamCard.isVisible({ timeout: 20_000 }).catch(() => false)) {
    await expect(async () => {
      if (await teamCard.isVisible()) await teamCard.click()
      await expect(teamCard).toBeHidden({ timeout: 5_000 })
    }).toPass({ timeout: 90_000 })
  }
  const scheduleCard = page.getByText(TITLE).first()
  await expect(scheduleCard).toBeVisible({ timeout: 30_000 })
  await expect(async () => {
    if (await scheduleCard.isVisible()) await scheduleCard.click()
    await expect(page.getByRole('radiogroup').first()).toBeVisible({ timeout: 5_000 })
  }).toPass({ timeout: 60_000 })
  await expect(page.getByRole('radiogroup')).toHaveCount(3)
}

function radio(page: Page, slotIndex: number, pref: Pref) {
  return page.getByRole('radiogroup').nth(slotIndex).getByRole('radio', { name: LABEL[pref] })
}

async function expectChecked(page: Page, slotIndex: number, pref: Pref) {
  await expect(radio(page, slotIndex, pref), `枠${slotIndex + 1} は ${LABEL[pref]}`).toHaveAttribute('aria-checked', 'true')
}

async function submitFromUi(page: Page): Promise<void> {
  await page.getByRole('button', { name: '提出前確認' }).click()
  await page.getByRole('button', { name: '提出', exact: true }).click()
  await expect(page.getByText('この内容で送信しますか？')).toBeVisible({ timeout: 10_000 })
  await page.getByRole('button', { name: '送信する' }).click()
}

interface Write { method: string; url: string; body: unknown }
function recordWrites(page: Page): Write[] {
  const writes: Write[] = []
  page.on('request', (req) => {
    if (req.url().includes('/api/v1/shifts/requests') && ['POST', 'PATCH', 'PUT', 'DELETE'].includes(req.method())) {
      writes.push({ method: req.method(), url: req.url(), body: req.postDataJSON() })
    }
  })
  return writes
}

test('AC1 既存の提出済み希望が初期値として表示される（未提出日は既定の「できれば休み」）', async ({ browser }) => {
  const page = await openAs(browser, MEMBER_EMAIL)
  await gotoSlotFill(page)
  await expectChecked(page, 0, 'PREFERRED')
  await expectChecked(page, 1, 'AVAILABLE')
  await expectChecked(page, 2, 'STRONG_REST')
  // 既存メモも初期値として入っている
  await page.getByRole('button', { name: '希望メモ' }).first().click()
  await expect(page.getByPlaceholder('希望メモ').first()).toHaveValue(NOTE_1)
  await page.screenshot({ path: 'test-results/1051-initial.png', fullPage: true })
  await page.context().close()
})

test('AC2 一部の日だけ変更して送信 → 変更した日だけ更新され、触っていない既存希望は再送されず保持される', async ({ browser }) => {
  const page = await openAs(browser, MEMBER_EMAIL)
  await gotoSlotFill(page)
  const writes = recordWrites(page)
  await expectChecked(page, 1, 'AVAILABLE')

  // 別経路（別端末・別画面）で枠2の希望が更新された状況を再現する（API=前提操作）。
  // 画面は古い値（指定なし）を持ったままなので、未変更の既存希望を再送する実装なら上書きされる。
  const ext = await memberCtx.patch(`${BE_API}/shifts/requests/${existingIds.s2}`, {
    headers: authHeaders(memberToken),
    data: { preference: 'WEAK_REST', note: null },
  })
  expect(ext.status(), `別経路更新: ${await ext.text()}`).toBeLessThan(300)

  // UI: 枠1を「出れなくはない」に変更（既存の変更）、枠3を「指定なし」に変更（新規）。枠2は触らない。
  await radio(page, 0, 'WEAK_REST').click()
  await radio(page, 2, 'AVAILABLE').click()
  await submitFromUi(page)
  await expect(page.getByText('提出しました').or(page.getByText(/提出.*(完了|しました)/)).first()).toBeVisible({ timeout: 15_000 })

  console.log('[WRITES]', JSON.stringify(writes))
  const patches = writes.filter((w) => w.method === 'PATCH')
  const posts = writes.filter((w) => w.method === 'POST')
  expect(patches.map((p) => p.url.split('/').pop()), '更新は変更した枠1の希望だけ').toEqual([String(existingIds.s1)])
  expect(posts.length, '新規作成は未提出の枠3だけ').toBe(1)
  expect(writes.length, '書き込みは PATCH 1 + POST 1 のみ').toBe(2)

  // 永続化の検証（API 読み取り）
  const rows = await myRequestsOfSchedule()
  expect(rows.length).toBe(3)
  const bySlot = new Map(rows.map((r) => [r.slotId, r]))
  expect(bySlot.get(slotIds[0]!)).toMatchObject({ id: existingIds.s1, preference: 'WEAK_REST', note: NOTE_1 })
  expect(bySlot.get(slotIds[1]!), '触っていない既存希望は別経路の値を保持').toMatchObject({
    id: existingIds.s2,
    preference: 'WEAK_REST',
  })
  expect(bySlot.get(slotIds[2]!)).toMatchObject({ preference: 'AVAILABLE' })
  await page.context().close()
})

test('AC3 作成→同データ保持で更新: 開き直すと保存値が初期表示され、何も変えず送信すると書き込みが発生しない', async ({ browser }) => {
  const page = await openAs(browser, MEMBER_EMAIL)
  await gotoSlotFill(page)
  await expectChecked(page, 0, 'WEAK_REST')
  await expectChecked(page, 1, 'WEAK_REST')
  await expectChecked(page, 2, 'AVAILABLE')
  const before = await myRequestsOfSchedule()
  const writes = recordWrites(page)

  await submitFromUi(page)
  await expect(page.getByText('提出しました').or(page.getByText(/提出.*(完了|しました)/)).first()).toBeVisible({ timeout: 15_000 })
  expect(writes, '未変更なら再送しない').toEqual([])

  const after = await myRequestsOfSchedule()
  expect(after.map((r) => [r.id, r.preference, r.note]).sort()).toEqual(
    before.map((r) => [r.id, r.preference, r.note]).sort(),
  )
  await page.context().close()
})

test('AC4-a 同チームの別メンバーには他人の希望が初期値に出ない（自分の既定のまま）', async ({ browser }) => {
  const page = await openAs(browser, OTHER_MEMBER_EMAIL)
  await gotoSlotFill(page)
  for (const i of [0, 1, 2]) await expectChecked(page, i, 'STRONG_REST')
  await page.screenshot({ path: 'test-results/1051-other-member.png', fullPage: true })
  await page.context().close()
})

test('AC4-b チーム管理者（ADMIN）でも他人の希望は初期値に出ない（自分の希望のみ）', async ({ browser }) => {
  const page = await openAs(browser, TEAM_ADMIN_EMAIL)
  await gotoSlotFill(page)
  for (const i of [0, 1, 2]) await expectChecked(page, i, 'STRONG_REST')
  await page.context().close()
})

test('AC4-c 他チーム（非所属）ユーザーには対象チーム・シフト表が /my/shift-request に出ず、詳細 URL 直打ちでも見えない', async ({ browser }) => {
  const page = await openAs(browser, OUTSIDER_EMAIL)
  await page.goto('/my/shift-request')
  await waitForHydration(page)
  await page.waitForLoadState('networkidle')
  await expect(page.getByText(teamName, { exact: true })).toHaveCount(0)
  await expect(page.getByText(TITLE)).toHaveCount(0)
  const listBody = (await page.locator('body').innerText()).replace(/\n+/g, ' / ')
  console.log('[OUTSIDER /my/shift-request]', listBody.slice(0, 300))

  // 詳細 URL 直打ち
  const detailRes = page.waitForResponse((r) => r.url().includes(`/api/v1/shifts/schedules/${scheduleId}`) && r.request().method() === 'GET', { timeout: 30_000 })
  await page.goto(`/shift/${scheduleId}`)
  await waitForHydration(page)
  const res = await detailRes
  await page.waitForLoadState('networkidle')
  const detailBody = (await page.locator('body').innerText()).replace(/\n+/g, ' / ')
  console.log('[OUTSIDER /shift/id] BE status=', res.status(), 'body=', detailBody.slice(0, 300))
  expect(res.status(), 'BE は他チームのシフト表を返さない').toBeGreaterThanOrEqual(403)
  await expect(page.getByText(TITLE)).toHaveCount(0)
  await page.screenshot({ path: 'test-results/1051-outsider-detail.png', fullPage: true })
  await page.context().close()

  // 他チームのユーザーは希望を直接書き込めない（画面の導線が無い場合の BE 側の裏取り。読み取り検証）
  const outCtx = await playwrightRequest.newContext()
  const outToken = await login(outCtx, OUTSIDER_EMAIL)
  const patch = await outCtx.patch(`${BE_API}/shifts/requests/${existingIds.s1}`, {
    headers: authHeaders(outToken),
    data: { preference: 'ABSOLUTE_REST', note: null },
  })
  expect(patch.status(), '他人の希望の PATCH は拒否').toBeGreaterThanOrEqual(400)
  await outCtx.dispose()
  const rows = await myRequestsOfSchedule()
  expect(rows.find((r) => r.id === existingIds.s1)?.preference, '他人の PATCH で変わっていない').toBe('WEAK_REST')
})
