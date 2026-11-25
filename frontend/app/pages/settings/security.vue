<script setup lang="ts">
import type { AuthSessionResponse, WebAuthnCredentialResponse } from '~/types/auth'
import { useWebAuthnRegister } from '~/composables/useWebAuthnRegister'
import { ERROR_REAUTH_REQUIRED, usePasskeyManageReauth } from '~/composables/usePasskeyManageReauth'
import type { PasskeyManageOutcome } from '~/composables/usePasskeyManageReauth'
import { parseApiError } from '~/utils/apiError'

definePageMeta({
  middleware: 'auth',
})

const notification = useNotification()
const authStore = useAuthStore()
const {
  getSessions,
  revokeSession,
  revokeAllSessions,
  getWebAuthnCredentials,
  deleteWebAuthnCredential,
  updateWebAuthnCredential,
} = useAuthApi()
const { getProfile } = useUserSettingsApi()

const loading = ref(true)
const sessions = ref<AuthSessionResponse[]>([])
const credentials = ref<WebAuthnCredentialResponse[]>([])

// 2FA の本人確認でパスワード欄を出すかどうか（OAuth のみの利用者は false）
const hasPassword = ref(true)

// WebAuthn rename
const renameDialog = ref(false)
const renameTarget = ref<WebAuthnCredentialResponse | null>(null)
const newDeviceName = ref('')

// WebAuthn register
const { registerPasskey, isRegistering: isRegisteringPasskey, error: registerError, errorCode: registerErrorCode } = useWebAuthnRegister()
// パスキー登録・削除の前の再認証（5分以内に実行。AUTH_087 なら再認証からやり直す）
const passkeyReauth = usePasskeyManageReauth()
const registerDialog = ref(false)
const newPasskeyName = ref('')

// 使い方ガイド（モーダル）
const showHelp = ref(false)

const { t } = useI18n()

onMounted(async () => {
  await loadData()
})

async function loadData() {
  loading.value = true
  try {
    const [sessRes, credRes, profileRes] = await Promise.all([
      getSessions(),
      getWebAuthnCredentials(),
      getProfile(),
    ])
    sessions.value = sessRes.data
    credentials.value = credRes.data
    hasPassword.value = profileRes.data.hasPassword
  } catch {
    notification.error('セキュリティ情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

// === Sessions ===
async function handleRevokeSession(id: number) {
  try {
    await revokeSession(id)
    sessions.value = sessions.value.filter((s) => s.id !== id)
    notification.success('セッションを無効化しました')
  } catch {
    notification.error('セッションの無効化に失敗しました')
  }
}

async function handleRevokeAllSessions() {
  try {
    await revokeAllSessions()
    notification.success('全デバイスからログアウトしました')
    authStore.logout()
  } catch {
    notification.error('全デバイスログアウトに失敗しました')
  }
}

// === WebAuthn ===
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
    notification.success('セキュリティキーを削除しました')
  } else if (outcome === 'failed') {
    notification.error('セキュリティキーの削除に失敗しました')
  }
}

function openRenameDialog(cred: WebAuthnCredentialResponse) {
  renameTarget.value = cred
  newDeviceName.value = cred.deviceName ?? ''
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
    notification.success('デバイス名を更新しました')
  } catch {
    notification.error('デバイス名の更新に失敗しました')
  }
}

// 登録ボタン押下: 本人確認を先に済ませてからデバイス名ダイアログを開く（モーダル on モーダルを避ける）
async function startPasskeyRegister() {
  if (!(await passkeyReauth.ensureReauthenticated())) return
  registerDialog.value = true
}

async function handlePasskeyRegister() {
  const deviceName = newPasskeyName.value.trim() || undefined
  if (await registerPasskey(deviceName)) {
    registerDialog.value = false
    newPasskeyName.value = ''
    notification.success(t('settings.security.webauthn.register_success'))
    // 資格情報一覧を再取得
    try {
      const credRes = await getWebAuthnCredentials()
      credentials.value = credRes.data
    } catch {
      // 再取得失敗は非致命的。次回リロード時に反映される。
    }
  } else if (registerErrorCode.value === ERROR_REAUTH_REQUIRED) {
    // 再認証が期限切れ（5分）等。デバイス名ダイアログを閉じてから本人確認をやり直す（入力した名前は残す）
    registerDialog.value = false
    notification.warn(t('settings.security.webauthn.reauth.expired'))
    await startPasskeyRegister()
  } else if (registerError.value) {
    notification.error(t('settings.security.webauthn.register_error'))
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader title="セキュリティ" help @help="showHelp = true" />

    <SecurityHelpDialog v-model:visible="showHelp" />

    <SettingsPasskeyReauthDialog
      :visible="passkeyReauth.dialogVisible.value"
      :method="passkeyReauth.dialogMethod.value"
      :busy="passkeyReauth.busy.value"
      :error-message="passkeyReauth.errorMessage.value"
      :reauth-email="passkeyReauth.reauthEmail.value"
      :two-fa-method="passkeyReauth.twoFaMethod.value"
      @submit="passkeyReauth.submit"
      @send-email="passkeyReauth.sendEmail"
      @update:visible="passkeyReauth.onDialogVisibleChange"
    />

    <PageLoading v-if="loading" />

    <div v-else class="fade-in space-y-8">
      <SettingsTwoFactorSection :has-password="hasPassword" />

      <SettingsSecuritySection
        :sessions="sessions"
        :credentials="credentials"
        :rename-dialog="renameDialog"
        :new-device-name="newDeviceName"
        :is-registering-passkey="isRegisteringPasskey"
        :register-dialog="registerDialog"
        :new-passkey-name="newPasskeyName"
        @revoke-session="handleRevokeSession"
        @revoke-all-sessions="handleRevokeAllSessions"
        @delete-credential="handleDeleteCredential"
        @open-rename-dialog="openRenameDialog"
        @rename-credential="handleRenameCredential"
        @passkey-register-start="startPasskeyRegister"
        @passkey-register="handlePasskeyRegister"
        @update:rename-dialog="renameDialog = $event"
        @update:new-device-name="newDeviceName = $event"
        @update:register-dialog="registerDialog = $event"
        @update:new-passkey-name="newPasskeyName = $event"
      />
    </div>
  </div>
</template>
