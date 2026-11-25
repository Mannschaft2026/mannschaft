import type { PasskeyManageReauthMethod } from '~/composables/useAuthApi'

/**
 * パスキー登録・削除前の再認証で、入力欄を出す条件（BE の PasskeyManageReauthMethod と対応）。
 *
 * | 手段 | パスワード | 2FA 要素／メールOTP |
 * |---|---|---|
 * | PASSKEY | 不要（OS の認証ダイアログ） | 不要 |
 * | PASSWORD | 要 | 不要 |
 * | PASSWORD_AND_SECOND_FACTOR | 要 | 要 |
 * | SECOND_FACTOR | 不要（パスワードを持たない） | 要 |
 * | EMAIL_OTP | 不要 | 要（メールOTP） |
 */
export function passkeyReauthNeedsPassword(method: PasskeyManageReauthMethod): boolean {
  return method === 'PASSWORD' || method === 'PASSWORD_AND_SECOND_FACTOR'
}

export function passkeyReauthNeedsFactor(method: PasskeyManageReauthMethod): boolean {
  return method === 'PASSWORD_AND_SECOND_FACTOR' || method === 'SECOND_FACTOR' || method === 'EMAIL_OTP'
}
