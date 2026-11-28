import { inviteRedirect } from '~/utils/inviteRedirect'

/** 保護者の承認画面（FE: pages/parental-consent/approve.vue）。token はクエリで受ける。 */
export const PARENTAL_CONSENT_APPROVE_PATH = '/parental-consent/approve'

const STORAGE_KEY = 'parentalConsentReturn'
/** 同意リンクの有効期限（BE: 7 日）に合わせる。過ぎた保存は読み出さず破棄する。 */
const TTL_MS = 7 * 24 * 60 * 60 * 1000
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{1,128}$/
const APPROVE_REDIRECT_PATTERN = /^\/parental-consent\/approve\?token=[A-Za-z0-9_-]{1,128}$/

/** 承認画面（token 付き）の相対パスだけを許可する（オープンリダイレクトを作らない）。 */
export function parentalConsentRedirect(value: unknown): string | undefined {
  return typeof value === 'string' && APPROVE_REDIRECT_PATTERN.test(value) ? value : undefined
}

/** 登録・メール確認・ログインで引き継げる復帰先（招待 + 保護者の承認画面）。 */
export function returnRedirect(value: unknown): string | undefined {
  return inviteRedirect(value) ?? parentalConsentRedirect(value)
}

/**
 * 未ログインの保護者が承認画面を開いたとき、token を保存する。
 * 新規登録 → メール確認（別タブ・別画面）→ ログインを経ても承認画面へ戻れるよう localStorage に保存する。
 */
export function stashParentalConsentToken(token: string): void {
  if (typeof localStorage === 'undefined' || !TOKEN_PATTERN.test(token)) return
  localStorage.setItem(STORAGE_KEY, JSON.stringify({ token, savedAt: Date.now() }))
}

export function clearParentalConsentReturn(): void {
  if (typeof localStorage === 'undefined') return
  localStorage.removeItem(STORAGE_KEY)
}

/** 保存済み token から承認画面の戻り先を作り、同時に破棄する（読み出しは一度きり）。無効・期限切れは undefined。 */
export function takeParentalConsentReturnPath(): string | undefined {
  if (typeof localStorage === 'undefined') return undefined
  const raw = localStorage.getItem(STORAGE_KEY)
  if (raw === null) return undefined
  localStorage.removeItem(STORAGE_KEY)
  try {
    const saved = JSON.parse(raw) as { token?: unknown; savedAt?: unknown }
    if (typeof saved.token !== 'string' || typeof saved.savedAt !== 'number') return undefined
    if (Date.now() - saved.savedAt > TTL_MS) return undefined
    return parentalConsentRedirect(`${PARENTAL_CONSENT_APPROVE_PATH}?token=${saved.token}`)
  } catch {
    // eslint-disable-next-line no-restricted-syntax -- 破損した保存値（手編集・旧形式）は「保存なし」と同じ扱いが正しい。取得失敗ではなく、すでに破棄済みで戻り先が無いだけ
    return undefined
  }
}
