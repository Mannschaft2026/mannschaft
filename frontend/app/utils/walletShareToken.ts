/**
 * F18 ウォレット — 共有トークン（顧客 QR → 店主側 resolve）の形式判定ユーティリティ。
 *
 * <p>共有トークンは BE の {@code SecretTokenVault#issueBase64Url()} が発行する
 * Base64URL（パディング無し）43 字。以前の UUID（36 字）ではない。
 * カード ID（UUID）の手入力検証とは別物なので、判定を分ける。</p>
 */

/** 共有トークン: Base64URL・パディング無し・43 字（256 bit） */
const SHARE_TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/

/** カード ID: UUID 形式（簡易判定 8-4-4-4-12） */
const CARD_ID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

export function isShareToken(value: string): boolean {
  return SHARE_TOKEN_PATTERN.test(value)
}

export function isCardId(value: string): boolean {
  return CARD_ID_PATTERN.test(value)
}

/**
 * QR / 手入力の文字列から共有トークンを取り出す。
 * 素のトークン、または {@code ...?token=<token>} を持つ URL（{@code mannschaft://} を含む）を受け付ける。
 * 形式に合わないものは null。
 */
export function extractShareToken(qrValue: string): string | null {
  const raw = qrValue.trim()
  if (!raw) return null

  if (isShareToken(raw)) return raw

  // URL 系（mannschaft:// は WHATWG URL がパースできないため https:// に置換）
  try {
    const normalized = raw.startsWith('mannschaft://')
      ? raw.replace('mannschaft://', 'https://app.local/')
      : raw
    const url = new URL(normalized)
    const token = url.searchParams.get('token')
    if (token && isShareToken(token)) return token
  }
  catch {
    // パース失敗 → 不正な QR
  }
  return null
}
