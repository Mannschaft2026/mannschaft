export type LoginResult = {
  accessToken: string
  refreshToken: string
  userId: number
  fullName: string
  email: string
  mfaRequired?: boolean
  mfaSessionToken?: string
  reactivated?: boolean
}

export function useLoginSession() {
  const api = useApi()
  const authStore = useAuthStore()
  // イベントハンドラ内でuseRuntimeConfigを呼ばず、先回り更新へsetup時の設定を渡す。
  const runtimeConfig = useRuntimeConfig()
  const notification = useNotification()
  const route = useRoute()
  const { applyAccountLocale } = useLocale()
  const { t } = useI18n()

  async function completeLogin(
    tokens: { accessToken: string; refreshToken: string },
    passwordResult?: LoginResult,
  ): Promise<string> {
    let navigationTarget: string | null = null
    let isPendingConsent = false
    authStore.setTokens(tokens.accessToken, tokens.refreshToken)
    // /api/v1/users/me でフルプロフィール（systemRole・locale・avatarUrl 等）を一括取得
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
      // 保護者同意待ちも認証済みとして status 付きで本人を保存する（isAuthenticated=false だと
      // auth middleware が /login へ戻すため）。遷移先は redirect に関わらず pending 固定。
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
      // 同意待ちはアカウント言語の適用もしない（BE ゲートで塞がれる API を呼ばないため）
      if (!isPendingConsent && profile.data.locale) {
        await applyAccountLocale(profile.data.locale)
      }
    } catch {
      if (!passwordResult) {
        const redirect = route.query.redirect
        await authStore.serverLogout()
        if (typeof redirect === 'string') {
          await navigateTo({ path: '/login', query: { redirect } }, { replace: true })
        }
        throw new Error('profile')
      }
      // プロフィール取得失敗時はログインレスポンスの基本情報で続行。
      // ただしトークンが同意待ち（ppc=true）なら通常ユーザー扱いせず、同意待ちの本人として保存する。
      isPendingConsent = isParentalConsentPendingToken(tokens.accessToken)
      await authStore.setUser({
        id: passwordResult.userId,
        email: passwordResult.email,
        fullName: passwordResult.fullName,
        profileImageUrl: null,
        ...(isPendingConsent ? { status: PENDING_PARENTAL_CONSENT_STATUS } : {}),
      })
    }

    // 本人復元とlocale適用による世代更新が終わってから、先回り更新を武装する。
    armProactiveRefresh(runtimeConfig, authStore)

    // 保護者同意待ちは、設定同期も redirect の尊重もせず pending 固定で遷移する。
    if (isPendingConsent) {
      navigationTarget = PARENTAL_CONSENT_PENDING_PATH
    } else {
      // ログイン成立直後にアカウント設定（外観・ナビ）をサーバーから同期する。
      // 新ブラウザ（シークレット等）で localStorage が空でも BEの保存済み設定が反映される。
      authStore.syncAccountSettings()

      // 遷移先の決定。
      //
      // 一般ユーザーの既定を `/`（ランディング）ではなく `/dashboard` にしている理由:
      // `/` には `guest` ミドルウェアが付いており、認証済みユーザーは即座に `/dashboard` へ
      // 弾き返される。`/` 経由にすると「認証済みユーザーが決して見ないランディングページ」を
      // 読み込んでから捨てることになり、遷移が二段になって待ち時間が伸びるだけで利点がない。
      // 保護者の承認画面から登録・メール確認を経て戻った場合は、redirect が失われているため保存済み token の承認画面へ戻る。
      // 保存は一度きり。redirect クエリがあればそちらを優先する。
      const parentalConsentReturn = takeParentalConsentReturnPath()
      navigationTarget = passwordResult?.reactivated
        ? '/account-restore'
        : (route.query.redirect as string)
          || parentalConsentReturn
          || (authStore.isSystemAdmin ? '/system-admin' : '/dashboard')

      notification.success(t('auth.login.success'))
    }
    return navigationTarget!
  }

  return { completeLogin }
}
