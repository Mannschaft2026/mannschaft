import { defineStore } from 'pinia'

/**
 * F10.9 サポート閲覧（本人承認制の代理ログイン）ストア（戦役Z-B・試練スタブ）。
 *
 * isActive の間、useApi の onRequest が X-Support-Access-Grant-Id を付与する
 * （/api/v1/system-admin/** と /api/v1/auth/** には付けない）。旧 useAdminImpersonationStore を置き換える。
 * 実装役が中身を作る。スタブの間は isActive は常に false、操作は未実装で例外になる。
 */
export interface SupportAccessSession {
  grantId: string
  targetUserId: number
  targetDisplayName: string
  /** 許可の期限（ISO 8601）。許可+24h 固定。 */
  expiresAt: string
}

export const useSupportAccessStore = defineStore('supportAccess', () => {
  const grantId = ref<string | null>(null)
  const targetUserId = ref<number | null>(null)
  const targetDisplayName = ref<string>('')
  const expiresAt = ref<string | null>(null)

  /** 有効な閲覧中か（期限切れは false）。試練スタブ: 常に false。 */
  const isActive = computed(() => false)

  /** 期限までの残り秒数（閲覧中でなければ 0）。試練スタブ: 常に 0。 */
  const remainingSeconds = computed(() => 0)

  /** 閲覧開始の応答（POST sessions）で閲覧中にする。 */
  function startSession(_session: SupportAccessSession): void {
    throw new Error('試練スタブ: 戦役Z-B 未実装')
  }

  /** 閲覧を終える（BE の sessions/end を呼び、状態と本人データのキャッシュを消す）。 */
  async function endSession(): Promise<void> {
    throw new Error('試練スタブ: 戦役Z-B 未実装')
  }

  return {
    grantId,
    targetUserId,
    targetDisplayName,
    expiresAt,
    isActive,
    remainingSeconds,
    startSession,
    endSession,
  }
})
