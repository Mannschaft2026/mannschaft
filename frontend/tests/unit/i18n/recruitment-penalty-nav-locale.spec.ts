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

/**
 * Nuxt のテスト環境では vue-i18n のビルド時プラグインがメッセージを AST へコンパイルするため、
 * 文字列は `{ loc: { source } }` の形で届く。環境によらず元の文言を取り出す。
 */
function asText(value: unknown): unknown {
  if (value && typeof value === 'object') {
    const source = (value as { loc?: { source?: unknown } }).loc?.source
    if (typeof source === 'string') return source
  }
  return value
}

// 日本語の残存はかな（ひらがな・カタカナ）で判定する。漢字は中国語（zh）の正当な表記なので対象外
const JAPANESE = /[぀-ヿ]/

describe('無断欠席・ペナルティ管理の専用文言（CMP-261005-2103 I2）', () => {
  it.each(Object.keys(locales) as (keyof typeof locales)[])(
    'I2: %s に空表示・未設定表示の専用キーがあり、募集一覧の空文言と異なる',
    (locale) => {
      const messages = locales[locale]
      const noListings = asText(lookup(messages, 'recruitment.label.noListings'))
      for (const key of REQUIRED_KEYS) {
        const value = asText(lookup(messages, key))
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
