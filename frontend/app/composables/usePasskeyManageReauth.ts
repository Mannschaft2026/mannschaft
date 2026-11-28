import { ref } from 'vue'
import { useAuthApi } from './useAuthApi'
import type { MfaEmailSendResponse, PasskeyManageReauthMethod, PasskeyManageReauthRequest } from './useAuthApi'
import { usePasskeyReauth } from './usePasskeyReauth'
import { describeTwoFactorError } from '~/utils/twoFactorError'
import { parseApiError } from '~/utils/apiError'
import { passkeyReauthNeedsFactor } from '~/utils/passkeyReauthMethod'

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
 *
 * <p>iOS/Safari は navigator.credentials.get をユーザー操作の直後（アクティベーションが生きている間）にしか受け付けない。
 * そのため (1) 手段の取得は画面表示時に {@link prefetchMethod} で先読みし、ボタン押下から get までの通信を begin の 1 本にする、
 * (2) AUTH_087 の再試行は自動で get を呼ばず、「本人確認をやり直す」ボタン（{@code retryVisible}）の押下を起点にする。</p>
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
  /** 2FA 方式（2FA 要素を選ばせる手段のときだけ取得）。ダイアログで使えない要素を出さないための表示ヒント。 */
  const twoFaMethod = ref<'TOTP' | 'EMAIL' | undefined>(undefined)

  /** AUTH_087 後の「本人確認をやり直す」ダイアログ。パスキー再認証はこのボタン押下を起点に行う。 */
  const retryVisible = ref(false)
  /** 登録の再試行のとき true（端末側に残ったパスキーの案内を出す）。 */
  const retryLeftoverNote = ref(false)

  let pending: ((ok: boolean) => void) | null = null
  let retryPending: ((go: boolean) => void) | null = null
  /** 先読みした再認証手段。ボタン押下から get までの通信を減らす（古くなりうるので操作後に取り直す）。 */
  let cachedMethod: PasskeyManageReauthMethod | undefined

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

  /**
   * 再認証手段を先読みする（画面表示時・操作後に呼ぶ）。失敗してもここでは通知しない:
   * 押下時に {@link ensureReauthenticated} が取り直し、そこで失敗を利用者へ見せる（先読みは最適化であり必須ではない）。
   */
  async function prefetchMethod(): Promise<void> {
    try {
      cachedMethod = (await authApi.getPasskeyManageReauthMethod()).data.method
    }
    catch (e) {
      cachedMethod = undefined
      if (import.meta.dev) console.warn('[passkey-manage-reauth] prefetch failed', e)
    }
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
    let method: PasskeyManageReauthMethod | undefined = cachedMethod
    if (!method) {
      try {
        method = (await authApi.getPasskeyManageReauthMethod()).data.method
        cachedMethod = method
      }
      catch (e) {
        notification.error(describeFailure(e))
        return false
      }
    }
    if (!method) {
      notification.error(t('settings.security.two_fa.error.generic'))
      return false
    }
    if (method === 'PASSKEY') return reauthenticateByPasskey()

    dialogMethod.value = method
    errorMessage.value = ''
    reauthEmail.value = null
    twoFaMethod.value = undefined
    if (passkeyReauthNeedsFactor(method) && method !== 'EMAIL_OTP') {
      try {
        twoFaMethod.value = (await api<{ data: { method?: 'TOTP' | 'EMAIL' } }>('/api/v1/auth/2fa/status')).data?.method
      }
      catch {
        // 表示ヒントの取得失敗は致命的でない（全要素を出す）。要素の正否は BE が検証して AUTH_080 で返す。
        twoFaMethod.value = undefined
      }
    }
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

  function settleRetry(go: boolean) {
    const resolve = retryPending
    retryPending = null
    retryVisible.value = false
    resolve?.(go)
  }

  /** 「本人確認をやり直す」ボタン。この押下を起点に begin → get まで進める（ユーザー操作のアクティベーションを保つ）。 */
  function confirmRetry() {
    settleRetry(true)
  }

  function onRetryVisibleChange(visible: boolean) {
    if (!visible) settleRetry(false)
  }

  /**
   * AUTH_087（期限切れ・別セッション等）の後の再認証。パスキー再認証は自動で始めず、利用者に
   * 「本人確認をやり直す」ボタンを押させ、その押下から get を呼ぶ（iOS/Safari のアクティベーション切れ対策）。
   * パスワード等のダイアログ手段は WebAuthn を使わないので、通知してそのままダイアログを開く。
   *
   * @param leftoverPasskey 登録の再試行のとき true。端末側に残ったパスキーの案内を添える（BE には登録されていない）。
   */
  async function reauthenticateAfterExpiry(leftoverPasskey = false): Promise<boolean> {
    if (!cachedMethod) await prefetchMethod()
    if (cachedMethod === 'PASSKEY') {
      settleRetry(false)
      retryLeftoverNote.value = leftoverPasskey
      retryVisible.value = true
      const go = await new Promise<boolean>((resolve) => {
        retryPending = resolve
      })
      if (!go) return false
      return ensureReauthenticated()
    }
    notification.warn(leftoverPasskey
      ? `${t('settings.security.webauthn.reauth.expired')} ${t('settings.security.webauthn.reauth.leftover_passkey')}`
      : t('settings.security.webauthn.reauth.expired'))
    return ensureReauthenticated()
  }

  /**
   * 再認証してから操作を実行する。操作が AUTH_087（期限切れ・別セッション等）を返したら、
   * 期限切れを知らせ、「本人確認をやり直す」操作を経て再認証からやり直す（再試行は 1 回）。
   *
   * @returns 'cancelled' は再認証が済まなかった（キャンセル・失敗）。それ以外は操作の結果。
   */
  async function runWithReauth(action: () => Promise<PasskeyManageOutcome>): Promise<PasskeyManageOutcome | 'cancelled'> {
    let outcome: PasskeyManageOutcome = 'reauth_required'
    for (let attempt = 0; attempt < 2 && outcome === 'reauth_required'; attempt++) {
      const ok = attempt === 0 ? await ensureReauthenticated() : await reauthenticateAfterExpiry()
      if (!ok) return 'cancelled'
      outcome = await action()
    }
    // 操作でパスキーの有無が変わると手段も変わる。次の押下に備えて取り直す（結果は待たない）
    void prefetchMethod()
    if (outcome === 'reauth_required') notification.error(t('settings.security.webauthn.reauth.expired'))
    return outcome
  }

  return {
    dialogVisible,
    dialogMethod,
    busy,
    errorMessage,
    reauthEmail,
    twoFaMethod,
    retryVisible,
    retryLeftoverNote,
    prefetchMethod,
    ensureReauthenticated,
    reauthenticateAfterExpiry,
    confirmRetry,
    onRetryVisibleChange,
    runWithReauth,
    submit,
    sendEmail,
    onDialogVisibleChange,
  }
}
