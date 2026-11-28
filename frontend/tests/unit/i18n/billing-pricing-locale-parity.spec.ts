import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/** 公開価格ページ billing.pricing.* の6言語キーパリティと、ja 以外への日本語残留の禁止。 */
const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const KEYS = [
  'title', 'description', 'heading', 'scopeLabel', 'scopeUser', 'scopeTeam', 'scopeOrg',
  'quoteRequired', 'comingSoon', 'free', 'taxIncluded', 'taxBreakdown',
  'loadError', 'retry', 'addonsHeading', 'register', 'login',
] as const
const load = (l: string) =>
  JSON.parse(readFileSync(resolve(process.cwd(), `app/locales/${l}/billing.json`), 'utf8').replace(/^\uFEFF/, ''))

describe('billing.pricing 6言語パリティ', () => {
  it.each(LANGS)('%s は全キーを持つ', (l) => {
    const p = load(l).billing.pricing as Record<string, unknown>
    for (const k of KEYS) expect(typeof p?.[k], `${l}.${k}`).toBe('string')
  })
  it.each(LANGS.filter(l => l !== 'ja'))('%s に日本語（かな／日本語の漢字）が残っていない', (l) => {
    const p = load(l).billing.pricing as Record<string, string>
    const re = l === 'zh' ? /[぀-ヿ]/ : /[぀-ヿ一-鿿]/
    for (const [k, v] of Object.entries(p)) expect(re.test(v), `${l}.${k}`).toBe(false)
  })
})
