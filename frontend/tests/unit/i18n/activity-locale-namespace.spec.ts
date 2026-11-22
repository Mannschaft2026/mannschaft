import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * activity.json の名前空間ラッパー検証（AC-78 / F06.4.1 先行修正・試練）。
 *
 * Nuxt i18n ではファイル名は名前空間にならない。呼び出し側は t('activity.stats.…') /
 * t('activity.template.…') で参照するため、activity.json の最上位は activity の1キーだけでなければならない
 * （さもないとキーが生表示され、stats / template が root を汚す）。
 * common.json にも activity.* があるため、ロケールファイル同士を deep merge しても、同じ葉キーを奪い合わないこと。
 * 6言語（ja と ja 以外の5言語）すべてが同じ形であること。
 */
const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const here = dirname(fileURLToPath(import.meta.url))
const localesDir = resolve(here, '../../../app/locales')
const nuxtConfig = readFileSync(resolve(here, '../../../nuxt.config.ts'), 'utf-8')

type Json = { [k: string]: unknown }
const isObj = (v: unknown): v is Json => !!v && typeof v === 'object' && !Array.isArray(v)

function readJson(lang: string, file: string): Json {
  return JSON.parse(readFileSync(resolve(localesDir, lang, file), 'utf-8').replace(/^\u{FEFF}/u, '')) as Json
}

function deepMerge(target: Json, src: Json): Json {
  for (const [k, v] of Object.entries(src)) {
    const cur = target[k]
    target[k] = isObj(cur) && isObj(v) ? deepMerge(cur, v) : v
  }
  return target
}

function leafPaths(node: unknown, prefix = ''): string[] {
  if (!isObj(node)) return [prefix]
  return Object.entries(node).flatMap(([k, v]) => leafPaths(v, prefix ? `${prefix}.${k}` : k))
}

function filesOf(lang: string): string[] {
  const start = nuxtConfig.indexOf(`code: '${lang}'`)
  const filesIdx = nuxtConfig.indexOf('files: [', start)
  const end = nuxtConfig.indexOf(']', filesIdx)
  return [...nuxtConfig.slice(filesIdx, end).matchAll(/'([^']+)'/g)].map((m) => m[1]!)
}

describe.each(LANGS)('activity.json の名前空間（%s）', (lang) => {
  it('AC-78: 最上位キーは activity の1つだけ（stats / template / member_fields が root に漏れない）', () => {
    expect(Object.keys(readJson(lang, 'activity.json'))).toEqual(['activity'])
  })

  it('AC-78: activity.stats / activity.template / activity.member_fields が解決できる', () => {
    const activity = readJson(lang, 'activity.json').activity as Json
    expect(isObj(activity)).toBe(true)
    for (const key of ['stats', 'template', 'member_fields']) {
      expect(isObj(activity[key]), `activity.${key}`).toBe(true)
    }
    expect(typeof (activity.stats as Json).title).toBe('string')
    expect(((activity.stats as Json).title as string).length).toBeGreaterThan(0)
  })

  it('AC-78: nuxt.config の files に activity.json が登録されている', () => {
    expect(filesOf(lang)).toContain(`${lang}/activity.json`)
  })

  it('AC-78: common.json の activity.* と deep merge しても両方の既存キーが残り、葉キーを奪い合わない', () => {
    const fromActivityFile = (readJson(lang, 'activity.json').activity ?? {}) as Json
    const fromCommon = readJson(lang, 'common.json').activity as Json
    expect(isObj(fromCommon)).toBe(true)

    const a = new Set(leafPaths(fromActivityFile))
    const overlap = leafPaths(fromCommon).filter((p) => a.has(p))
    expect(overlap, '同じ activity.* の葉キーを両ファイルで定義している').toEqual([])

    const merged: Json = {}
    deepMerge(merged, JSON.parse(JSON.stringify(readJson(lang, 'common.json'))) as Json)
    deepMerge(merged, JSON.parse(JSON.stringify(readJson(lang, 'activity.json'))) as Json)
    const mergedActivity = merged.activity as Json
    expect(typeof mergedActivity.pageTitle, 'common.json 側 activity.pageTitle が残る').toBe('string')
    expect(isObj(mergedActivity.stats), 'activity.json 側 activity.stats が残る').toBe(true)
    expect(isObj(mergedActivity.template), 'activity.json 側 activity.template が残る').toBe(true)
  })
})
