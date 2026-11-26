import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  clearParentalConsentReturn,
  parentalConsentRedirect,
  returnRedirect,
  stashParentalConsentToken,
  takeParentalConsentReturnPath,
} from './parentalConsentReturn'

/**
 * CMP-261123-1124 アリシゼーション ALIC-1: 新規保護者がメール内リンク→登録→メール確認→ログインしても
 * 承認画面（token 付き）へ戻れるよう、token を保存し、ログイン後に一度だけ読み出す。
 * 戻り先は相対パスの許可リストに限る（オープンリダイレクトを作らない）。
 */
describe('parentalConsentRedirect（許可リスト）', () => {
  it.each([
    '/parental-consent/approve?token=abc123',
    '/parental-consent/approve?token=A-b_9',
  ])('%s を受け入れる', (value) => {
    expect(parentalConsentRedirect(value)).toBe(value)
  })

  it.each([
    'https://evil.example.com/parental-consent/approve?token=a',
    '//evil.example.com',
    '/parental-consent/approve',
    '/parental-consent/approve?token=',
    '/parental-consent/approve?token=a&next=https://evil',
    '/parental-consent/approve?token=a b',
    '/parental-consent/manage?token=a',
    '/parental-consent/approve?token=a\\b',
    undefined,
    null,
    42,
  ])('%s は拒否する', (value) => {
    expect(parentalConsentRedirect(value)).toBeUndefined()
  })
})

describe('returnRedirect（招待 + 保護者同意の許可リスト）', () => {
  it('招待の復帰先も従来どおり受け入れる', () => {
    expect(returnRedirect('/invite/abc-123')).toBe('/invite/abc-123')
  })
  it('承認画面の復帰先を受け入れ、他は拒否する', () => {
    expect(returnRedirect('/parental-consent/approve?token=abc')).toBe('/parental-consent/approve?token=abc')
    expect(returnRedirect('/dashboard')).toBeUndefined()
  })
})

describe('token の保存と一度きりの読み出し', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.useRealTimers()
  })

  it('保存した token から戻り先パスを組み立て、読み出すと破棄される', () => {
    stashParentalConsentToken('tok-1')

    expect(takeParentalConsentReturnPath()).toBe('/parental-consent/approve?token=tok-1')
    expect(takeParentalConsentReturnPath()).toBeUndefined()
  })

  it('不正な token（許可文字以外）は保存しない', () => {
    stashParentalConsentToken('x&redirect=https://evil')

    expect(takeParentalConsentReturnPath()).toBeUndefined()
  })

  it('有効期限（7 日）を過ぎた保存は読み出せず、破棄される', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-01T00:00:00Z'))
    stashParentalConsentToken('tok-2')
    vi.setSystemTime(new Date('2026-11-09T00:00:00Z'))

    expect(takeParentalConsentReturnPath()).toBeUndefined()
    expect(localStorage.length).toBe(0)
  })

  it('clear で破棄できる', () => {
    stashParentalConsentToken('tok-3')
    clearParentalConsentReturn()

    expect(takeParentalConsentReturnPath()).toBeUndefined()
  })
})
