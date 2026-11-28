/** 保護者同意待ちの本人ステータス（BE の UserStatus と同名）。 */
export const PENDING_PARENTAL_CONSENT_STATUS = 'PENDING_PARENTAL_CONSENT'

/** 保護者同意待ちの利用者が唯一開いてよい画面。BE の ParentalConsentGateFilter が他の API を遮断する。 */
export const PARENTAL_CONSENT_PENDING_PATH = '/parental-consent/pending'

/**
 * アクセストークン（JWT）の ppc クレームから、保護者同意待ちのトークンかを判定する。
 * /users/me が取得できないときのフォールバック判定に使う。デコード失敗は false（署名検証は BE が行う）。
 */
export function isParentalConsentPendingToken(accessToken: string | null | undefined): boolean {
  try {
    const payloadPart = accessToken?.split('.')[1]
    if (!payloadPart) return false
    const base64 = payloadPart.replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(base64)) as { ppc?: unknown }
    return payload.ppc === true
  } catch {
    return false
  }
}
