import { describe, expect, it } from 'vitest'
import type { PasskeyManageReauthMethod } from '~/composables/useAuthApi'
import { passkeyReauthNeedsFactor, passkeyReauthNeedsPassword } from './passkeyReauthMethod'

/** 再認証手段 5 種すべてで、パスワード欄・2FA 要素欄の要否が BE の契約と一致すること（CMP-261021-1338 B5）。 */
const CASES: [PasskeyManageReauthMethod, boolean, boolean][] = [
  ['PASSKEY', false, false],
  ['PASSWORD', true, false],
  ['PASSWORD_AND_SECOND_FACTOR', true, true],
  ['SECOND_FACTOR', false, true],
  ['EMAIL_OTP', false, true],
]

describe('passkeyReauthMethod', () => {
  it.each(CASES)('%s: needsPassword=%s needsFactor=%s', (method, password, factor) => {
    expect(passkeyReauthNeedsPassword(method)).toBe(password)
    expect(passkeyReauthNeedsFactor(method)).toBe(factor)
  })
})
