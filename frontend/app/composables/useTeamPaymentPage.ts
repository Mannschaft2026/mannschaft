/**
 * チーム請求3画面の権限確認・取得・再試行を同じ経路で実行する。
 * 先に useTeamAccessGate でチーム詳細の可否を判定し（CMP-261124-0159 PR-F2c）、403/404/判定中/通信失敗の間は
 * 権限取得も本文 API も呼ばない。gate* を返すので、画面は gateState で ScopeNotFound / 読み込み中 / エラーを出し分ける。
 */
export function useTeamPaymentPage<T>(
  teamSlug: Ref<string>,
  fetchData: (teamId: number) => Promise<T>,
  emptyData: () => T,
  requestId?: Ref<string>,
) {
  const gate = useTeamAccessGate(teamSlug)
  const access = useRoleAccess('team', teamSlug, { watchScope: false })
  const { resolveScopeId } = useActivityScopeId()
  const { handleApiError } = useErrorHandler()
  const teamId = ref<number | null>(null)
  const data = shallowRef<T>(emptyData())
  const state = ref<'loading' | 'loaded' | 'denied' | 'error'>('loading')
  const loadError = shallowRef<unknown>(null)
  let requestSeq = 0

  function fail(error: unknown): void {
    data.value = emptyData()
    loadError.value = error
    state.value = 'error'
    handleApiError(error, 'payment-request.team.load')
  }

  async function load(): Promise<void> {
    const seq = ++requestSeq
    const slug = teamSlug.value
    const id = requestId?.value
    const isCurrent = () => seq === requestSeq && slug === teamSlug.value && id === requestId?.value
    // watchの同期実行時点で、前のチームの金融情報を描画対象から外す。
    data.value = emptyData()
    teamId.value = null
    loadError.value = null
    state.value = 'loading'
    try {
      const result = await access.loadPermissions()
      if (!isCurrent()) return
      if (!result.ok) throw result.error
      if (!access.isAdminOrDeputy.value) {
        state.value = 'denied'
        return
      }
      const resolvedId = await resolveScopeId('TEAM', slug)
      if (!isCurrent()) return
      if (resolvedId === null) throw new Error('payment request team scope could not be resolved')
      const response = await fetchData(resolvedId)
      if (!isCurrent()) return
      teamId.value = resolvedId
      data.value = response
      state.value = 'loaded'
    } catch (error: unknown) {
      if (isCurrent()) fail(error)
    }
  }

  // チームが変わったら前のチームの金融情報を即座に外す。取得はチーム判定が 'ok' になってからだけ（gate.onOk）。
  // 同じチームで requestId だけ変わった場合は判定済みなのでそのまま取得し直す。
  let lastSlug = teamSlug.value
  watch(() => [teamSlug.value, requestId?.value], () => {
    if (teamSlug.value !== lastSlug) {
      lastSlug = teamSlug.value
      requestSeq += 1
      data.value = emptyData()
      teamId.value = null
      loadError.value = null
      state.value = 'loading'
      return
    }
    if (gate.state.value === 'ok') void load()
  }, { flush: 'sync' })
  gate.onOk(load)
  onUnmounted(() => { requestSeq += 1 })

  return {
    data, teamId, state, loadError, load, fail,
    gateState: gate.state, gateError: gate.error, reloadGate: gate.load,
    loading: computed(() => state.value === 'loading'),
    isAdminOrDeputy: computed(() => state.value === 'loaded'),
  }
}
