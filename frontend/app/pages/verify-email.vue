<script setup lang="ts">
definePageMeta({
  layout: 'auth',
})

const route = useRoute()
const returnPath = computed(() => returnRedirect(route.query.redirect))
const loginTarget = computed(() => ({
  path: '/login',
  query: returnPath.value ? { redirect: returnPath.value } : {},
}))
const api = useApi()
const notification = useNotification()
const { t } = useI18n()

const token = computed(() => route.query.token as string | undefined)
const email = computed(() => route.query.email as string | undefined)

const verifying = ref(false)
const verified = ref(false)
// 検証失敗時の本文（BE の message 優先・無ければ既定文言）。null は未失敗。
const verifyError = ref<string | null>(null)
const resending = ref(false)

// BE は利用者の言語で data.error.message を返す。code の有無を問わず優先する。
function apiErrorMessage(err: unknown): string | undefined {
  const message = (err as { data?: { error?: { message?: unknown } } } | null)?.data?.error?.message
  return typeof message === 'string' && message ? message : undefined
}

async function verifyToken() {
  if (!token.value) return
  verifying.value = true
  verifyError.value = null
  try {
    await api('/api/v1/auth/verify-email', {
      method: 'POST',
      body: { token: token.value },
    })
    verified.value = true
    notification.success(t('auth.verify_email.success_toast'))
    setTimeout(() => navigateTo(loginTarget.value), 2000)
  }
  catch (err) {
    verifyError.value = apiErrorMessage(err) || t('auth.verify_email.failed_description')
    notification.error(t('auth.verify_email.failed_toast_title'), verifyError.value)
  }
  finally {
    verifying.value = false
  }
}

async function resendVerification() {
  if (!email.value) return
  resending.value = true
  try {
    await api('/api/v1/auth/verify-email/resend', {
      method: 'POST',
      body: { email: email.value },
    })
    notification.success(t('auth.verify_email.resend_success'))
  }
  catch (err) {
    notification.error(
      t('auth.verify_email.resend_failed_title'),
      apiErrorMessage(err) || t('auth.verify_email.resend_failed_message'),
    )
  }
  finally {
    resending.value = false
  }
}

onMounted(() => {
  if (token.value) {
    verifyToken()
  }
})
</script>

<template>
  <div class="flex flex-col items-center gap-6">
    <!-- トークン検証中 -->
    <template v-if="token">
      <template v-if="verifying">
        <LoadingBounce />
        <p class="text-center text-lg">{{ $t('auth.verify_email.verifying') }}</p>
      </template>

      <template v-else-if="verified">
        <i class="pi pi-check-circle text-5xl text-green-500" />
        <h2 class="text-xl font-bold">{{ $t('auth.verify_email.success_title') }}</h2>
        <p class="text-center text-surface-500">
          {{ $t('auth.verify_email.success_description') }}
        </p>
        <Button
          :label="$t('auth.verify_email.to_login')"
          icon="pi pi-sign-in"
          class="w-full"
          @click="navigateTo(loginTarget)"
        />
      </template>

      <template v-else-if="verifyError !== null">
        <i class="pi pi-times-circle text-5xl text-red-500" />
        <h2 class="text-xl font-bold">{{ $t('auth.verify_email.failed_title') }}</h2>
        <p class="text-center text-surface-500">
          {{ verifyError }}
        </p>
        <Button
          v-if="email"
          :label="$t('auth.verify_email.resend_button')"
          icon="pi pi-envelope"
          :loading="resending"
          class="w-full"
          @click="resendVerification"
        />
        <NuxtLink :to="loginTarget" class="text-sm text-primary hover:underline">
          {{ $t('auth.verify_email.back_to_login') }}
        </NuxtLink>
      </template>
    </template>

    <!-- トークンなし（登録直後の案内画面） -->
    <template v-else>
      <i class="pi pi-envelope text-5xl text-primary" />
      <h2 class="text-xl font-bold">{{ $t('auth.verify_email.sent_title') }}</h2>
      <p class="text-center text-surface-500">
        {{ email ? $t('auth.verify_email.sent_description_to', { email }) : $t('auth.verify_email.sent_description') }}
      </p>
      <Button
        v-if="email"
        :label="$t('auth.verify_email.resend_button')"
        icon="pi pi-envelope"
        outlined
        :loading="resending"
        class="w-full"
        @click="resendVerification"
      />
      <NuxtLink :to="loginTarget" class="text-sm text-primary hover:underline">
        {{ $t('auth.verify_email.back_to_login') }}
      </NuxtLink>
    </template>
  </div>
</template>
