// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * CMP-261125-1108 共通「見つかりません」表示（ScopeNotFound.vue）の文言の 6 言語パリティ。
 *
 * 前提とするキー（common.json の common.scopeNotFound 名前空間）:
 * - common.scopeNotFound.title（ja: 見つかりません）
 * - common.scopeNotFound.body
 *
 * 非所属・権限なしでも「存在する」ことを漏らさないため、権限・所属を示唆する文言を含めない。
 * 値に裸の @ を含むと vue-i18n がリンク記法として解釈し描画が壊れるため禁止する。
 */
const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const REQUIRED_KEYS = ['title', 'body'] as const

const here = dirname(fileURLToPath(import.meta.url))
const localesDir = resolve(here, '../../../app/locales')

function loadScopeNotFound(lang: string): Record<string, unknown> {
  const json = JSON.parse(readFileSync(resolve(localesDir, lang, 'common.json'), 'utf-8')) as Record<string, unknown>
  const common = json.common
  if (!common || typeof common !== 'object' || Array.isArray(common)) return {}
  const ns = (common as Record<string, unknown>).scopeNotFound
  return ns && typeof ns === 'object' && !Array.isArray(ns) ? (ns as Record<string, unknown>) : {}
}

/** ひらがな・カタカナ（zh は漢字を使うため、日本語の残留はかなで判定する） */
const KANA = /[぀-ヿ]/
/** ja 以外では漢字も日本語残留とみなす（zh を除く） */
const CJK = /[぀-ヿ一-鿿]/

/** 権限・所属を示唆する語（言語別） */
const PERMISSION_HINTS: Record<(typeof LANGS)[number], RegExp> = {
  ja: /権限|所属|メンバー|参加/,
  en: /permission|member|access|belong|authori[sz]/i,
  zh: /权限|成员|所属|加入/,
  ko: /권한|멤버|소속|가입/,
  es: /permiso|miembro|acceso|pertenec/i,
  de: /berechtigung|mitglied|zugriff|zugang|gehör/i,
}

describe('CMP-261125-1108 common.scopeNotFound 文言の 6 言語パリティ', () => {
  for (const lang of LANGS) {
    it(`${lang}/common.json に common.scopeNotFound.title/body が非空文字列で揃っている`, () => {
      const ns = loadScopeNotFound(lang)
      const missing = REQUIRED_KEYS.filter((k) => typeof ns[k] !== 'string' || (ns[k] as string).trim() === '')
      expect({ lang, missing }).toEqual({ lang, missing: [] })
    })

    it(`${lang}: 権限・所属を示唆する文言を含まず、裸の @ も含まない`, () => {
      const ns = loadScopeNotFound(lang)
      for (const k of REQUIRED_KEYS) {
        const value = String(ns[k] ?? '')
        expect(value, `${lang}.${k}`).not.toBe('')
        expect(value, `${lang}.${k}`).not.toMatch(PERMISSION_HINTS[lang])
        expect(value, `${lang}.${k}`).not.toContain('@')
      }
    })

    if (lang !== 'ja') {
      it(`${lang}: 日本語が残留していない`, () => {
        const ns = loadScopeNotFound(lang)
        for (const k of REQUIRED_KEYS) {
          const value = String(ns[k] ?? '')
          expect(value, `${lang}.${k}`).not.toBe('')
          expect(value, `${lang}.${k}`).not.toMatch(lang === 'zh' ? KANA : CJK)
        }
      })
    }
  }

  it('ja の見出しは「見つかりません」', () => {
    expect(loadScopeNotFound('ja').title).toBe('見つかりません')
  })
})
