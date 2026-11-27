import { defineStore } from 'pinia'
import type { SupportAccessSessionResponse } from '~/types/support-access'

/**
 * F10.9 サポート閲覧（本人承認制の代理ログイン）ストア（戦役Z-B）。
 *
 * isActive の間、useApi の onRequest が X-Support-Access-Grant-Id を付与する
 * （/api/v1/system-admin/** と /api/v1/auth/** には付けない）。旧・管理者変身のストアを置き換える。
 *
 * - 状態は画面メモリだけに持つ（再読込すると閲覧は終わり、運営者本人の画面に戻る）。許可自体は BE で期限まで有効。
 * - 期限（許可+24時間）を過ぎたら、BE の応答を待たずに手元で終了する。残り時間は端末の時計ではなく、
 *   閲覧開始の応答の remainingSeconds（BE の時刻基準）を受け取った時点から数える（端末の時計のずれで延命・早切れしない）。
 * - 終了時は BE の sessions/end を呼び、本人データのキャッシュ（useAsyncData・useState）を必ず消す。
 */
export interface SupportAccessSession {
  grantId: string
  targetUserId: number
  targetDisplayName: string
  /** 許可の期限（ISO 8601）。許可+24h 固定。表示用。 */
  expiresAt: string
  /** 応答時点での残り秒数（BE の時刻基準）。無ければ expiresAt と端末の時計で数える。 */
  remainingSeconds?: number
}

/**
 * 閲覧開始の応答（生成型は全項目 optional）を、閲覧に必要な値が揃った {@link SupportAccessSession} にする。
 * grant・本人・期限のどれかが欠けていれば null（呼び出し側は閲覧を始めない）。
 */
export function toSupportAccessSession(res: SupportAccessSessionResponse | null | undefined): SupportAccessSession | null {
  if (!res) return null
  const { grantId, targetUserId, targetDisplayName, expiresAt, remainingSeconds } = res
  if (!grantId || typeof targetUserId !== 'number' || !expiresAt) return null
  return {
    grantId,
    targetUserId,
    targetDisplayName: targetDisplayName ?? '',
    expiresAt,
    ...(typeof remainingSeconds === 'number' ? { remainingSeconds } : {}),
  }
}

/**
 * 読み出しごとに現在時刻で評価する値。computed は時刻の経過では再計算されないため customRef を使い、
 * 1 秒ごとの clock 更新で画面（残り時間）も更新する。
 */
function liveValue<T>(clock: Ref<number>, read: () => T): Ref<T> {
  return customRef<T>(track => ({
    get() {
      track()
      void clock.value
      return read()
    },
    set() {
      // 読み取り専用
    },
  }))
}

export const useSupportAccessStore = defineStore('supportAccess', () => {
  const grantId = ref<string | null>(null)
  const targetUserId = ref<number | null>(null)
  const targetDisplayName = ref<string>('')
  const expiresAt = ref<string | null>(null)
  /** 終了の時刻（端末の時計の上での値）。BE の残り秒数を受け取った時刻に足して求める。 */
  const deadlineMs = ref<number | null>(null)
  const clock = ref(Date.now())
  let timer: ReturnType<typeof setInterval> | null = null

  function remainingMs(): number {
    if (!grantId.value || deadlineMs.value === null) return 0
    return Math.max(0, deadlineMs.value - Date.now())
  }

  /** BE の残り秒数があればそれを基準に、無ければ expiresAt（端末の時計との比較）で終了時刻を決める。 */
  function resolveDeadline(session: SupportAccessSession): number | null {
    if (typeof session.remainingSeconds === 'number' && Number.isFinite(session.remainingSeconds)) {
      return Date.now() + Math.max(0, session.remainingSeconds) * 1000
    }
    const expires = Date.parse(session.expiresAt)
    return Number.isFinite(expires) ? expires : null
  }

  /** 有効な閲覧中か（期限切れは false）。 */
  const isActive = liveValue(clock, () => remainingMs() > 0)

  /** 期限までの残り秒数（閲覧中でなければ 0）。 */
  const remainingSeconds = liveValue(clock, () => Math.floor(remainingMs() / 1000))

  function stopTimer() {
    if (timer) clearInterval(timer)
    timer = null
  }

  /**
   * useAsyncData / useState のキャッシュを消す。
   * 単体テストでは Nuxt インスタンスが無いため、globalThis に差した代替を優先する（本番では未定義で、自動 import の本物を使う）。
   */
  function clearCaches(options: { state: boolean }) {
    const seam = globalThis as { clearNuxtData?: () => void; clearNuxtState?: () => void }
    ;(seam.clearNuxtData ?? clearNuxtData)()
    if (options.state) (seam.clearNuxtState ?? clearNuxtState)()
  }

  /** 手元の閲覧状態と、本人データを含みうるキャッシュを消す。 */
  function resetLocal() {
    stopTimer()
    grantId.value = null
    targetUserId.value = null
    targetDisplayName.value = ''
    expiresAt.value = null
    deadlineMs.value = null
    clearCaches({ state: true })
  }

  /** BE 側で無効になった（期限切れ・取消・撤回）許可を、手元でも終える。BE は呼ばない。 */
  function expireLocally(): void {
    if (!grantId.value) return
    resetLocal()
  }

  /** 閲覧開始の応答（POST sessions）で閲覧中にする。運営者本人のキャッシュは持ち越さない。 */
  function startSession(session: SupportAccessSession): void {
    stopTimer()
    grantId.value = session.grantId
    targetUserId.value = session.targetUserId
    targetDisplayName.value = session.targetDisplayName
    expiresAt.value = session.expiresAt
    deadlineMs.value = resolveDeadline(session)
    clock.value = Date.now()
    // 運営者本人の取得結果を本人の画面として使い回さない（useState は他の画面状態も含むため終了時だけ消す）
    clearCaches({ state: false })
    if (typeof window !== 'undefined') {
      timer = setInterval(() => {
        clock.value = Date.now()
        // 期限切れは BE の応答を待たずに手元で終える（BE も次のリクエストから拒否する）
        if (remainingMs() <= 0) expireLocally()
      }, 1000)
    }
  }

  /** 閲覧を終える（BE の sessions/end を呼び、状態と本人データのキャッシュを消す）。 */
  async function endSession(): Promise<void> {
    const id = grantId.value
    if (!id) return
    try {
      const api = useApi()
      await api(`/api/v1/system-admin/support-access/grants/${id}/sessions/end`, { method: 'POST' })
    }
    finally {
      // BE の終了が失敗しても、手元の閲覧状態と本人データは必ず消す（失敗は呼び出し側へ伝える）
      resetLocal()
    }
  }

  return {
    grantId,
    targetUserId,
    targetDisplayName,
    expiresAt,
    isActive,
    remainingSeconds,
    startSession,
    expireLocally,
    endSession,
  }
})
