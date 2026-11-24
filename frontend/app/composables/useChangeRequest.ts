import type { Ref } from 'vue'
import type { ChangeRequest, CreateChangeRequestPayload, ReviewOutcome } from '~/types/shift'

/** 一覧を最新に取り直すべき競合（他者が先に状態を変えた）のエラーコード。 */
const CONFLICT_CODES = ['SHIFT_031', 'COMMON_003']

function isConflict(error: unknown): boolean {
  const e = error as { statusCode?: number; status?: number; data?: { error?: { code?: string } } }
  const code = e?.data?.error?.code
  return (code !== undefined && CONFLICT_CODES.includes(code)) || (e?.statusCode ?? e?.status) === 409
}

/**
 * シフト変更依頼の取得・作成・審査・取下を管理する composable。
 * F03.5 §A-1確定前変更 / A-2個別交代 / A-3オープンコール に対応。
 *
 * - 取得失敗は通知せず `loadError` に積む（画面が DashboardErrorState で出す。空状態にしない）。
 * - 作成・審査・取下の失敗は `handleApiError` に委ね、例外は再 throw しない（戻り値 false）。
 * - 同じ依頼への操作は処理中に重ねて送らない（`processingIds`）。
 */
export function useChangeRequest(scheduleId: Ref<number>) {
  const shiftApi = useShiftApi()
  const { success: showSuccess, error: showError } = useNotification()
  const { handleApiError } = useErrorHandler()
  const { t } = useI18n()

  const requests = ref<ChangeRequest[]>([])
  const isLoading = ref(false)
  const loadError = ref<unknown>(null)
  /** 審査・取り下げの処理中の依頼 ID */
  const processingIds = ref<number[]>([])

  /**
   * 変更依頼一覧を取得する。
   * @returns 失敗したときはそのエラー、成功なら null
   */
  async function fetchRequests(): Promise<unknown> {
    isLoading.value = true
    loadError.value = null
    try {
      const list = await shiftApi.listChangeRequests(scheduleId.value)
      // OPEN の依頼を先頭に並べる
      list.sort((a, b) => {
        if (a.reviewInfo.status === 'OPEN' && b.reviewInfo.status !== 'OPEN') return -1
        if (a.reviewInfo.status !== 'OPEN' && b.reviewInfo.status === 'OPEN') return 1
        return new Date(b.timing.createdAt).getTime() - new Date(a.timing.createdAt).getTime()
      })
      requests.value = list
      return null
    } catch (error) {
      // 再取得の失敗で既存の一覧を空にしない（空状態に見せない）。画面が loadError を見て出す。
      loadError.value = error
      return error
    } finally {
      isLoading.value = false
    }
  }

  /** 変更依頼を作成する。成功なら true */
  async function createRequest(payload: CreateChangeRequestPayload): Promise<boolean> {
    try {
      const created = await shiftApi.createChangeRequest(payload)
      requests.value.unshift(created)
      showSuccess(t('shift.changeRequest.createSuccess'))
      return true
    } catch (error) {
      handleApiError(error, 'シフト変更依頼の作成')
      return false
    }
  }

  type ExecResult = 'ok' | 'busy' | 'conflict' | 'error'

  /**
   * 同一依頼への操作を直列化して実行する。処理中なら何もせず 'busy'。
   * conflictMessage を渡すと、409 はそれを通知して handleApiError は呼ばない。
   */
  async function execute(
    id: number,
    context: string,
    action: () => Promise<void>,
    conflictMessage?: string,
  ): Promise<ExecResult> {
    if (processingIds.value.includes(id)) return 'busy'
    processingIds.value = [...processingIds.value, id]
    try {
      await action()
      return 'ok'
    } catch (error) {
      const conflict = isConflict(error)
      if (conflict && conflictMessage) showError(conflictMessage)
      else handleApiError(error, context)
      // 他者が先に状態を変えた競合は、一覧を最新に取り直して正しい状態を見せる
      if (conflict) await fetchRequests()
      return conflict ? 'conflict' : 'error'
    } finally {
      processingIds.value = processingIds.value.filter(x => x !== id)
    }
  }

  /**
   * 変更依頼を審査する（チーム ADMIN / DEPUTY_ADMIN）。
   * version は依頼の楽観ロック値（不一致は BE が 409 SHIFT_018）。
   * 409 は競合専用の文言を出し、一覧を再取得して conflict（最新の依頼つき）を返す。
   */
  async function reviewWithOutcome(
    id: number,
    decision: 'ACCEPTED' | 'REJECTED',
    comment: string | undefined,
    version: number,
  ): Promise<ReviewOutcome> {
    const result = await execute(
      id,
      'シフト変更依頼の審査',
      async () => {
        const updated = await shiftApi.reviewChangeRequest(id, {
          decision,
          reviewComment: comment || undefined,
          version,
        })
        const idx = requests.value.findIndex(r => r.id === id)
        if (idx !== -1) {
          requests.value[idx] = { ...requests.value[idx], ...updated, requesterName: updated.requesterName ?? requests.value[idx]!.requesterName }
        }
        showSuccess(
          decision === 'ACCEPTED'
            ? t('shift.changeRequest.approveSuccess')
            : t('shift.changeRequest.rejectSuccess'),
        )
      },
      t('shift.changeRequest.reviewConflict'),
    )
    if (result === 'ok') return { status: 'ok' }
    if (result === 'conflict') {
      return { status: 'conflict', latest: requests.value.find(r => r.id === id) ?? null }
    }
    return { status: 'error' }
  }

  /** 変更依頼を審査する。成功なら true */
  async function review(
    id: number,
    decision: 'ACCEPTED' | 'REJECTED',
    comment: string | undefined,
    version: number,
  ): Promise<boolean> {
    return (await reviewWithOutcome(id, decision, comment, version)).status === 'ok'
  }

  /** 変更依頼を取り下げる（依頼者のみ）。成功なら true */
  function withdraw(id: number): Promise<boolean> {
    return execute(id, 'シフト変更依頼の取り下げ', async () => {
      await shiftApi.withdrawChangeRequest(id)
      const idx = requests.value.findIndex(r => r.id === id)
      if (idx !== -1) {
        const target = requests.value[idx]!
        requests.value[idx] = {
          ...target,
          reviewInfo: { ...target.reviewInfo, status: 'WITHDRAWN' },
        }
      }
      showSuccess(t('shift.changeRequest.withdrawSuccess'))
    }).then(result => result === 'ok')
  }

  return {
    requests,
    isLoading,
    loadError,
    processingIds,
    fetchRequests,
    createRequest,
    review,
    reviewWithOutcome,
    withdraw,
  }
}
