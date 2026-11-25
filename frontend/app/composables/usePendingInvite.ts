// CMP-261107-1833 PR2（AC13）: ログイン後の参加待ち招待ダイアログの状態と操作（components/auth/PendingInviteDialog.vue から使う）。
// 設計正本 §6・§9-1・§9-4。API は /api/v1/me/pending-invite（GET / POST accept・decline・reissue-request・dismiss）。
import type { components } from '~/types/generated'

type PendingInvite = components['schemas']['PendingInviteResponse']
type ApiResponse<T> = { data?: T | null }

const BASE = '/api/v1/me/pending-invite'
// 「あとで」: セッション中だけ再表示しない（保留 ID を記録）
const LATER_KEY = 'mannschaft.pendingInvite.later'
// 「参加できません」を確認済みの保留 ID。BE はこの状態の dismiss を受け付けないため端末側で記録する
const UNAVAILABLE_SEEN_KEY = 'mannschaft.pendingInvite.unavailableSeen'
// タイマー誤差で再試行可能時刻の直前に発火しても有効化されるよう、少し余裕を持たせる
const RETRY_TIMER_MARGIN_MS = 50
// setTimeout の上限（約24.8日）。これを超える値は即時発火してしまう
const MAX_TIMEOUT_MS = 2_147_483_647

export function usePendingInvite() {
  const authStore = useAuthStore()
  const api = useApi()
  const notification = useNotification()
  const { handleApiError } = useErrorHandler()
  const { t, te } = useI18n()

  const invite = ref<PendingInvite | null>(null)
  const closed = ref(false)
  const busy = ref(false)
  // 取得に成功したら、そのログインの間は取り直さない（AUTH_070 や失敗時だけ次のトークン変化で取り直す）
  let fetched = false
  let loading = false
  // 取得中にトークンが更新された（保護者承認後など）場合、完了後に必ず取り直す
  let refetchRequested = false
  const laterId = ref<string | null>(null)
  const unavailableSeenId = ref<string | null>(null)
  const now = ref(Date.now())
  let retryTimer: ReturnType<typeof setTimeout> | null = null

  function errorCode(err: unknown): string | undefined {
    return (err as { data?: { error?: { code?: string } } })?.data?.error?.code
  }

  const status = computed(() => invite.value?.status)
  const visible = computed(() => {
    const current = invite.value
    if (!current || closed.value) return false
    switch (current.status) {
      case 'PENDING':
        return current.id !== laterId.value
      case 'EXPIRED':
      case 'REISSUE_REQUESTED':
      case 'ACCEPTING':
        return true
      case 'UNAVAILABLE':
        return current.id !== unavailableSeenId.value
      default:
        // JOINED / DECLINED / DISMISSED / DISCARDED は終了状態（GET は終了行も返す）
        return false
    }
  })
  const isExpiredView = computed(() => status.value === 'EXPIRED' || status.value === 'REISSUE_REQUESTED')
  const header = computed(() => {
    if (isExpiredView.value) return t('auth.pending_invite.title_expired')
    if (status.value === 'ACCEPTING') return t('auth.pending_invite.title_accepting')
    if (status.value === 'UNAVAILABLE') return t('auth.pending_invite.title_unavailable')
    return t('auth.pending_invite.title_pending')
  })
  const roleLabel = computed(() => {
    const role = invite.value?.roleName
    if (!role) return ''
    return te(`role.${role}`) ? t(`role.${role}`) : role
  })
  const scopeName = computed(() => invite.value?.scopeName ?? '')
  const retryEnabled = computed(() => {
    const at = invite.value?.retryAvailableAt
    return !at || now.value >= Date.parse(at)
  })

  function clearRetryTimer() {
    if (retryTimer) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
  }

  // 画面を開いたまま retryAvailableAt を過ぎた場合も再試行を有効にする
  watch(
    () => [invite.value?.status, invite.value?.retryAvailableAt] as const,
    ([st, at]) => {
      clearRetryTimer()
      now.value = Date.now()
      if (st !== 'ACCEPTING' || !at) return
      const wait = Date.parse(at) - now.value
      if (wait <= 0) return
      retryTimer = setTimeout(() => {
        retryTimer = null
        now.value = Date.now()
      }, Math.min(wait + RETRY_TIMER_MARGIN_MS, MAX_TIMEOUT_MS))
    },
    { immediate: true },
  )
  onBeforeUnmount(clearRetryTimer)

  async function fetchInvite(): Promise<void> {
    if (loading) {
      refetchRequested = true
      return
    }
    loading = true
    try {
      const result = await api<ApiResponse<PendingInvite>>(BASE)
      invite.value = result.data ?? null
      laterId.value = sessionStorage.getItem(LATER_KEY)
      unavailableSeenId.value = localStorage.getItem(UNAVAILABLE_SEEN_KEY)
      fetched = true
    } catch (err: unknown) {
      // AUTH_070（保護者同意待ち）は異常ではない。同意承認後のトークン更新で取り直す
      if (errorCode(err) !== 'AUTH_070') handleApiError(err, t('auth.pending_invite.load_context'))
    } finally {
      loading = false
      if (refetchRequested) {
        refetchRequested = false
        if (!fetched) void fetchInvite()
      }
    }
  }

  watch(
    () => [authStore.user, authStore.accessToken] as const,
    ([user]) => {
      if (!user) {
        invite.value = null
        closed.value = false
        fetched = false
        return
      }
      if (!fetched) void fetchInvite()
    },
    { immediate: true },
  )

  async function accept() {
    if (busy.value) return
    busy.value = true
    try {
      const result = await api<ApiResponse<{ status?: string }>>(`${BASE}/accept`, { method: 'POST' })
      if (result.data?.status === 'JOINED') {
        notification.success(t('auth.pending_invite.joined', { name: scopeName.value }))
        closed.value = true
        await navigateTo('/dashboard')
      } else {
        // EXPIRED / UNAVAILABLE / ACCEPTING など。案内に必要な publicPath 等を取り直す
        fetched = false
        await fetchInvite()
      }
    } catch (err: unknown) {
      if (errorCode(err) === 'AUTH_086') {
        // 別の処理が進行中。現在の状態に表示を合わせる
        fetched = false
        await fetchInvite()
      } else {
        handleApiError(err, t('auth.pending_invite.accept_context'))
      }
    } finally {
      busy.value = false
    }
  }

  async function decline() {
    if (busy.value) return
    busy.value = true
    try {
      await api(`${BASE}/decline`, { method: 'POST' })
      closed.value = true
    } catch (err: unknown) {
      handleApiError(err, t('auth.pending_invite.decline_context'))
    } finally {
      busy.value = false
    }
  }

  function later() {
    const id = invite.value?.id
    if (!id) return
    sessionStorage.setItem(LATER_KEY, id)
    laterId.value = id
  }

  async function reissue() {
    if (busy.value || !invite.value) return
    busy.value = true
    try {
      await api(`${BASE}/reissue-request`, { method: 'POST' })
      // 初回でも7日以内の再押下（alreadyRequested）でも、依頼済み表示にする
      invite.value = { ...invite.value, status: 'REISSUE_REQUESTED' }
    } catch (err: unknown) {
      handleApiError(err, t('auth.pending_invite.reissue_context'))
    } finally {
      busy.value = false
    }
  }

  async function dismiss() {
    if (busy.value) return
    busy.value = true
    try {
      await api(`${BASE}/dismiss`, { method: 'POST' })
      closed.value = true
    } catch (err: unknown) {
      handleApiError(err, t('auth.pending_invite.dismiss_context'))
    } finally {
      busy.value = false
    }
  }

  // UNAVAILABLE は BE が dismiss を受け付けないため、閉じたら端末に記録して次回以降出さない
  // 参加申し込みへのリンクを押した: モーダルを閉じ、案内を終了（EXPIRED→DISMISSED）する。
  // 申し込みへ進んだ利用者に、次回ログインでも同じ失効案内を出し続けないため。遷移自体は失敗しても妨げない
  async function leaveForJoinRequest() {
    closed.value = true
    try {
      await api(`${BASE}/dismiss`, { method: 'POST' })
    } catch (err: unknown) {
      handleApiError(err, t('auth.pending_invite.dismiss_context'))
    }
  }

  function closeUnavailable() {
    const id = invite.value?.id
    if (id) {
      localStorage.setItem(UNAVAILABLE_SEEN_KEY, id)
      unavailableSeenId.value = id
    }
  }

    return {
      status,
      visible,
      header,
      roleLabel,
      scopeName,
      invite,
      busy,
      isExpiredView,
      retryEnabled,
      accept,
      decline,
      later,
      reissue,
      dismiss,
      closeUnavailable,
      leaveForJoinRequest,
    }
  }
