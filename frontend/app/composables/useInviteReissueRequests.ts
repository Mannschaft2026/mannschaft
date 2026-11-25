// CMP-261107-1833 PR3（再招待依頼の受け手側）: 招待管理画面の「再招待の依頼」欄の状態と操作。
// 陣立て書 .claude/handoffs/cmp1833-pr3-plan-20261123.md の i・j。
// API: GET /api/v1/{teams|organizations}/{slug}/invite-reissue-requests?page&size（古い順）、
//      POST …/{id}/reissue → { status: REISSUED | ALREADY_MEMBER, expiresAt? }、POST …/{id}/decline → { status: REISSUE_DECLINED }。
// 金型: useJoinRequestManagement（取得世代番号で古い応答を捨てる・ID で重複排除・失敗と 0 件を区別）。
import { ref, computed } from 'vue'
import type { Ref } from 'vue'
import { useNuxtApp } from '#app'

const PAGE_SIZE = 20

export type InviteReissueScopeType = 'team' | 'organization'

export interface InviteReissueRequest {
  id: string
  requesterDisplayName: string
  requesterAvatarUrl?: string | null
  requestedAt: string
  roleName: string
}

interface InviteReissuePage {
  content: InviteReissueRequest[]
  totalElements: number
}

interface ReissueResult {
  status?: 'REISSUED' | 'ALREADY_MEMBER'
  expiresAt?: string | null
}

type ApiResponse<T> = { data: T }

/**
 * 「再招待の依頼」欄を出してよいか（BE の InviteIssuerPolicy#canIssue と同じ。ADMIN、または INVITE_MEMBERS を持つ DEPUTY_ADMIN）。
 * 欄を出すかどうかの目安であり、認可の正本は BE（権限が無ければ API が 403 を返し、欄は閉じる）。
 */
export function canManageInviteReissue(roleName: string | null | undefined, permissions: readonly string[]): boolean {
  if (roleName === 'ADMIN') return true
  return roleName === 'DEPUTY_ADMIN' && permissions.includes('INVITE_MEMBERS')
}

function errorCode(err: unknown): string | undefined {
  return (err as { data?: { error?: { code?: string } } })?.data?.error?.code
}

function httpStatus(err: unknown): number | undefined {
  const e = err as { statusCode?: number; status?: number; response?: { status?: number } }
  return e?.statusCode ?? e?.status ?? e?.response?.status
}

export function useInviteReissueRequests(scopeType: Ref<InviteReissueScopeType>, slug: Ref<string>) {
  const api = useApi()
  const notification = useNotification()
  const { handleApiError } = useErrorHandler()
  // useI18n() は setup の外では呼べないため、useJoinRequestManagement と同様に $i18n 経由で引く
  const t = (key: string, params?: Record<string, unknown>) => useNuxtApp().$i18n.t(key, params as never) as string

  const requests = ref<InviteReissueRequest[]>([])
  const loading = ref(false)
  const error = ref(false)
  const forbidden = ref(false)
  const totalElements = ref(0)
  const processingIds = ref<string[]>([])
  const page = ref(0)
  let fetchSeq = 0

  const hasMore = computed(() => requests.value.length < totalElements.value)

  function basePath(): string {
    const base = scopeType.value === 'team' ? 'teams' : 'organizations'
    return `/api/v1/${base}/${slug.value}/invite-reissue-requests`
  }

  function closePanel() {
    forbidden.value = true
    requests.value = []
    totalElements.value = 0
    page.value = 0
    error.value = false
  }

  /** append=true は次ページを追記取得し、それ以外は先頭ページからやり直す。 */
  async function fetchRequests(append = false) {
    const seq = ++fetchSeq
    const targetPage = append ? page.value + 1 : 0
    loading.value = true
    error.value = false
    try {
      const res = await api<ApiResponse<InviteReissuePage>>(`${basePath()}?page=${targetPage}&size=${PAGE_SIZE}`)
      // 自分より新しい取得が始まっていれば、後着した古い応答は捨てる
      if (seq !== fetchSeq) return
      if (append) {
        const known = new Set(requests.value.map(r => r.id))
        requests.value = [...requests.value, ...res.data.content.filter(r => !known.has(r.id))]
      }
      else {
        requests.value = res.data.content
      }
      totalElements.value = res.data.totalElements
      page.value = targetPage
    }
    catch (err: unknown) {
      if (seq !== fetchSeq) return
      if (httpStatus(err) === 403) {
        // 権限を外された。欄を閉じ、理由を表示する（黙って消さない）
        closePanel()
        notification.error(t('inviteReissue.forbidden'))
        return
      }
      error.value = true
      handleApiError(err, t('inviteReissue.load_error'))
      if (!append) {
        requests.value = []
        totalElements.value = 0
        page.value = 0
      }
    }
    finally {
      if (seq === fetchSeq) loading.value = false
    }
  }

  async function init() {
    forbidden.value = false
    await fetchRequests()
  }

  async function loadMore() {
    if (!hasMore.value || loading.value) return
    await fetchRequests(true)
  }

  /** 先頭から取り直す。一覧が空（または取得失敗）なら先頭ページから、そうでなければ追加読み込みを再試行する。 */
  async function retry() {
    if (requests.value.length === 0) await fetchRequests()
    else await loadMore()
  }

  /** 操作の失敗を種別ごとに扱う。汎用エラー処理に回すのは想定外のものだけ。 */
  async function handleOperationError(err: unknown, context: string) {
    if (httpStatus(err) === 403) {
      closePanel()
      notification.error(t('inviteReissue.forbidden'))
      return
    }
    switch (errorCode(err)) {
      case 'ROLE_017':
        // 他の管理者が先に対応した、または状態が変わった
        notification.info(t('inviteReissue.already_handled'))
        await fetchRequests()
        return
      case 'ROLE_018':
        notification.warn(t('inviteReissue.requester_unavailable'))
        return
      case 'ROLE_019':
        notification.error(t('inviteReissue.activation_failed'))
        await fetchRequests()
        return
      default:
        handleApiError(err, context)
    }
  }

  async function run(id: string, action: 'reissue' | 'decline') {
    if (processingIds.value.includes(id)) return
    processingIds.value = [...processingIds.value, id]
    const name = requests.value.find(r => r.id === id)?.requesterDisplayName ?? ''
    try {
      const res = await api<ApiResponse<ReissueResult>>(`${basePath()}/${id}/${action}`, { method: 'POST' })
      if (action === 'decline') {
        notification.success(t('inviteReissue.declined'))
      }
      else if (res?.data?.status === 'ALREADY_MEMBER') {
        notification.info(t('inviteReissue.already_member'))
      }
      else {
        notification.success(t('inviteReissue.reissued', { name }))
      }
      await fetchRequests()
    }
    catch (err: unknown) {
      await handleOperationError(err, t(action === 'reissue' ? 'inviteReissue.reissue_context' : 'inviteReissue.decline_context'))
    }
    finally {
      processingIds.value = processingIds.value.filter(pid => pid !== id)
    }
  }

  const reissue = (id: string) => run(id, 'reissue')
  const decline = (id: string) => run(id, 'decline')

  return {
    requests,
    loading,
    error,
    forbidden,
    totalElements,
    hasMore,
    processingIds,
    init,
    loadMore,
    retry,
    reissue,
    decline,
  }
}
