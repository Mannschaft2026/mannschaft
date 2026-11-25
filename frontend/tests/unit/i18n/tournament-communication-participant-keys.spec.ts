// @vitest-environment node
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * CMP-261118-0900 X6（設計書 F08.7.1 01_communication.md §10.9）:
 * 参加者向け連絡スペース画面の新キー tournament.communication.participant.* が 6 言語すべてにある。
 * ja 以外に日本語が残っていないことは locale-no-japanese-residue.spec.ts が別途検出する。
 */

const LOCALES_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '../../../app/locales')
const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const KEYS = ['title', 'open_link', 'view_only_notice', 'unavailable', 'unavailable_hint', 'load_retry'] as const
/** 再利用する既存キー（§10.9）も消えていないこと */
const REUSED_KEYS = ['title', 'bulletin', 'chat', 'tournament_space', 'division_space', 'empty', 'load_failed'] as const

function loadCommunication(lang: string): Record<string, unknown> {
  const raw = readFileSync(resolve(LOCALES_DIR, lang, 'tournament.json'), 'utf8').replace(/^\u{FEFF}/u, '')
  const json = JSON.parse(raw) as { tournament?: { communication?: Record<string, unknown> } }
  return json.tournament?.communication ?? {}
}

describe('tournament.communication.participant.* の 6 言語', () => {
  it.each(LANGS)('X6: %s に participant の新キー 6 個が空でない文字列で揃っている', (lang) => {
    const participant = loadCommunication(lang).participant as Record<string, unknown> | undefined
    expect(participant, `${lang}: tournament.communication.participant が無い`).toBeTypeOf('object')
    for (const key of KEYS) {
      const value = participant?.[key]
      expect(typeof value === 'string' && value.trim().length > 0, `${lang}: participant.${key} が空または不在`).toBe(true)
    }
  })

  it.each(LANGS)('X6: %s で再利用する既存キーが残っている', (lang) => {
    const communication = loadCommunication(lang)
    for (const key of REUSED_KEYS) {
      expect(typeof communication[key], `${lang}: communication.${key}`).toBe('string')
    }
  })

  it('X6: ja の文言は設計書 §10.9 の案と一致する', () => {
    const participant = loadCommunication('ja').participant as Record<string, string> | undefined
    expect(participant).toMatchObject({
      title: '大会の連絡スペース',
      open_link: '連絡スペースを開く',
      unavailable: 'この大会の連絡スペースを利用できません',
      load_retry: '再読み込み',
    })
  })
})
