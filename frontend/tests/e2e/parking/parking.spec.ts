import { test, expect, type Page, type Route } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { setupAuth } from '../helpers/action-memo-mocks'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from '../teams/helpers'

/**
 * F09.3 駐車場（区画一覧・利用申請）E2E テスト
 *
 * テストID: PARKING-001〜009
 *
 * 方針:
 * - API モックは BE の実レスポンス形に合わせる
 *   一覧: `{ data, meta: { total, page(0始まり), size, totalPages } }`
 *   区画: SpaceResponse（status は VACANT/OCCUPIED/MAINTENANCE、applicationStatus、spaceType 等）
 * - 管理者向けの承認・却下・抽選の画面は今回の範囲外（台帳 CMP-261124-2331）
 *
 * 仕様書: docs/features/F09.3_parking.md
 */

const ORG_ID = 1
const ME = 100

const SPACE_A = {
  id: 1,
  scopeType: 'TEAM',
  scopeId: TEAM_ID,
  spaceNumber: 'A-001',
  spaceType: 'INDOOR',
  status: 'VACANT',
  applicationStatus: 'ACCEPTING',
  allocationMethod: 'FIRST_COME',
  pricePerMonth: 5000,
}
const SPACE_B = { ...SPACE_A, id: 2, spaceNumber: 'A-002', status: 'OCCUPIED', applicationStatus: 'NOT_ACCEPTING' }
const SPACE_VISITOR = { ...SPACE_A, id: 3, spaceNumber: 'V-001', spaceType: 'VISITOR' }
// 2 ページ目にだけある受付中区画（全ページ取得しないと申請フォームに出ない）
const SPACE_PAGE2 = { ...SPACE_A, id: 4, spaceNumber: 'Z-099' }

const VEHICLE = { id: 5, userId: ME, vehicleType: 'CAR', plateNumber: '品川 500 あ 1234', nickname: null }

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

function paged<T>(data: T[], page = 0, totalPages = 1) {
  return { data, meta: { total: data.length, page, size: 100, totalPages } }
}

async function asRole(page: Page, role: 'MEMBER' | 'ADMIN', permissions: string[] = []) {
  await setupAuth(page, { userId: ME, displayName: 'テスト ユーザー', role: role === 'ADMIN' ? 'ADMIN' : 'MEMBER' })
  await mockTeam(page)
  await mockTeamFeatureApis(page)
  await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, (route) =>
    json(route, { data: { roleName: role, permissions } }),
  )
}

/** 区画（2 ページ）・車両・自分の申請（可変）をモックする。 */
async function mockParking(page: Page, opts: { applications?: unknown[] } = {}) {
  const state = {
    applications: opts.applications ?? [],
    posted: [] as unknown[],
    deleted: [] as string[],
    appListCalls: 0,
  }
  await page.route(`**/api/v1/teams/${TEAM_ID}/parking/spaces*`, (route) => {
    const page0 = new URL(route.request().url()).searchParams.get('page') ?? '0'
    return json(
      route,
      page0 === '0'
        ? paged([SPACE_A, SPACE_B, SPACE_VISITOR], 0, 2)
        : paged([SPACE_PAGE2], 1, 2),
    )
  })
  await page.route('**/api/v1/users/me/vehicles', (route) => json(route, { data: [VEHICLE] }))
  await page.route(`**/api/v1/teams/${TEAM_ID}/parking/applications*`, async (route) => {
    const req = route.request()
    if (req.method() === 'POST') {
      state.posted.push(req.postDataJSON())
      const created = { id: 77, spaceId: 1, userId: ME, vehicleId: 5, status: 'PENDING', createdAt: '2026-11-24T09:00:00' }
      state.applications = [created, ...state.applications]
      return json(route, { data: created }, 201)
    }
    state.appListCalls++
    return json(route, paged(state.applications as unknown[]))
  })
  return state
}

test.describe('PARKING: F09.3 駐車場', () => {
  test('PARKING-001: チーム駐車場ページが表示される', async ({ page }) => {
    await asRole(page, 'MEMBER')
    await mockParking(page)
    await page.goto(`/teams/${TEAM_ID}/parking`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '駐車場管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('PARKING-002: 組織駐車場ページが表示される', async ({ page }) => {
    await setupAuth(page, { userId: ME, displayName: 'テスト ユーザー', role: 'MEMBER' })
    await page.route(`**/api/v1/organizations/${ORG_ID}`, (route) =>
      json(route, { data: { id: ORG_ID, name: 'テスト組織', description: 'E2Eテスト用組織' } }),
    )
    await page.route(`**/api/v1/organizations/${ORG_ID}/**`, (route) => json(route, paged([])))
    await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, (route) =>
      json(route, { data: { roleName: 'MEMBER', permissions: [] } }),
    )
    await page.route('**/api/v1/users/me/vehicles', (route) => json(route, { data: [] }))
    await page.goto(`/organizations/${ORG_ID}/parking`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '駐車場管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('PARKING-003: 区画一覧を表示する（BE の実形・状態の日本語表示）', async ({ page }) => {
    await asRole(page, 'MEMBER')
    await mockParking(page)
    await page.goto(`/teams/${TEAM_ID}/parking`)
    await waitForHydration(page)
    await expect(page.getByText('A-001')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('A-002')).toBeVisible()
    await expect(page.getByText('Z-099')).toBeVisible()
    await expect(page.getByText('使用中')).toBeVisible()
    // 生のコード値（VACANT 等）を画面に出さない
    await expect(page.getByText('VACANT', { exact: true })).toHaveCount(0)
  })

  test('PARKING-004: 「区画を追加」は管理者にだけ出る', async ({ page }) => {
    await asRole(page, 'MEMBER')
    await mockParking(page)
    await page.goto(`/teams/${TEAM_ID}/parking`)
    await waitForHydration(page)
    await expect(page.getByText('A-001')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: '区画を追加' })).toHaveCount(0)
  })

  test('PARKING-005: ADMIN には「区画を追加」が出る', async ({ page }) => {
    await asRole(page, 'ADMIN')
    await mockParking(page)
    await page.goto(`/teams/${TEAM_ID}/parking`)
    await waitForHydration(page)
    await expect(page.getByRole('button', { name: '区画を追加' })).toBeVisible({ timeout: 10_000 })
  })

  test('PARKING-006: MEMBER が区画と車両を選んで申請でき、申請一覧に反映される', async ({ page }) => {
    await asRole(page, 'MEMBER')
    const state = await mockParking(page)
    await page.goto(`/teams/${TEAM_ID}/parking`)
    await waitForHydration(page)

    await page.getByTestId('parking-apply-open').click()
    const dialog = page.getByTestId('parking-apply-dialog')
    await expect(dialog).toBeVisible()

    // 受付中かつ来場者用でない区画だけが選べる（2 ページ目の Z-099 も含む）
    await dialog.getByTestId('parking-apply-space').click()
    await expect(page.getByRole('option', { name: /A-001/ })).toBeVisible()
    await expect(page.getByRole('option', { name: /Z-099/ })).toBeVisible()
    await expect(page.getByRole('option', { name: /A-002/ })).toHaveCount(0)
    await expect(page.getByRole('option', { name: /V-001/ })).toHaveCount(0)
    await page.getByRole('option', { name: /A-001/ }).click()

    await dialog.getByTestId('parking-apply-message').fill('送迎で必要です')
    await dialog.getByTestId('parking-apply-submit').click()

    await expect(dialog).toBeHidden({ timeout: 10_000 })
    expect(state.posted).toEqual([{ spaceId: 1, vehicleId: 5, message: '送迎で必要です' }])
    await expect(page.getByTestId('parking-application')).toHaveCount(1)
    await expect(page.getByTestId('parking-application-status')).toHaveText('審査待ち')
  })

  test('PARKING-007: 申請が 409（申請済み）なら文言を出し、フォームと一覧を保つ', async ({ page }) => {
    await asRole(page, 'MEMBER')
    await mockParking(page)
    await page.route(`**/api/v1/teams/${TEAM_ID}/parking/applications`, async (route) => {
      if (route.request().method() === 'POST') {
        return json(route, { error: { code: 'PARKING_014', message: 'x' } }, 409)
      }
      return json(route, paged([]))
    })
    await page.goto(`/teams/${TEAM_ID}/parking`)
    await waitForHydration(page)
    await page.getByTestId('parking-apply-open').click()
    const dialog = page.getByTestId('parking-apply-dialog')
    await dialog.getByTestId('parking-apply-space').click()
    await page.getByRole('option', { name: /A-001/ }).click()
    await dialog.getByTestId('parking-apply-submit').click()

    await expect(page.getByText('この区画には既に申請済みです')).toBeVisible({ timeout: 10_000 })
    await expect(dialog).toBeVisible()
  })

  test('PARKING-008: 審査待ちの申請を確認ダイアログのうえ取り消せる', async ({ page }) => {
    await asRole(page, 'MEMBER')
    const state = await mockParking(page, {
      applications: [
        { id: 10, spaceId: 1, userId: ME, vehicleId: 5, status: 'PENDING', createdAt: '2026-11-20T09:00:00' },
        { id: 11, spaceId: 2, userId: ME, vehicleId: 5, status: 'APPROVED', createdAt: '2026-11-10T09:00:00' },
      ],
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/parking/applications/10`, async (route) => {
      state.deleted.push(route.request().method())
      state.applications = state.applications.filter((a) => (a as { id: number }).id !== 10)
      return route.fulfill({ status: 204, body: '' })
    })
    await page.goto(`/teams/${TEAM_ID}/parking`)
    await waitForHydration(page)

    // 決定済み（APPROVED）には取消が出ない
    await expect(page.getByTestId('parking-application')).toHaveCount(2, { timeout: 10_000 })
    await expect(page.getByTestId('parking-application-cancel')).toHaveCount(1)

    await page.getByTestId('parking-application-cancel').click()
    await page.getByRole('button', { name: '取り消す' }).last().click()

    await expect(page.getByTestId('parking-application')).toHaveCount(1, { timeout: 10_000 })
    expect(state.deleted).toEqual(['DELETE'])
  })

  test('PARKING-009: 取消が 409（決定済み）なら文言を出し、一覧を取り直す', async ({ page }) => {
    await asRole(page, 'MEMBER')
    const state = await mockParking(page, {
      applications: [{ id: 10, spaceId: 1, userId: ME, vehicleId: 5, status: 'PENDING', createdAt: '2026-11-20T09:00:00' }],
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/parking/applications/10`, async (route) => {
      // 別端末で承認された、という状況
      state.applications = [{ ...(state.applications[0] as object), status: 'APPROVED' }]
      return json(route, { error: { code: 'PARKING_015', message: 'x' } }, 409)
    })
    await page.goto(`/teams/${TEAM_ID}/parking`)
    await waitForHydration(page)
    await page.getByTestId('parking-application-cancel').click()
    await page.getByRole('button', { name: '取り消す' }).last().click()

    await expect(page.getByText('この申請は既に決定済みのため操作できません')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('parking-application-status')).toHaveText('承認済み')
    await expect(page.getByTestId('parking-application-cancel')).toHaveCount(0)
  })
})
