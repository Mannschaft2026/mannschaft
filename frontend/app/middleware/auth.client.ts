// Client-side auth check. Runs after auth.client.ts plugin has restored tokens from localStorage.
export default defineNuxtRouteMiddleware((to) => {
  const authStore = useAuthStore()
  if (!authStore.isAuthenticated) {
    return navigateTo({
      path: '/login',
      query: { redirect: to.fullPath },
    })
  }
  // 保護者同意待ちは pending 画面以外を開かせない。
  if (authStore.isPendingParentalConsent && to.path !== PARENTAL_CONSENT_PENDING_PATH) {
    return navigateTo(PARENTAL_CONSENT_PENDING_PATH, { replace: true })
  }
})
