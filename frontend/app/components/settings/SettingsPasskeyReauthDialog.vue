<script setup lang="ts">
import type { MfaEmailSendResponse, PasskeyManageReauthMethod, PasskeyManageReauthRequest } from '~/composables/useAuthApi'
import { passkeyReauthNeedsFactor, passkeyReauthNeedsPassword } from '~/utils/passkeyReauthMethod'

/**
 * パスキーの登録・削除の前に挟む本人確認ダイアログ（CMP-261021-1338 B5/B9）。
 *
 * 手段は BE の GET /reauthenticate-method で決まり、画面側では選ばせない。
 * - PASSWORD: パスワード
 * - PASSWORD_AND_SECOND_FACTOR: パスワード + 2FA 要素（TOTP / 本人確認メールOTP / バックアップコード）
 * - SECOND_FACTOR: 2FA 要素のみ（パスワードを持たない SNS ログイン + 2FA 有効の利用者。パスワード欄は出さない）
 * - EMAIL_OTP: 本人確認メールOTP（パスワードも 2FA も無い SNS ログインのみの利用者）
 * PASSKEY（パスキー保有者）はこのダイアログを使わず、OS の認証ダイアログ（UV 必須）で再認証する。
 */
type DialogMethod = Exclude<PasskeyManageReauthMethod, 'PASSKEY'>
type Factor = NonNullable<PasskeyManageReauthRequest['method']>

const props = defineProps<{
  visible: boolean
  method: DialogMethod
  busy: boolean
  errorMessage: string
  /** 本人確認メールOTP の送信結果（未送信なら null）。 */
  reauthEmail: MfaEmailSendResponse | null
  /** 利用者の 2FA 方式（GET /2fa/status）。使えない 2FA 要素を選択肢に出さないために使う。 */
  twoFaMethod?: 'TOTP' | 'EMAIL'
}>()

const emit = defineEmits<{
  submit: [body: PasskeyManageReauthRequest]
  sendEmail: []
  'update:visible': [value: boolean]
}>()

const { t } = useI18n()

const password = ref('')
const factor = ref<Factor>('TOTP')
const code = ref('')
const resendRemaining = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

const needsPassword = computed(() => passkeyReauthNeedsPassword(props.method))
const needsFactor = computed(() => passkeyReauthNeedsFactor(props.method))
/** EMAIL_OTP 手段のコードは常にメールOTP。2FA 要素と併用するときは選択された要素。 */
const activeFactor = computed<Factor>(() => (props.method === 'EMAIL_OTP' ? 'EMAIL' : factor.value))
const isOtpFactor = computed(() => activeFactor.value !== 'BACKUP_CODE')
const showFactorSelect = computed(() => props.method === 'PASSWORD_AND_SECOND_FACTOR' || props.method === 'SECOND_FACTOR')
const showEmailSend = computed(() => needsFactor.value && activeFactor.value === 'EMAIL')

/**
 * 2FA 方式が EMAIL なら TOTP は出さない。SECOND_FACTOR では BE がメールOTP を「2FA 方式が EMAIL のとき」だけ
 * 要素として受けるため、TOTP 方式の利用者にはメールを出さない（2FA 方式が不明なら全て出す）。
 */
const initialFactor = computed<Factor>(() => (props.twoFaMethod === 'EMAIL' ? 'EMAIL' : 'TOTP'))
const factorOptions = computed(() => {
  const options: { label: string, value: Factor }[] = []
  if (props.twoFaMethod !== 'EMAIL') {
    options.push({ label: t('settings.security.two_fa.reauth.method_totp'), value: 'TOTP' })
  }
  if (!(props.method === 'SECOND_FACTOR' && props.twoFaMethod === 'TOTP')) {
    options.push({ label: t('settings.security.two_fa.reauth.method_email'), value: 'EMAIL' })
  }
  options.push({ label: t('settings.security.two_fa.reauth.method_backup'), value: 'BACKUP_CODE' })
  return options
})

const normalizedCode = computed(() => (isOtpFactor.value ? code.value : code.value.replace(/[\s-]/g, '')))
const codeReady = computed(() => (isOtpFactor.value ? /^\d{6}$/.test(code.value) : normalizedCode.value.length > 0))
const reauthReady = computed(() => (!needsPassword.value || password.value.length > 0) && (!needsFactor.value || codeReady.value))
const canSubmit = computed(() => reauthReady.value && !props.busy)

function stopTimer() {
  if (timer) clearInterval(timer)
  timer = null
}

function startCountdown(seconds: number) {
  stopTimer()
  resendRemaining.value = seconds
  if (seconds <= 0) return
  timer = setInterval(() => {
    resendRemaining.value -= 1
    if (resendRemaining.value <= 0) stopTimer()
  }, 1000)
}

watch(
  () => props.visible,
  (v) => {
    if (!v) {
      stopTimer()
      return
    }
    password.value = ''
    factor.value = initialFactor.value
    code.value = ''
    resendRemaining.value = 0
  },
  { immediate: true },
)

watch(
  () => props.reauthEmail,
  (mail) => {
    if (mail) startCountdown(mail.resendAfterSeconds ?? 0)
  },
)

// 要素を切り替えたらコードを消す（別要素のコードを誤送信しない）
watch(factor, () => {
  code.value = ''
})

onUnmounted(stopTimer)

function submit() {
  if (!canSubmit.value) return
  emit('submit', {
    ...(needsPassword.value ? { currentPassword: password.value } : {}),
    ...(needsFactor.value ? { method: activeFactor.value, code: normalizedCode.value } : {}),
  })
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="$t('settings.security.webauthn.reauth.title')"
    :modal="true"
    class="w-full max-w-md"
    data-testid="passkey-reauth-dialog"
    @update:visible="emit('update:visible', $event)"
  >
    <form class="space-y-4" @submit.prevent="submit">
      <!-- エラーは本文の先頭に置く。末尾だと狭い画面でフッターの陰に隠れて利用者に見えない。 -->
      <Message v-if="errorMessage" severity="error" :closable="false" data-testid="passkey-reauth-error">
        {{ errorMessage }}
      </Message>

      <p class="text-sm text-surface-500" data-testid="passkey-reauth-description">
        {{ $t('settings.security.webauthn.reauth.description') }}
      </p>

      <div v-if="needsPassword" class="flex flex-col gap-2">
        <label for="passkeyReauthPassword" class="text-sm font-semibold">
          {{ $t('settings.security.webauthn.reauth.password_label') }}
        </label>
        <Password
          v-model="password"
          input-id="passkeyReauthPassword"
          :feedback="false"
          toggle-mask
          fluid
          data-testid="passkey-reauth-password"
        />
      </div>

      <div v-if="showFactorSelect" class="flex flex-col gap-2">
        <span class="text-sm font-semibold">{{ $t('settings.security.two_fa.reauth.method_label') }}</span>
        <SelectButton
          v-model="factor"
          :options="factorOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          data-testid="passkey-reauth-factor"
        />
      </div>

      <div v-if="showEmailSend" class="space-y-2">
        <Button
          type="button"
          translate="no"
          :label="
            resendRemaining > 0
              ? $t('settings.security.two_fa.email.resend_wait', { seconds: resendRemaining })
              : reauthEmail
                ? $t('settings.security.two_fa.email.resend_button')
                : $t('settings.security.two_fa.reauth.send_email_button')
          "
          icon="pi pi-envelope"
          severity="secondary"
          size="small"
          :disabled="busy || resendRemaining > 0"
          data-testid="passkey-reauth-send-email"
          @click="emit('sendEmail')"
        />
        <p v-if="reauthEmail" class="text-xs text-surface-500" data-testid="passkey-reauth-email-sent">
          {{
            $t('settings.security.two_fa.email.sent', {
              email: reauthEmail.maskedEmail ?? '',
              minutes: Math.max(1, Math.round((reauthEmail.expiresInSeconds ?? 60) / 60)),
            })
          }}
        </p>
      </div>

      <div v-if="needsFactor" class="flex flex-col gap-2">
        <label class="text-sm font-semibold">
          {{
            activeFactor === 'BACKUP_CODE'
              ? $t('settings.security.two_fa.reauth.code_label_backup')
              : $t('settings.security.webauthn.reauth.otp_label')
          }}
        </label>
        <InputOtp v-if="isOtpFactor" :key="activeFactor" v-model="code" :length="6" integer-only data-testid="passkey-reauth-otp" />
        <InputText v-else v-model="code" autocomplete="off" fluid data-testid="passkey-reauth-backup-input" />
      </div>

      <div class="flex justify-end gap-2">
        <Button
          type="button"
          translate="no"
          :label="$t('button.cancel')"
          severity="secondary"
          data-testid="passkey-reauth-cancel"
          @click="emit('update:visible', false)"
        />
        <Button
          type="submit"
          translate="no"
          :label="$t('settings.security.webauthn.reauth.submit')"
          :loading="busy"
          :disabled="!canSubmit"
          data-testid="passkey-reauth-submit"
        />
      </div>
    </form>
  </Dialog>
</template>
