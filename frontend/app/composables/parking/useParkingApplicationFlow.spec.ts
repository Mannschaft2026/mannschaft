import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { isStaleStateError, resolveParkingErrorKey } from './parkingErrors'
import { isCancellableApplication, useParkingApplicationFlow } from './useParkingApplicationFlow'

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))

const notify = { success: vi.fn(), error: vi.fn() }
const handleApiError = vi.fn()
mockNuxtImport('useI18n', () => () => ({ t: (k: string) => k }))
mockNuxtImport('useNotification', () => () => notify)
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))

function apiError(statusCode: number, code?: string) {
  return { statusCode, data: { error: { code } } }
}

describe('parkingErrors', () => {
  it('errorCode を優先して i18n キーへ解決する', () => {
    expect(resolveParkingErrorKey(apiError(404, 'PARKING_002'))).toBe('parking.errors.vehicleNotFound')
    expect(resolveParkingErrorKey(apiError(409, 'PARKING_015'))).toBe('parking.errors.invalidStatus')
    expect(resolveParkingErrorKey(apiError(409, 'PARKING_014'))).toBe('parking.errors.alreadyApplied')
  })
  it('未知コードは HTTP ステータスで解決する', () => {
    expect(resolveParkingErrorKey(apiError(403, 'COMMON_002'))).toBe('parking.errors.forbidden')
    expect(resolveParkingErrorKey(apiError(409))).toBe('parking.errors.conflict')
    expect(resolveParkingErrorKey(apiError(422))).toBe('parking.errors.unprocessable')
    expect(resolveParkingErrorKey(apiError(404))).toBe('parking.errors.notFound')
  })
  it('5xx やネットワークエラーは null（共通ハンドラへ委ねる）', () => {
    expect(resolveParkingErrorKey(apiError(500))).toBeNull()
    expect(resolveParkingErrorKey(new Error('x'))).toBeNull()
  })
  it('404/409/422 は状態が古いエラー', () => {
    expect(isStaleStateError(apiError(409))).toBe(true)
    expect(isStaleStateError(apiError(403))).toBe(false)
  })
})

describe('isCancellableApplication', () => {
  it('PENDING / LOTTERY_PENDING のみ取消できる', () => {
    expect(isCancellableApplication({ status: 'PENDING' })).toBe(true)
    expect(isCancellableApplication({ status: 'LOTTERY_PENDING' })).toBe(true)
    expect(isCancellableApplication({ status: 'APPROVED' })).toBe(false)
    expect(isCancellableApplication({ status: 'REJECTED' })).toBe(false)
    expect(isCancellableApplication({ status: 'CANCELLED' })).toBe(false)
  })
})

describe('useParkingApplicationFlow', () => {
  beforeEach(() => {
    mockApi.mockReset()
    notify.success.mockReset()
    notify.error.mockReset()
    handleApiError.mockReset()
  })

  it('一覧は page 0 始まり・size 付きで取得し meta.total を保持する', async () => {
    mockApi.mockResolvedValueOnce({ data: [{ id: 1, status: 'PENDING' }], meta: { total: 11 } })
    const flow = useParkingApplicationFlow('team', '5')
    await flow.loadApplications(0)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/teams/5/parking/applications?page=0&size=10')
    expect(flow.total.value).toBe(11)
    expect(flow.applications.value).toHaveLength(1)
  })

  it('一覧の取得失敗は loadError に残し、表示中の一覧は消さない', async () => {
    mockApi.mockResolvedValueOnce({ data: [{ id: 1 }], meta: { total: 1 } })
    const flow = useParkingApplicationFlow('team', '5')
    await flow.loadApplications(0)
    mockApi.mockRejectedValueOnce(apiError(500))
    await flow.loadApplications(0)
    expect(flow.loadError.value).toBeTruthy()
    expect(flow.applications.value).toHaveLength(1)
  })

  it('申請成功: POST して成功通知し、一覧を先頭ページで取り直す', async () => {
    mockApi
      .mockResolvedValueOnce({ data: { id: 9, status: 'PENDING' } })
      .mockResolvedValueOnce({ data: [{ id: 9 }], meta: { total: 1 } })
    const flow = useParkingApplicationFlow('organization', '7')
    const ok = await flow.submit({ spaceId: 2, vehicleId: 3, message: 'hi' })
    expect(ok).toBe(true)
    expect(mockApi.mock.calls[0]).toEqual([
      '/api/v1/organizations/7/parking/applications',
      { method: 'POST', body: { spaceId: 2, vehicleId: 3, message: 'hi' } },
    ])
    expect(notify.success).toHaveBeenCalledWith('parking.apply.success')
  })

  it('抽選区画の申請は抽選用の成功文言', async () => {
    mockApi
      .mockResolvedValueOnce({ data: { id: 9, status: 'LOTTERY_PENDING' } })
      .mockResolvedValueOnce({ data: [], meta: { total: 0 } })
    const flow = useParkingApplicationFlow('team', '5')
    await flow.submit({ spaceId: 2, vehicleId: 3 })
    expect(notify.success).toHaveBeenCalledWith('parking.apply.successLottery')
  })

  it('申請 409: i18n 文言で通知し、false を返し、区画の取り直しを促す。一覧は保持', async () => {
    const onStale = vi.fn()
    mockApi.mockResolvedValueOnce({ data: [{ id: 1 }], meta: { total: 1 } })
    const flow = useParkingApplicationFlow('team', '5', { onStale })
    await flow.loadApplications(0)
    mockApi.mockRejectedValueOnce(apiError(409, 'PARKING_014'))
    const ok = await flow.submit({ spaceId: 2, vehicleId: 3 })
    expect(ok).toBe(false)
    expect(notify.error).toHaveBeenCalledWith('dialog.error', 'parking.errors.alreadyApplied')
    expect(onStale).toHaveBeenCalledTimes(1)
    expect(flow.applications.value).toHaveLength(1)
    expect(flow.submitting.value).toBe(false)
  })

  it('申請 404（他人の車両）/ 403 / 422 も i18n 文言で通知', async () => {
    const flow = useParkingApplicationFlow('team', '5')
    mockApi.mockRejectedValueOnce(apiError(404, 'PARKING_002'))
    await flow.submit({ spaceId: 1, vehicleId: 1 })
    mockApi.mockRejectedValueOnce(apiError(403, 'COMMON_002'))
    await flow.submit({ spaceId: 1, vehicleId: 1 })
    mockApi.mockRejectedValueOnce(apiError(422, 'PARKING_013'))
    await flow.submit({ spaceId: 1, vehicleId: 1 })
    expect(notify.error.mock.calls.map((c) => c[1])).toEqual([
      'parking.errors.vehicleNotFound',
      'parking.errors.forbidden',
      'parking.errors.notAccepting',
    ])
  })

  it('5xx は共通ハンドラへ委ねる（握りつぶさない）', async () => {
    const flow = useParkingApplicationFlow('team', '5')
    mockApi.mockRejectedValueOnce(apiError(500))
    await flow.submit({ spaceId: 1, vehicleId: 1 })
    expect(handleApiError).toHaveBeenCalledTimes(1)
  })

  it('取消成功: DELETE して一覧を取り直す', async () => {
    mockApi
      .mockResolvedValueOnce({ data: [{ id: 4, status: 'PENDING' }], meta: { total: 1 } })
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce({ data: [], meta: { total: 0 } })
    const flow = useParkingApplicationFlow('team', '5')
    await flow.loadApplications(0)
    const ok = await flow.cancel({ id: 4, status: 'PENDING' })
    expect(ok).toBe(true)
    expect(mockApi.mock.calls[1]).toEqual(['/api/v1/teams/5/parking/applications/4', { method: 'DELETE' }])
    expect(notify.success).toHaveBeenCalledWith('parking.applications.cancelled')
    expect(flow.applications.value).toHaveLength(0)
  })

  it('取消 409（決定済み）: 通知して一覧を取り直す', async () => {
    mockApi
      .mockResolvedValueOnce({ data: [{ id: 4, status: 'PENDING' }], meta: { total: 1 } })
      .mockRejectedValueOnce(apiError(409, 'PARKING_015'))
      .mockResolvedValueOnce({ data: [{ id: 4, status: 'APPROVED' }], meta: { total: 1 } })
    const flow = useParkingApplicationFlow('team', '5')
    await flow.loadApplications(0)
    const ok = await flow.cancel({ id: 4, status: 'PENDING' })
    expect(ok).toBe(false)
    expect(notify.error).toHaveBeenCalledWith('dialog.error', 'parking.errors.invalidStatus')
    expect(flow.applications.value[0]?.status).toBe('APPROVED')
  })

  it('車両の取得失敗は vehiclesError に残す', async () => {
    mockApi.mockRejectedValueOnce(apiError(500))
    const flow = useParkingApplicationFlow('team', '5')
    await flow.loadVehicles()
    expect(flow.vehiclesError.value).toBeTruthy()
    expect(flow.vehiclesLoaded.value).toBe(false)
  })
})
