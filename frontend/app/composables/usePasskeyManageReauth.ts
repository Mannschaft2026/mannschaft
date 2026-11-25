import { ref } from 'vue'
import { useAuthApi } from './useAuthApi'
import type { MfaEmailSendResponse, PasskeyManageReauthMethod, PasskeyManageReauthRequest } from './useAuthApi'
import { usePasskeyReauth } from './usePasskeyReauth'
import { describeTwoFactorError } from '~/utils/twoFactorError'
import { parseApiError } from '~/utils/apiError'

/** BE: 再認証が無い・期限切れ・別セッション・別用途（403）。再認証からやり直す。 */
export const ERROR_REAUTH_REQUIRED = 'AUTH_087'
const ERROR_REAUTH_FAILED = 'AUTH_080'

/** パスキー登録・削除の実行結果。AUTH_087 は 'reauth_required' で返し、再認証してやり直す。 */
export type PasskeyManageOutcome = 'done' | 'reauth_required' | 'failed'

/**
 * パスキーの登録・削除前の再認証（CMP-261021-1338 B5/B9）。
 *
 * <p>手段は BE の GET /reauthenticate-method で決まる（パスキー保有→パスキー（UV 必須・用途 PASSKEY_MANAGE）、
 * パスワードあり→パスワード（＋2FA 要素）、いずれも無し→メールOTP）。パスキー以外は
 * {@code SettingsPasskeyReauthDialog} で入力させる。成功後 5 分以内（BE 側 TTL）に登録・削除を実行する。</p>
 *
 * <p>{@link runWithReauth} は「再認証 → 操作」を 1 組で行い、操作が AUTH_087（期限切れ等）なら再認証からやり直す。</p>
 */
export function usePasskeyManageReauth() {
  const authApi = useAuthApi()
  const api = useApi()
  const notification = useNotification()
  const { t } = useI18n()
  const { reauthenticateWithPasskey } = usePasskeyReauth()

  const dialogVisible = ref(false)
  const dialogMethod = ref<Exclude<PasskeyManageReauthMethod, 'PASSKEY'>>('PASSWORD')
  const busy = ref(false)
  const errorMessage = ref('')
  const reauthEmail = ref<MfaEmailSendResponse | null>(null)

  let pending: ((ok: boolean) => void) | null = null

  function settle(ok: boolean) {
    const resolve = pending
    pending = null
    resolve?.(ok)
  }

  function describeFailure(e: unknown): string {
    const code = parseApiError(e).code
    if (code === ERROR_REAUTH_FAILED) return t('settings.security.webauthn.reauth.failed')
    if (code === ERROR_REAUTH_REQUIRED) return t('settings.security.webauthn.reauth.expired')
    // 429（AUTH_044）の待ち時間・メールOTP 送信の上限などは 2FA 設定の写像を共用する。未知のエラーは generic。
    const info = describeTwoFactorError(e)
    return t(`settings.security.two_fa.error.${info.key}`, info.params ?? {})
  }

  /** パスキーで再認証する。成功でなければ理由を利用者へ見せる（キャンセルは黙って中断）。 */
  async function reauthenticateByPasskey(): Promise<boolean> {
    const result = await reauthenticateWithPasskey('PASSKEY_MANAGE')
    if (result === 'ok') return true
    if (result === 'uv_unavailable') notification.error(t('settings.security.webauthn.reauth.uv_unavailable'))
    else if (result === 'failed') notification.error(t('settings.security.webauthn.reauth.passkey_failed'))
    return false
  }

  /**
   * 再認証を完了させる。成功で true、キャンセル・失敗で false（失敗は利用者へ通知済み）。
   * 同時に 2 回呼ばれたら、先のダイアログは取り消す。
   */
  async function ensureReauthenticated(): Promise<boolean> {
    settle(false)
    let method: PasskeyManageReauthMethod | undefined
    try {
      method = (await authApi.getPasskeyManageReauthMethod()).data.method
    }
    catch (e) {
      notification.error(describeFailure(e))
      return false
    }
    if (!method) {
      notification.error(t('settings.security.two_fa.error.generic'))
      return false
    }
    if (method === 'PASSKEY') return reauthenticateByPasskey()

    dialogMethod.value = method
    errorMessage.value = ''
    reauthEmail.value = null
    dialogVisible.value = true
    return new Promise<boolean>((resolve) => {
      pending = resolve
    })
  }

  async function submit(body: PasskeyManageReauthRequest): Promise<void> {
    busy.value = true
    errorMessage.value = ''
    try {
      await authApi.reauthenticateForPasskeyManage(body)
      dialogVisible.value = false
      settle(true)
    }
    catch (e) {
      // 握りつぶさず、ダイアログ内に理由を出して再入力させる（ダイアログは開いたまま）
      errorMessage.value = describeFailure(e)
    }
    finally {
      busy.value = false
    }
  }

  /** 本人確認メールOTP（REAUTH）を送る。EMAIL_OTP 手段は専用 API、2FA 要素としてのメールは 2FA 用の送信 API。 */
  async function sendEmail(): Promise<void> {
    busy.value = true
    errorMessage.value = ''
    try {
      reauthEmail.value = dialogMethod.value === 'EMAIL_OTP'
        ? (await authApi.sendPasskeyManageReauthEmailOtp()).data
        : (await api<{ data: MfaEmailSendResponse }>('/api/v1/auth/2fa/reauth/email/send', { method: 'POST' })).data
    }
    catch (e) {
      errorMessage.value = describeFailure(e)
    }
    finally {
      busy.value = false
    }
  }

  /** ダイアログの閉じる操作（キャンセル）。待機中の再認証は false で終わる。 */
  function onDialogVisibleChange(visible: boolean) {
    dialogVisible.value = visible
    if (!visible) settle(false)
  }

  /**
   * 再認証してから操作を実行する。操作が AUTH_087（期限切れ・別セッション等）を返したら、
   * 期限切れを知らせて再認証からやり直す（再試行は 1 回）。
   *
   * @returns 'cancelled' は再認証が済まなかった（キャンセル・失敗）。それ以外は操作の結果。
   */
  async function runWithReauth(action: () => Promise<PasskeyManageOutcome>): Promise<PasskeyManageOutcome | 'cancelled'> {
    let outcome: PasskeyManageOutcome = 'reauth_required'
    for (let attempt = 0; attempt < 2 && outcome === 'reauth_required'; attempt++) {
      if (attempt > 0) notification.warn(t('settings.security.webauthn.reauth.expired'))
      if (!(await ensureReauthenticated())) return 'cancelled'
      outcome = await action()
    }
    if (outcome === 'reauth_required') notification.error(t('settings.security.webauthn.reauth.expired'))
    return outcome
  }

  return {
    dialogVisible,
    dialogMethod,
    busy,
    errorMessage,
    reauthEmail,
    ensureReauthenticated,
    runWithReauth,
    submit,
    sendEmail,
    onDialogVisibleChange,
  }
}
