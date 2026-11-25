/**
 * F18 個人ポイントカードウォレット — 提示モードの生体認証ゲート。
 *
 * <p>設定 {@code requireBiometricOnShow=true} のときに呼ぶ。サーバー側 5 分 TTL
 * 検証を含む 3 段フロー。</p>
 *
 * <ol>
 *   <li>{@code POST /reauthenticate-begin} でチャレンジ取得</li>
 *   <li>{@code navigator.credentials.get} で署名取得（OS 生体認証 / PIN ダイアログ）</li>
 *   <li>{@code POST /reauthenticate-complete} でサーバー検証 → 「再認証済みフラグ」が
 *       Valkey に 5 分 TTL で記録される</li>
 * </ol>
 *
 * <p>UV（生体認証・画面ロック PIN）を必須（userVerification='required'）で要求し、用途 POINTCARD_PRESENT を
 * begin に送って challenge に束ねる。UV を満たせない端末では提示モードを開けず、その旨を {@code uvUnavailable} で返す
 * （汎用の認証失敗にしない）。署名取得の本体は {@link usePasskeyReauth}。</p>
 *
 * <p>completeReauthenticate は AT/RT を再発行しない。フラグは提示モード起動 API が
 * 1 回限りで消費する（POINT_CARD_009 再生攻撃防止）。</p>
 *
 * <p>分割元: {@code frontend/app/pages/wallet/groups/[id]/show.vue}（リファクタリング第11弾）。</p>
 */

import type { Ref } from 'vue'
import { ref } from 'vue'
import { usePasskeyReauth } from '../usePasskeyReauth'

export interface UseBiometricGate {
  /**
   * @returns 成功で true / ユーザーキャンセル or 失敗で false
   */
  verifyBiometric: () => Promise<boolean>
  /** 直近の verifyBiometric が「この端末は UV 不可」で失敗したか（A6: 専用文言を出す）。 */
  uvUnavailable: Ref<boolean>
}

export function useBiometricGate(): UseBiometricGate {
  const { reauthenticateWithPasskey } = usePasskeyReauth()
  const uvUnavailable = ref(false)

  async function verifyBiometric(): Promise<boolean> {
    uvUnavailable.value = false
    const result = await reauthenticateWithPasskey('POINTCARD_PRESENT')
    uvUnavailable.value = result === 'uv_unavailable'
    return result === 'ok'
  }

  return { verifyBiometric, uvUnavailable }
}
