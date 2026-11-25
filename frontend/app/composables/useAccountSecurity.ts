import type { components } from '~/types/generated'
import type { AuthSessionResponse, WebAuthnCredentialResponse } from '~/types/auth'
import type { LoginHistoryResponse } from '~/types/user-settings'
import { useWebAuthnRegister } from '~/composables/useWebAuthnRegister'
import { parseApiError } from '~/utils/apiError'
import { ERROR_REAUTH_REQUIRED, usePasskeyManageReauth } from '~/composables/usePasskeyManageReauth'
import type { PasskeyManageOutcome } from '~/composables/usePasskeyManageReauth'
import { describeTwoFactorError } from '~/utils/twoFactorError'

export function useAccountSecurity() {
  const notification = useNotification()
  const { t } = useI18n()
  const {
    getSessions,
    revokeSession,
    revokeAllSessions,
    getWebAuthnCredentials,
    deleteWebAuthnCredential,
    updateWebAuthnCredential,
  } = useAuthApi()
  const { getLoginHistory } = useUserSettingsApi()

  const sessions = ref<AuthSessionResponse[]>([])
  const credentials = ref<WebAuthnCredentialResponse[]>([])
  const renameDialog = ref(false)
  const renameTarget = ref<WebAuthnCredentialResponse | null>(null)
  const newDeviceName = ref('')
  const { registerPasskey, isRegistering: isRegisteringPasskey, error: registerError, errorCode: registerErrorCode } = useWebAuthnRegister()
  // パスキー登録・削除の前の再認証（5分以内に実行。AUTH_087 なら再認証からやり直す）
  const passkeyReauth = usePasskeyManageReauth()
  const registerDialog = ref(false)
  const newPasskeyName = ref('')

  const loginHistory = ref<LoginHistoryResponse[]>([])
  const loginHistoryNextCursor = ref<string | null>(null)
  const loginHistoryHasNext = ref(false)
  const loadingMoreHistory = ref(false)

  async function loadSecurity() {
    try {
      const [sessRes, credRes] = await Promise.all([getSessions(), getWebAuthnCredentials()])
      sessions.value = sessRes.data
      credentials.value = credRes.data
    } catch {
      /* silent */
    }
  }

  async function loadLoginHistory(cursor?: string) {
    if (cursor) loadingMoreHistory.value = true
    try {
      const res = await getLoginHistory(cursor, 10)
      if (cursor) loginHistory.value.push(...res.data)
      else loginHistory.value = res.data
      loginHistoryNextCursor.value = res.meta.nextCursor
      loginHistoryHasNext.value = res.meta.hasNext
    } catch {
      /* silent */
    } finally {
      loadingMoreHistory.value = false
    }
  }

  async function handleRevokeSession(id: number) {
    try {
      await revokeSession(id)
      sessions.value = sessions.value.filter((s) => s.id !== id)
      notification.success(t('settings.security.toast.revoke_success'))
    } catch {
      notification.error(t('settings.security.toast.revoke_error'))
    }
  }

  async function handleRevokeAllSessions() {
    try {
      await revokeAllSessions()
      sessions.value = []
      notification.success(t('settings.security.toast.revoke_all_success'))
    } catch {
      notification.error(t('settings.security.toast.revoke_all_error'))
    }
  }

  async function handleDeleteCredential(id: number) {
    const outcome = await passkeyReauth.runWithReauth(async (): Promise<PasskeyManageOutcome> => {
      try {
        await deleteWebAuthnCredential(id)
        return 'done'
      } catch (e) {
        return parseApiError(e).code === ERROR_REAUTH_REQUIRED ? 'reauth_required' : 'failed'
      }
    })
    if (outcome === 'done') {
      credentials.value = credentials.value.filter((c) => c.id !== id)
      notification.success(t('settings.security.toast.delete_key_success'))
    } else if (outcome === 'failed') {
      notification.error(t('settings.security.toast.delete_key_error'))
    }
  }

  function openRenameDialog(cred: WebAuthnCredentialResponse) {
    renameTarget.value = cred
    newDeviceName.value = cred.deviceName
    renameDialog.value = true
  }

  async function handleRenameCredential() {
    if (!renameTarget.value) return
    try {
      const res = await updateWebAuthnCredential(renameTarget.value.id, {
        deviceName: newDeviceName.value,
      })
      const idx = credentials.value.findIndex((c) => c.id === renameTarget.value!.id)
      if (idx !== -1) credentials.value[idx] = res.data
      renameDialog.value = false
      notification.success(t('settings.security.toast.rename_success'))
    } catch {
      notification.error(t('settings.security.toast.rename_error'))
    }
  }

  /**
   * 登録ボタン押下。本人確認（再認証）を先に済ませ、通ってからデバイス名ダイアログを開く。
   * 本人確認ダイアログとデバイス名ダイアログが同時に開く（モーダル on モーダル）ことはない。
   */
  async function startPasskeyRegister() {
    if (!(await passkeyReauth.ensureReauthenticated())) return
    registerDialog.value = true
  }

  async function handlePasskeyRegister() {
    if (await registerPasskey(newPasskeyName.value.trim() || undefined)) {
      registerDialog.value = false
      newPasskeyName.value = ''
      notification.success(t('settings.security.webauthn.register_success'))
      await loadSecurity()
    } else if (registerErrorCode.value === ERROR_REAUTH_REQUIRED) {
      // 再認証が期限切れ（5分）等。デバイス名ダイアログを閉じてから本人確認をやり直す（入力した名前は残す）
      registerDialog.value = false
      notification.warn(t('settings.security.webauthn.reauth.expired'))
      await startPasskeyRegister()
    } else if (registerError.value) {
      notification.error(t('settings.security.webauthn.register_error'))
    }
  }

  return {
    passkeyReauth,
    isRegisteringPasskey,
    registerDialog,
    newPasskeyName,
    startPasskeyRegister,
    handlePasskeyRegister,
    sessions,
    credentials,
    renameDialog,
    renameTarget,
    newDeviceName,
    loginHistory,
    loginHistoryNextCursor,
    loginHistoryHasNext,
    loadingMoreHistory,
    loadSecurity,
    loadLoginHistory,
    handleRevokeSession,
    handleRevokeAllSessions,
    handleDeleteCredential,
    openRenameDialog,
    handleRenameCredential,
  }
}

// ============================================================
// 2段階認証（2FA）設定（CMP-261012-0910 丁隊 AC-2〜5, 29, 34, 44）
// ============================================================

type Schemas = components['schemas']
type TwoFactorStatus = Schemas['TwoFactorStatusResponse']
type MfaEmailSend = Schemas['MfaEmailSendResponse']

/** 本人確認に使える要素。 */
export type TwoFactorFactor = 'TOTP' | 'BACKUP_CODE' | 'EMAIL'

/**
 * 無効化・バックアップコード再生成で BE へ渡す本人確認情報。
 * 無効化（AC-30）は要素が必須（パスワード保持者はパスワードも）。再生成（AC-44）はパスワードか要素のどちらかでよく、
 * 提示しなかった側は undefined のまま送らない（示したものはすべて BE で照合される）。
 */
export interface TwoFactorReauthInput {
  method?: TwoFactorFactor
  code?: string
  /** 入力した場合のみ。パスワードを持たない利用者（OAuth のみ等）は常に undefined。 */
  currentPassword?: string
}

export function useTwoFactorSettings() {
  const api = useApi()
  const notification = useNotification()
  const { t } = useI18n()

  const status = ref<TwoFactorStatus | null>(null)
  const loadingStatus = ref(false)
  const busy = ref(false)
  /** 直近の失敗を利用者へ見せるための文言（成功・キャンセル・次の操作開始でクリアする）。 */
  const errorMessage = ref('')
  /** BE のフラグが off（AUTH_082）と判明したら false。メール方式の開始ボタンを無効にする。 */
  const emailAvailable = ref(true)

  const totpSetup = ref<{ secret: string; qrCodeUrl: string } | null>(null)
  const emailSetup = ref<MfaEmailSend | null>(null)
  const reauthEmail = ref<MfaEmailSend | null>(null)

  const backupCodes = ref<string[]>([])
  const showBackupCodesDialog = ref(false)

  function fail(e: unknown) {
    const info = describeTwoFactorError(e)
    if (info.code === 'AUTH_082') emailAvailable.value = false
    errorMessage.value = t(`settings.security.two_fa.error.${info.key}`, info.params ?? {})
  }

  async function loadStatus(): Promise<void> {
    loadingStatus.value = true
    try {
      status.value = (await api<{ data: TwoFactorStatus }>('/api/v1/auth/2fa/status')).data
    } catch (e) {
      fail(e)
      notification.error(t('settings.security.two_fa.error.status_load'), errorMessage.value)
    } finally {
      loadingStatus.value = false
    }
  }

  /** 共通: busy 管理とエラー表示つきで API 呼び出しを実行し、成功時に結果を返す（失敗は null）。 */
  async function run<T>(action: () => Promise<T>): Promise<T | null> {
    // 送信中の連打・二重実行は黙って捨てる（同じ要求を 2 回送らない。ALIC-2）。エラー表示も出さない。
    if (busy.value) return null
    busy.value = true
    errorMessage.value = ''
    try {
      return await action()
    } catch (e) {
      fail(e)
      return null
    } finally {
      busy.value = false
    }
  }

  function showBackupCodes(codes: string[]) {
    backupCodes.value = codes
    showBackupCodesDialog.value = true
  }

  // === TOTP ===
  async function startTotpSetup(): Promise<void> {
    const res = await run(() =>
      api<{ data: { secret: string; qrCodeUrl: string } }>('/api/v1/auth/2fa/setup', { method: 'POST' }),
    )
    if (res) totpSetup.value = res.data
  }

  async function confirmTotpSetup(totpCode: string): Promise<boolean> {
    const res = await run(() =>
      api<{ data: Schemas['BackupCodesResponse'] }>('/api/v1/auth/2fa/verify', {
        method: 'POST',
        body: { totpCode },
      }),
    )
    if (!res) return false
    totpSetup.value = null
    showBackupCodes(res.data.backupCodes ?? [])
    notification.success(t('settings.security.two_fa.toast.enabled'))
    await loadStatus()
    return true
  }

  function cancelTotpSetup() {
    totpSetup.value = null
    errorMessage.value = ''
  }

  // === メール2FA ===
  async function startEmailSetup(): Promise<boolean> {
    const res = await run(() => api<{ data: MfaEmailSend }>('/api/v1/auth/2fa/email/setup', { method: 'POST' }))
    if (!res) return false
    emailSetup.value = res.data
    return true
  }

  async function confirmEmailSetup(code: string): Promise<boolean> {
    const res = await run(() =>
      api<{ data: Schemas['BackupCodesResponse'] }>('/api/v1/auth/2fa/email/enable', {
        method: 'POST',
        body: { code },
      }),
    )
    if (!res) return false
    emailSetup.value = null
    showBackupCodes(res.data.backupCodes ?? [])
    notification.success(t('settings.security.two_fa.toast.enabled'))
    await loadStatus()
    return true
  }

  function cancelEmailSetup() {
    emailSetup.value = null
    errorMessage.value = ''
  }

  // === 本人確認（無効化・再生成） ===
  async function sendReauthEmail(): Promise<boolean> {
    const res = await run(() =>
      api<{ data: MfaEmailSend }>('/api/v1/auth/2fa/reauth/email/send', { method: 'POST' }),
    )
    if (!res) return false
    reauthEmail.value = res.data
    return true
  }

  function resetReauth() {
    reauthEmail.value = null
    errorMessage.value = ''
  }

  function reauthBody(input: TwoFactorReauthInput) {
    return {
      ...(input.method && input.code ? { method: input.method, code: input.code } : {}),
      ...(input.currentPassword ? { currentPassword: input.currentPassword } : {}),
    }
  }

  async function disableTwoFactor(input: TwoFactorReauthInput): Promise<boolean> {
    const res = await run(() => api('/api/v1/auth/2fa/disable', { method: 'POST', body: reauthBody(input) }))
    if (res === null) return false
    notification.success(t('settings.security.two_fa.toast.disabled'))
    resetReauth()
    await loadStatus()
    return true
  }

  async function regenerateBackupCodes(input: TwoFactorReauthInput): Promise<boolean> {
    const res = await run(() =>
      api<{ data: Schemas['BackupCodesResponse'] }>('/api/v1/auth/2fa/backup-codes/regenerate', {
        method: 'POST',
        body: reauthBody(input),
      }),
    )
    if (!res) return false
    showBackupCodes(res.data.backupCodes ?? [])
    notification.success(t('settings.security.toast.regenerate_success'))
    resetReauth()
    await loadStatus()
    return true
  }

  function closeBackupCodesDialog() {
    showBackupCodesDialog.value = false
    backupCodes.value = []
  }

  return {
    status,
    loadingStatus,
    busy,
    errorMessage,
    emailAvailable,
    totpSetup,
    emailSetup,
    reauthEmail,
    backupCodes,
    showBackupCodesDialog,
    loadStatus,
    startTotpSetup,
    confirmTotpSetup,
    cancelTotpSetup,
    startEmailSetup,
    confirmEmailSetup,
    cancelEmailSetup,
    sendReauthEmail,
    resetReauth,
    disableTwoFactor,
    regenerateBackupCodes,
    closeBackupCodesDialog,
  }
}
