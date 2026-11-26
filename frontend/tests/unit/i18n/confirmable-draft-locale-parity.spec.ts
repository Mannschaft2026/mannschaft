// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * 試練: 確認通知の下書き（CMP-261126-1558 FE-AC10）の文言が6言語すべてに、その言語の訳で入っていること。
 *
 * 実装側が合わせる契約: 文言は common.json の `confirmable.draft.*` に置く。最低限、下記 REQUIRED_KEYS を持つ。
 *  - count は「N/10」用で、プレースホルダ {count} と {limit} を持つ
 * 日本語残存・構造の全体検査は既存の番人 locale-no-japanese-residue.spec.ts が受け持つ（ここでは対象キーだけ見る）。
 */
const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const REQUIRED_KEYS = [
  'list_title', // 一覧の見出し
  'count', // 「{count}/{limit}」
  'empty', // 0件の空表示
  'new', // 新しく書く
  'untitled', // タイトルが空の下書き
  'delete', // 行の削除
  'delete_confirm', // 削除の確認文
  'limit_reached', // 上限の案内（古い下書きを消すよう促す）
  'conflict', // 別の画面で更新されました
  'reload_latest', // 最新を読み込む
  'overwrite', // この内容で上書き保存
  'unsaved_header', // 保存できていない変更があるまま切り替えるときの確認（見出し）
  'unsaved_confirm', // 同（確認文）
  'discard_and_switch', // 同（承認ボタン）
] as const

const here = dirname(fileURLToPath(import.meta.url))
const localesDir = resolve(here, '../../../app/locales')

function readCommon(lang: string): Record<string, unknown> {
  const raw = readFileSync(resolve(localesDir, lang, 'common.json'), 'utf-8')
  return JSON.parse(raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw) as Record<string, unknown>
}

function draftSection(lang: string): Record<string, unknown> {
  const confirmable = readCommon(lang).confirmable as Record<string, unknown> | undefined
  const draft = confirmable?.draft
  return draft && typeof draft === 'object' && !Array.isArray(draft) ? draft as Record<string, unknown> : {}
}

// ひらがな・カタカナ・CJK 統合漢字（zh は漢字を使うので仮名だけ見る）
const KANA = /[぀-ヿ]/
const KANA_OR_KANJI = /[぀-ヿ一-鿿]/

describe('確認通知の下書きの文言（CMP-261126-1558 FE-AC10）', () => {
  for (const lang of LANGS) {
    it(`FE-AC10: ${lang} に confirmable.draft.* の必須キーが空でない文字列で入っている`, () => {
      const section = draftSection(lang)
      const missing = REQUIRED_KEYS.filter(key => typeof section[key] !== 'string' || (section[key] as string).trim() === '')
      expect(missing, `${lang} で欠けているキー`).toEqual([])
    })

    it(`FE-AC10: ${lang} の count は {count} と {limit} を持つ`, () => {
      const count = String(draftSection(lang).count ?? '')
      expect(count).toContain('{count}')
      expect(count).toContain('{limit}')
    })
  }

  for (const lang of LANGS.filter(l => l !== 'ja')) {
    it(`FE-AC10: ${lang} の下書き文言に日本語を置かない`, () => {
      const section = draftSection(lang)
      expect(Object.keys(section).length, `${lang} に confirmable.draft が無い`).toBeGreaterThan(0)
      const pattern = lang === 'zh' ? KANA : KANA_OR_KANJI
      const residue = Object.entries(section).filter(([, v]) => typeof v === 'string' && pattern.test(v)).map(([k]) => k)
      expect(residue).toEqual([])
    })
  }

  it('FE-AC10: 6言語のキー集合が揃っている', () => {
    const base = Object.keys(draftSection('ja')).sort()
    expect(base.length).toBeGreaterThan(0)
    for (const lang of LANGS) expect(Object.keys(draftSection(lang)).sort(), lang).toEqual(base)
  })
})
