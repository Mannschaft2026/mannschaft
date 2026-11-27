// @vitest-environment node
import { describe, expect, it } from 'vitest'
import {
  extractShareToken,
  isCardId,
  isShareToken,
} from './walletShareToken'

// SecretTokenVault#issueBase64Url と同じ形（Base64URL・パディング無し・43 字）
const TOKEN_43 = 'AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-AbCde'
const LEGACY_UUID = '01234567-89ab-4cde-8fed-cba987654321'

describe('isShareToken', () => {
  it('43 字の Base64URL を受け付ける', () => {
    expect(TOKEN_43).toHaveLength(43)
    expect(isShareToken(TOKEN_43)).toBe(true)
  })

  it('旧形式の UUID（36 字）・短すぎ・長すぎ・使えない記号を弾く', () => {
    expect(isShareToken(LEGACY_UUID)).toBe(false)
    expect(isShareToken(TOKEN_43.slice(1))).toBe(false)
    expect(isShareToken(`${TOKEN_43}A`)).toBe(false)
    expect(isShareToken(`${TOKEN_43.slice(1)}+`)).toBe(false)
    expect(isShareToken(`${TOKEN_43.slice(1)}=`)).toBe(false)
    expect(isShareToken('')).toBe(false)
  })
})

describe('extractShareToken', () => {
  it('素のトークン文字列を取り出す（前後の空白は除く）', () => {
    expect(extractShareToken(`  ${TOKEN_43}\n`)).toBe(TOKEN_43)
  })

  it('ディープリンク mannschaft://wallet/share?token=... から取り出す', () => {
    expect(extractShareToken(`mannschaft://wallet/share?token=${TOKEN_43}`)).toBe(TOKEN_43)
  })

  it('https の URL からも token クエリを取り出す', () => {
    expect(extractShareToken(`https://example.com/wallet/share?token=${TOKEN_43}`)).toBe(TOKEN_43)
  })

  it('不正な形式は null を返す（旧 UUID・空・形式違反の token クエリ）', () => {
    expect(extractShareToken(LEGACY_UUID)).toBeNull()
    expect(extractShareToken(`mannschaft://wallet/share?token=${LEGACY_UUID}`)).toBeNull()
    expect(extractShareToken('')).toBeNull()
    expect(extractShareToken('not a token')).toBeNull()
  })
})

describe('isCardId', () => {
  it('カード ID は従来どおり UUID 形式だけを受け付ける', () => {
    expect(isCardId(LEGACY_UUID)).toBe(true)
    expect(isCardId(TOKEN_43)).toBe(false)
  })
})
