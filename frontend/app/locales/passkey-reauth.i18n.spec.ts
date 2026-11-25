// @vitest-environment node
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * 戦役A「パスキー再認証（CMP-261021-1339 → 1338）」FE 文言の試練（6 言語）。
 *
 * 検証観点:
 *   A6: UV（生体・PIN）に対応しない端末では提示モードを開けない旨を、汎用の「認証に失敗しました」とは別の文言で出す
 *       → wallet.presentation.uv_unavailable
 *   B9: パスキー登録・削除の前に挟む再認証ダイアログの文言（手段は BE の reauthenticate-method で自動選択）
 *       → settings.security.webauthn.reauth.{title,description,password_label,otp_label,submit,failed}
 *
 * キー名は試練の提案。出陣で変える場合は本 spec も同じ PR で揃えること。
 */

const LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const here = dirname(fileURLToPath(import.meta.url))
const JAPANESE = /[぀-ヿ一-鿿]/

function load(locale: string, file: string): Record<string, unknown> {
  return JSON.parse(readFileSync(resolve(here, locale, file), 'utf8')) as Record<string, unknown>
}

function lookup(dict: Record<string, unknown>, path: string): unknown {
  return path.split('.').reduce<unknown>(
    (node, key) => (node && typeof node === 'object' ? (node as Record<string, unknown>)[key] : undefined),
    dict,
  )
}

const KEYS: Array<{ ac: string, file: string, path: string }> = [
  { ac: 'A6', file: 'wallet.json', path: 'wallet.presentation.uv_unavailable' },
  ...['title', 'description', 'password_label', 'otp_label', 'submit', 'failed'].map(leaf => ({
    ac: 'B9', file: 'settings.json', path: `settings.security.webauthn.reauth.${leaf}`,
  })),
]

describe('パスキー再認証の文言（6 言語）', () => {
  for (const { ac, file, path } of KEYS) {
    it(`${ac}: ${path} が 6 言語すべてにあり、ja 以外に日本語を残さない`, () => {
      for (const locale of LOCALES) {
        const value = lookup(load(locale, file), path)
        expect(typeof value, `${locale}/${file} ${path}`).toBe('string')
        expect((value as string).trim(), `${locale}/${file} ${path}`).not.toBe('')
        if (locale !== 'ja') {
          // zh は漢字を使うため、かな（ひらがな・カタカナ）の残存だけを見る
          const pattern = locale === 'zh' ? /[぀-ヿ]/ : JAPANESE
          expect(pattern.test(value as string), `${locale}/${file} ${path}`).toBe(false)
        }
      }
    })
  }

  it('A6: UV 非対応の文言は汎用の認証失敗文言と別物である', () => {
    for (const locale of LOCALES) {
      const dict = load(locale, 'wallet.json')
      expect(typeof lookup(dict, 'wallet.presentation.uv_unavailable'), locale).toBe('string')
      expect(lookup(dict, 'wallet.presentation.uv_unavailable'))
        .not.toBe(lookup(dict, 'wallet.presentation.biometric_failed'))
    }
  })
})
