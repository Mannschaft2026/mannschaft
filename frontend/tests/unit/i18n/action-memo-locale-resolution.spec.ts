import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, resolve, join, extname } from 'node:path'

/**
 * action_memo.json の名前空間ラッパー検証（CMP-261119-2031）。
 *
 * Nuxt i18n ではファイル名は名前空間にならない。呼び出し側は t('action_memo.…') で参照するため、
 * action_memo.json の最上位は action_memo の1キーだけでなければならない（さもないとキーが生表示され、
 * title/settings/error/dialog 等が他ファイルと衝突して root を汚す）。
 */
const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const here = dirname(fileURLToPath(import.meta.url))
const appDir = resolve(here, '../../../app')
const localesDir = resolve(appDir, 'locales')
const nuxtConfig = readFileSync(resolve(here, '../../../nuxt.config.ts'), 'utf-8')

type Json = { [k: string]: unknown }
const isObj = (v: unknown): v is Json => !!v && typeof v === 'object' && !Array.isArray(v)

function deepMerge(target: Json, src: Json): Json {
  for (const [k, v] of Object.entries(src)) {
    const cur = target[k]
    target[k] = isObj(cur) && isObj(v) ? deepMerge(cur, v) : v
  }
  return target
}

function filesOf(lang: string): string[] {
  const start = nuxtConfig.indexOf(`code: '${lang}'`)
  const filesIdx = nuxtConfig.indexOf('files: [', start)
  const end = nuxtConfig.indexOf(']', filesIdx)
  return [...nuxtConfig.slice(filesIdx, end).matchAll(/'([^']+)'/g)].map((m) => m[1]!)
}

async function loadFile(rel: string): Promise<Json> {
  const abs = resolve(localesDir, rel)
  if (extname(abs) === '.json') return JSON.parse(readFileSync(abs, 'utf-8').replace(/^\u{FEFF}/u, '')) as Json
  const mod = (await import(/* @vite-ignore */ pathToFileURL(abs).href)) as { default: Json }
  return mod.default
}

async function merged(lang: string): Promise<Json> {
  const out: Json = {}
  for (const f of filesOf(lang)) deepMerge(out, JSON.parse(JSON.stringify(await loadFile(f))) as Json)
  return out
}

function walk(dir: string, acc: string[] = []): string[] {
  for (const n of readdirSync(dir)) {
    if (n === 'locales' || n === 'generated') continue
    const p = join(dir, n)
    if (statSync(p).isDirectory()) walk(p, acc)
    else if (/\.(vue|ts)$/.test(n)) acc.push(p)
  }
  return acc
}

const staticKeys = new Set<string>()
const dynamicUses: string[] = []
for (const f of walk(appDir)) {
  const src = readFileSync(f, 'utf-8')
  if (!src.includes('action_memo.')) continue
  for (const m of src.matchAll(/(['"`])(action_memo\.[^'"`]*)\1/g)) {
    const key = m[2]!
    if (key.includes('${')) dynamicUses.push(`${f}: ${key}`)
    else staticKeys.add(key)
  }
}

function resolveKey(root: Json, key: string): unknown {
  let cur: unknown = root
  for (const p of key.split('.')) {
    if (!isObj(cur)) return undefined
    cur = cur[p]
  }
  return cur
}

function leaves(obj: unknown, prefix = ''): string[] {
  if (isObj(obj)) return Object.entries(obj).flatMap(([k, v]) => leaves(v, prefix ? `${prefix}.${k}` : k))
  return [prefix]
}

describe('action_memo.json 名前空間ラッパー', () => {
  it('参照側に静的キーが存在する（走査の健全性）', () => {
    expect(staticKeys.size).toBeGreaterThan(100)
  })

  it('動的キー（テンプレートリテラル）は、列挙したうえで全候補が解決できる', async () => {
    // 現時点で動的キーは無い。増えた場合はここで検出して取りうる値の検証を追加すること。
    expect(dynamicUses).toEqual([])
  })

  for (const lang of LANGS) {
    describe(lang, () => {
      it('AC2/AC4: action_memo.json の最上位は action_memo の1キーだけ（root を汚さない）', () => {
        const raw = JSON.parse(readFileSync(resolve(localesDir, lang, 'action_memo.json'), 'utf-8')) as Json
        expect(Object.keys(raw)).toEqual(['action_memo'])
      })

      it('AC1: 参照されている静的キーがすべて文字列に解決できる', async () => {
        const root = await merged(lang)
        const unresolved = [...staticKeys].filter((k) => typeof resolveKey(root, k) !== 'string')
        expect(unresolved).toEqual([])
      })

      it('AC3: リーフキー集合が ja と一致し、空文字の値がない', () => {
        const load = (l: string) =>
          (JSON.parse(readFileSync(resolve(localesDir, l, 'action_memo.json'), 'utf-8')) as Json)
        const base = new Set(leaves(load('ja').action_memo))
        const cur = load(lang).action_memo
        const keys = new Set(leaves(cur))
        expect([...base].filter((k) => !keys.has(k))).toEqual([])
        expect([...keys].filter((k) => !base.has(k))).toEqual([])
        const empties = [...keys].filter((k) => resolveKey(cur as Json, k) === '')
        expect(empties).toEqual([])
      })
    })
  }
})
