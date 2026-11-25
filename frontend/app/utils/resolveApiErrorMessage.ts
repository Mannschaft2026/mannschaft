// API エラーコードから利用者向け文言を解決する純関数。
// useErrorHandler（4xx 等の handleApiError）と useApi（5xx の集約トースト）で規則を共有する。
// t / te は呼び出し側が渡す（useI18n でも nuxtApp.$i18n でもよい）。循環 import を避けるため他の composable に依存しない。
export interface ApiErrorMessageTranslator {
  t: (key: string) => string
  te: (key: string) => boolean
}

// フレンド領域はBEの日本語messageより利用中の言語を優先する。
const FRIEND_I18N_CODES = new Set([
  'SOCIAL_101',
  'SOCIAL_102',
  'SOCIAL_103',
  'SOCIAL_104',
  'SOCIAL_105',
  'SOCIAL_106',
  'SOCIAL_107',
  'SOCIAL_108',
  'SOCIAL_109',
  'SOCIAL_110',
  'SOCIAL_111',
  'SOCIAL_112',
  'SOCIAL_113',
  'SOCIAL_120',
  'SOCIAL_121',
  'SOCIAL_122',
  'SOCIAL_123',
  'SOCIAL_124',
  'SOCIAL_125',
  'SOCIAL_130',
  'SOCIAL_131',
  'SOCIAL_132',
  'SOCIAL_133',
  'SOCIAL_134',
])

// 大会エントリーは既存の領域別キーを使い、BEの日本語messageより利用言語を優先する。
const TOURNAMENT_ENTRY_I18N_KEYS: Record<string, string> = {
  TOUR_019: 'tournament.entry.error.TOUR_019',
  TOUR_020: 'tournament.entry.error.TOUR_020',
  TOUR_021: 'tournament.entry.error.TOUR_021',
  TOUR_022: 'tournament.entry.error.TOUR_022',
  TOUR_023: 'tournament.entry.error.TOUR_023',
  TOUR_024: 'tournament.entry.error.TOUR_024',
  TOUR_025: 'tournament.entry.error.TOUR_025',
  TOUR_026: 'tournament.entry.template.error.TOUR_026',
  TOUR_027: 'tournament.entry.template.error.TOUR_027',
  TOUR_028: 'tournament.entry.template.error.TOUR_028',
}

// #2426: BE（CommonErrorCode）が理由入りの具体的な message を返している場合、
// 汎用 i18n キー（例: error.COMMON_001）で上書きして理由を握りつぶさない。
// BE message を最優先し、無ければ従来どおり i18n キー→汎用文言にフォールバックする。
export function resolveApiErrorMessage(
  { t, te }: ApiErrorMessageTranslator,
  code: string,
  fallback?: string,
  context?: string,
  unresolvedKey = 'error.unknown',
): string {
    const key = `error.${code}`
    const tournamentKey = Object.hasOwn(TOURNAMENT_ENTRY_I18N_KEYS, code)
      ? TOURNAMENT_ENTRY_I18N_KEYS[code]
      : undefined
    if (tournamentKey && te(tournamentKey)) return t(tournamentKey)
    if (code === 'COMMON_003' && context === 'team-loyalty') return t('loyalty.error.changed')
    if (code === 'MEMBERSHIP_003' && context === 'team-loyalty')
      return t('loyalty.memberQr.disabled')
    if (code.startsWith('LOYALTY_')) {
      const loyaltyKeys: Record<string, string> = {
        LOYALTY_001: 'loadErrorState.states.notFoundOrForbidden.message',
        LOYALTY_002: 'loadErrorState.states.forbidden.message',
        LOYALTY_003: 'loyalty.error.ineligible',
        LOYALTY_004: 'loyalty.program.disabled',
        LOYALTY_005: 'loyalty.error.alreadyAwarded',
        LOYALTY_006: 'loyalty.error.changed',
        LOYALTY_007: 'loyalty.error.changed',
        LOYALTY_008: 'loyalty.error.insufficient',
        LOYALTY_009: 'loyalty.error.changed',
        LOYALTY_010: 'loyalty.self.rescan',
        LOYALTY_011: 'loyalty.self.rescan',
        LOYALTY_012: 'error.server_retry',
        LOYALTY_013: 'loyalty.error.changed',
        LOYALTY_014: 'loyalty.error.changed',
        LOYALTY_015: 'loyalty.requests.selfApprovalForbidden',
      }
      const loyaltyKey = loyaltyKeys[code]
      if (loyaltyKey) return t(loyaltyKey)
    }
    // CMP-260920-1040 AC-36: 確認通知は errorCode ごとの6言語文言を表示する。
    // 確認通知とチームの固定不在/復元409だけ登録済みi18nを優先し、他codeの具体的理由は維持する。
    if (
      (FRIEND_I18N_CODES.has(code) ||
        code.startsWith('CONFIRMABLE_NOTIFICATION_') ||
        code === 'TEAM_001' ||
        code === 'TEAM_006') &&
      te(key)
    )
      return t(key)
    if (fallback) return fallback
    if (te(key)) return t(key)
    return t(unresolvedKey)
  }
