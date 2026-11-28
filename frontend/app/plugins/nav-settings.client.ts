export default defineNuxtPlugin(() => {
  const store = useNavSettingsStore()
  const authStore = useAuthStore()

  store.loadFromStorage()

  // 同意待ちは BE ゲートで塞がれるため取得しない
  if (authStore.isAuthenticated && !authStore.isPendingParentalConsent) {
    void store.loadFromServer().catch((error) => {
      console.error('[navSettings] 起動時の設定取得に失敗しました', error)
    })
  }
})
