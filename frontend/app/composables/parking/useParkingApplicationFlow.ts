import { useParkingApplicationsApi } from './useParkingApplicationsApi'
import { usePersonalVehiclesApi } from './usePersonalVehiclesApi'
import { isStaleStateError, resolveParkingErrorKey } from './parkingErrors'
import type { ApplicationResponse, VehicleResponse } from '~/types/parking'

export const APPLICATION_PAGE_SIZE = 10

/** キャンセルできる申請の状態（BE: PENDING / LOTTERY_PENDING のみ。決定済みは 409）。 */
export function isCancellableApplication(app: ApplicationResponse): boolean {
  return app.status === 'PENDING' || app.status === 'LOTTERY_PENDING'
}

/**
 * 駐車場の利用申請（フォーム送信・自分の申請一覧・取消・車両）の状態と操作。
 *
 * - 失敗は握りつぶさず、利用言語の文言（parking.errors.*）でトースト表示する。
 *   該当が無い失敗は共通の handleApiError に委ねる。
 * - 409/404/422 は画面の状態が古い可能性があるため、`onStale` で区画一覧などの再取得を促す。
 *   失敗しても既に表示している一覧は消さない。
 */
export function useParkingApplicationFlow(
  scopeType: 'team' | 'organization',
  scopeId: string,
  options: { onStale?: () => void | Promise<void> } = {},
) {
  const { t } = useI18n()
  const notification = useNotification()
  const { handleApiError } = useErrorHandler()
  const applicationsApi = useParkingApplicationsApi()
  const vehiclesApi = usePersonalVehiclesApi()

  const applications = ref<ApplicationResponse[]>([])
  const total = ref(0)
  const page = ref(0)
  const loading = ref(false)
  const loadError = ref<unknown>(null)
  const submitting = ref(false)
  const registering = ref(false)
  const cancellingIds = ref<number[]>([])
  // 古い応答が新しい表示を上書きしないよう、最新の要求だけを反映する（要求連番）
  let applicationsSeq = 0
  let vehiclesSeq = 0

  const vehicles = ref<VehicleResponse[]>([])
  const vehiclesLoaded = ref(false)
  const vehiclesError = ref<unknown>(null)

  function reportError(error: unknown) {
    const key = resolveParkingErrorKey(error)
    if (key) notification.error(t('dialog.error'), t(key))
    else handleApiError(error)
  }

  async function notifyStale(error: unknown) {
    if (isStaleStateError(error)) await options.onStale?.()
  }

  async function loadApplications(targetPage = page.value) {
    const seq = ++applicationsSeq
    loading.value = true
    loadError.value = null
    try {
      const res = await applicationsApi.getApplications(scopeType, scopeId, {
        page: targetPage,
        size: APPLICATION_PAGE_SIZE,
      })
      if (seq !== applicationsSeq) return
      applications.value = res.data ?? []
      total.value = res.meta?.total ?? applications.value.length
      page.value = targetPage
    } catch (e) {
      if (seq !== applicationsSeq) return
      // 取得失敗は空一覧にせず、エラー状態として画面へ返す（表示中の一覧は保持）
      loadError.value = e
    } finally {
      if (seq === applicationsSeq) loading.value = false
    }
  }

  async function loadVehicles() {
    const seq = ++vehiclesSeq
    vehiclesError.value = null
    try {
      const res = await vehiclesApi.getMyVehicles()
      if (seq !== vehiclesSeq) return
      vehicles.value = res.data ?? []
      vehiclesLoaded.value = true
    } catch (e) {
      if (seq !== vehiclesSeq) return
      vehiclesError.value = e
    }
  }

  async function registerVehicle(body: {
    vehicleType: 'CAR' | 'MOTORCYCLE' | 'BICYCLE'
    plateNumber: string
    nickname?: string
  }): Promise<VehicleResponse | null> {
    if (registering.value) return null
    registering.value = true
    try {
      const res = await vehiclesApi.addVehicle(body)
      await loadVehicles()
      return res.data ?? null
    } catch (e) {
      reportError(e)
      return null
    } finally {
      registering.value = false
    }
  }

  /** 申請を送信する。成功したら true。 */
  async function submit(body: {
    spaceId: number
    vehicleId: number
    message?: string
  }): Promise<boolean> {
    if (submitting.value) return false
    submitting.value = true
    try {
      const res = await applicationsApi.createApplication(scopeType, scopeId, body)
      notification.success(
        res.data?.status === 'LOTTERY_PENDING'
          ? t('parking.apply.successLottery')
          : t('parking.apply.success'),
      )
      await loadApplications(0)
      return true
    } catch (e) {
      reportError(e)
      await notifyStale(e)
      return false
    } finally {
      submitting.value = false
    }
  }

  /** 申請を取り消す。成功したら true。 */
  async function cancel(app: ApplicationResponse): Promise<boolean> {
    if (app.id == null || cancellingIds.value.includes(app.id)) return false
    const appId = app.id
    cancellingIds.value = [...cancellingIds.value, appId]
    try {
      await applicationsApi.deleteApplication(scopeType, scopeId, app.id)
      notification.success(t('parking.applications.cancelled'))
      // 最後の1件を消してページが空になった場合は1つ前へ戻す
      const lastPage = Math.max(0, Math.ceil((total.value - 1) / APPLICATION_PAGE_SIZE) - 1)
      await loadApplications(Math.min(page.value, lastPage))
      return true
    } catch (e) {
      reportError(e)
      // 決定済み（409）・既に無い（404）などは一覧が古いので取り直す
      if (isStaleStateError(e)) await loadApplications()
      await notifyStale(e)
      return false
    } finally {
      cancellingIds.value = cancellingIds.value.filter((id) => id !== appId)
    }
  }

  return {
    applications,
    total,
    page,
    loading,
    loadError,
    submitting,
    registering,
    cancellingIds,
    vehicles,
    vehiclesLoaded,
    vehiclesError,
    loadApplications,
    loadVehicles,
    registerVehicle,
    submit,
    cancel,
  }
}
