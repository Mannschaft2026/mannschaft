import { describe, expect, it } from 'vitest'
import ja from '~/locales/ja/recruitment'
import en from '~/locales/en/recruitment'
import zh from '~/locales/zh/recruitment'
import ko from '~/locales/ko/recruitment'
import es from '~/locales/es/recruitment'
import de from '~/locales/de/recruitment'

/**
 * CMP-261005-2103 I2: 無断欠席・ペナルティ管理画面の空表示・未設定表示の専用文言が
 * 6言語すべてに入っていること（募集一覧の「募集はありません」を流用しない）。
 */
const locales = { ja, en, zh, ko, es, de } as const

const REQUIRED_KEYS = [
  'recruitment.noShow.empty',
  'recruitment.penalty.emptyPenalties',
  'recruitment.penalty.notConfigured',
] as const

function lookup(messages: unknown, key: string): unknown {
  return key.split('.').reduce<unknown>((node, part) => {
    if (node && typeof node === 'object' && part in node) {
      return (node as Record<string, unknown>)[part]
    }
    return undefined
  }, messages)
}

const JAPANESE = /[぀-ヿ一-鿿]/

describe('無断欠席・ペナルティ管理の専用文言（CMP-261005-2103 I2）', () => {
  it.each(Object.keys(locales) as (keyof typeof locales)[])(
    'I2: %s に空表示・未設定表示の専用キーがあり、募集一覧の空文言と異なる',
    (locale) => {
      const messages = locales[locale]
      const noListings = lookup(messages, 'recruitment.label.noListings')
      for (const key of REQUIRED_KEYS) {
        const value = lookup(messages, key)
        expect(typeof value, `${locale}: ${key}`).toBe('string')
        expect(String(value).trim(), `${locale}: ${key}`).not.toBe('')
        expect(value, `${locale}: ${key} が募集一覧の空文言と同じ`).not.toBe(noListings)
        if (locale !== 'ja') {
          expect(String(value), `${locale}: ${key} に日本語が残っている`).not.toMatch(JAPANESE)
        }
      }
    },
  )
})
