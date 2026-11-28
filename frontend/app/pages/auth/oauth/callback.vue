<script setup lang="ts">
definePageMeta({ layout: 'auth' })

const route = useRoute()
const router = useRouter()
const { loginWithOAuth } = useAuthApi()
const authStore = useAuthStore()
// 先回りリフレッシュ武装用に runtimeConfig を setup コンテキストで capture する
// （onMounted の await 後で useRuntimeConfig() を呼ぶ落とし穴を避ける。armProactiveRefresh 参照）。
const runtimeConfig = useRuntimeConfig()
const api = useApi()
const { t } = useI18n()
const notification = useNotification()
const { applyAccountLocale } = useLocale()

const error = ref<string | null>(null)
const loading = ref(true)

onMounted(async () => {
  const code = route.query.code as string | undefined
  const errorParam = route.query.error as string | undefined

  if (errorParam || !code) {
    error.value = t('auth.oauth.callback_error')
    loading.value = false
    return
  }

  try {
    const res = await loginWithOAuth('google', { code })
    const result = res.data
    // 応答は三択: MFA 要求（2FA 有効。トークンは未発行） / 連携競合（メール一致の既存アカウント） / トークン発行。
    // 2FA 有効ユーザーにトークンは返らないため、setTokens せず 2FA 検証画面へ進む（2FA を迂回させない）。
    if ('mfaRequired' in result && result.mfaRequired) {
      await router.push(buildMfaVerifyTarget(result))
      return
    }
    if (!('accessToken' in result)) {
      // 連携確認メール送信済み。ログイン画面で案内する
      await router.push('/login?oauthConflict=true')
      return
    }
    authStore.setTokens(result.accessToken, result.refreshToken)
    let isPendingConsent = false
    // プロフィールを取得してユーザー情報を更新
    try {
      const profile = await api<{
        data: {
          id: number
          email: string
          lastName: string
          firstName: string
          avatarUrl: string | null
          systemRole: string | null
          locale: string
          timezone: string | null
          status: string | null
        }
      }>('/api/v1/users/me')
      isPendingConsent = profile.data.status === PENDING_PARENTAL_CONSENT_STATUS
      await authStore.setUser({
        id: profile.data.id,
        email: profile.data.email,
        fullName: profile.data.lastName + ' ' + profile.data.firstName,
        profileImageUrl: profile.data.avatarUrl,
        systemRole: profile.data.systemRole ?? undefined,
        timezone: profile.data.timezone ?? undefined,
        locale: profile.data.locale || undefined,
        status: profile.data.status ?? undefined,
      })
      // ログイン時もアカウント言語を正として Cookie と表示を揃える（CMP-261007-1243）。
      // 同意待ちは BE ゲートで塞がれる API を呼ばない。
      if (!isPendingConsent) await applyAccountLocale(profile.data.locale)
    } catch {
      // プロフィール取得失敗はサイレント（トークンは取得済み）。
      // ただし同意待ち（ppc=true）は本人を復元できず pending へ着けない（user=null だと auth ガードが /login へ戻す）。
      // 通常扱いもしないので、共通のログイン失敗処理（サーバーログアウト）へ揃える。
      if (isParentalConsentPendingToken(result.accessToken)) {
        await authStore.serverLogout()
        error.value = t('auth.oauth.callback_error')
        return
      }
    }

    // 本人の復元による世代更新が終わってからタイマーを武装する。
    armProactiveRefresh(runtimeConfig, authStore)

    // ログイン成立直後にアカウント設定（外観・ナビ）をサーバーから同期する。
    // 新ブラウザ（シークレット等）で localStorage が空でも BEの保存済み設定が反映される。
    // 同意待ちは設定同期をせず pending 固定で遷移する。
    if (isPendingConsent) {
      await router.push(PARENTAL_CONSENT_PENDING_PATH)
      return
    }
    authStore.syncAccountSettings()

    notification.success(t('auth.login.success'))
    // 保護者の承認画面から来た場合は、保存済み token の承認画面へ戻る（一度きり）
    const resumed = takeParentalConsentReturnPath()
    if (resumed) {
      await router.push(resumed)
    } else if (authStore.isSystemAdmin) {
      await router.push('/system-admin')
    } else {
      await router.push('/dashboard')
    }
  } catch {
    // 連携競合は 200 で返る（上の分岐）。ここへ来るのは API 失敗・遷移失敗のみで、画面にエラーを表示する
    error.value = t('auth.oauth.callback_error')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="flex min-h-screen items-center justify-center">
    <div class="text-center">
      <div v-if="loading" class="space-y-4">
        <ProgressSpinner />
        <p class="text-surface-500">{{ $t('auth.oauth.processing') }}</p>
      </div>
      <div v-else-if="error" class="space-y-4">
        <i class="pi pi-exclamation-triangle text-4xl text-red-500" />
        <p class="text-lg font-medium">{{ error }}</p>
        <Button :label="$t('auth.oauth.back_to_login')" @click="$router.push('/login')" />
      </div>
    </div>
  </div>
</template>
