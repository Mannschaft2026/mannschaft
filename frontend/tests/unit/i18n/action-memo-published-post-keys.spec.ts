import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * CMP-261123-1201 AC-8: 公開済み個人投稿への導線の文言が 6 言語すべてに存在する
 * （action_memo.published_post.view_link / action_memo.published_post.published_note）。
 * ja 以外に日本語を置かないことは既存の番人 locale-no-japanese-residue.spec.ts が検出する。
 */
const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const KEYS = ['view_link', 'published_note'] as const
const here = dirname(fileURLToPath(import.meta.url))

type Json = { [k: string]: unknown }

describe('action_memo.published_post.* の 6 言語キー', () => {
  for (const lang of LANGS) {
    it(`${lang}: view_link / published_note が空でない文字列で存在する`, () => {
      const raw = readFileSync(resolve(here, `../../../app/locales/${lang}/action_memo.json`), 'utf-8').replace(/^\u{FEFF}/u, '')
      const json = JSON.parse(raw) as Json
      const ns = json.action_memo as Json
      const pp = ns.published_post as Json | undefined
      expect(pp, 'action_memo.published_post').toBeTruthy()
      for (const k of KEYS) {
        expect(typeof pp![k], `${lang} ${k}`).toBe('string')
        expect((pp![k] as string).trim().length).toBeGreaterThan(0)
      }
    })
  }

  it('ja の注記は「自分だけが見られます」の意味を含む', () => {
    const raw = readFileSync(resolve(here, '../../../app/locales/ja/action_memo.json'), 'utf-8').replace(/^\u{FEFF}/u, '')
    const pp = ((JSON.parse(raw) as Json).action_memo as Json).published_post as Json | undefined
    expect(String(pp?.published_note ?? '')).toContain('自分だけ')
  })
})
