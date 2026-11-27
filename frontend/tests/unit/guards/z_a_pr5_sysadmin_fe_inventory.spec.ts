// @vitest-environment node
/**
 * 戦役 Z-A PR5: FE の SYSTEM_ADMIN 判定の分類表（z_a_pr5_sysadmin_fe_inventory.tsv）と実コードの照合。
 *
 * 分類（設計書 z-a-design.md §4）:
 *   (i)   スコープの role/roleName が 'SYSTEM_ADMIN' であることを根拠にした導線。BE が返さなくなるので死にコード → PR5 で削除
 *   (ii)  authStore.isSystemAdmin を /system-admin への導線・拒否型・運営画面に使うもの → 残す
 *   (iii) それ以外 → G2 基準線（sysadmin-bypass-baseline.json）に残し、Z-C で縮める
 *
 * 照合は行番号ではなく G2 番人と同じキー「ファイル + パターン#所属関数」で行う（行番号のずれで壊れない）。
 * 計数ロジックは tests/unit/guards/sysadmin-bypass-guard.spec.ts（番人 G2）と同一。spec を import すると
 * G2 のテストが二重登録されるため、関数を写している（G2 側を変えたらここも合わせる）。
 *
 *   INV-01: 分類表の形式（7 列・分類値・code 行は g2_key 必須）
 *   INV-02: 分類表の件数（code 行の i/ii/iii）を固定し、分類の付け替えを PR 差分に出す
 *   INV-03: 実コードに分類表に無い SYSTEM_ADMIN 判定が無い（未分類の新規混入を防ぐ）
 *   INV-04: (ii)/(iii) の判定が残っている（Z-C 前に拒否型・運営導線を誤って消さない）
 *   INV-05【PR5 で green 化】: (i) の判定が実コードから消えている
 *   INV-06【PR5 で green 化】: G2 基準線が (i) の分だけ下がっている
 */
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const APP_ROOT = resolve(HERE, '..', '..', '..', 'app')
const INVENTORY = join(HERE, 'z_a_pr5_sysadmin_fe_inventory.tsv')
const BASELINE = join(HERE, 'sysadmin-bypass-baseline.json')

type Counts = Record<string, number>

// ── 番人 G2 と同一の計数（sysadmin-bypass-guard.spec.ts から写し） ──
const PATTERNS: Record<string, RegExp> = {
  "'SYSTEM_ADMIN'": /(['"`])SYSTEM_ADMIN\1/g,
  "'ROLE_SYSTEM_ADMIN'": /(['"`])ROLE_SYSTEM_ADMIN\1/g,
  'SYSTEM_ADMIN:': /(?<![\w'"`])SYSTEM_ADMIN\s*:/g,
  isSystemAdmin: /\bisSystemAdmin\b/g,
}
const NOT_FUNCTION_KEYWORDS = new Set(['if', 'for', 'while', 'switch', 'catch', 'function', 'return', 'await', 'typeof'])
function blockName(code: string, braceIdx: number): string {
  let j = braceIdx
  while (j > 0 && !';{}'.includes(code.charAt(j - 1))) j--
  const hdr = code.slice(Math.max(j, braceIdx - 400), braceIdx).trim()
  const fn = hdr.match(/\bfunction\s*\*?\s*(\w+)\s*[(<][^]*$/)
  if (fn) return fn[1] ?? ''
  const assigned = hdr.match(/\b(?:const|let|var)\s+(\w+)\b[^]*?(?:=>|\bfunction\b)[^]*$/)
  if (assigned) return assigned[1] ?? ''
  const method = hdr.match(/(?:^|[\s.])(?:async\s+)?(\w+)\s*(?:<[^>()]*>)?\s*\([^]*\)\s*(?::\s*[^{}]+)?$/)
  if (method && !NOT_FUNCTION_KEYWORDS.has(method[1] ?? '')) return method[1] ?? ''
  return ''
}
function enclosingFunctions(code: string, positions: number[]): string[] {
  const result: string[] = []
  const stack: string[] = []
  let pi = 0
  for (let i = 0; i < code.length && pi < positions.length; i++) {
    while (pi < positions.length && (positions[pi] ?? Infinity) <= i) {
      result.push(stack.findLast((n) => n !== '') ?? '-')
      pi++
    }
    if (code[i] === '{') stack.push(blockName(code, i))
    else if (code[i] === '}' && stack.length > 0) stack.pop()
  }
  while (pi < positions.length) {
    result.push(stack.findLast((n) => n !== '') ?? '-')
    pi++
  }
  return result
}
function stripComments(src: string): string {
  return src
    .replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[\s;{}(),])\/\/[^\n]*/g, (m, p1: string) => p1 + ' '.repeat(m.length - p1.length))
}
function countPatterns(src: string): Counts {
  const body = stripComments(src)
  const hits: { pos: number; name: string }[] = []
  for (const [name, re] of Object.entries(PATTERNS)) {
    for (const m of body.matchAll(re)) hits.push({ pos: m.index ?? 0, name })
  }
  hits.sort((a, b) => a.pos - b.pos)
  const fns = enclosingFunctions(body, hits.map((h) => h.pos))
  const result: Counts = {}
  hits.forEach((h, i) => {
    const key = `${h.name}#${fns[i]}`
    result[key] = (result[key] ?? 0) + 1
  })
  return result
}
function walk(dir: string, out: string[]): void {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) {
      if (name !== 'node_modules') walk(full, out)
    } else if (/\.(ts|vue|js)$/.test(name) && !/\.(spec|test)\.(ts|js)$/.test(name)) {
      out.push(full)
    }
  }
}
/** allowlist を除外しない全量（分類表は pages/system-admin 等の (ii) も含むため）。 */
function actualCounts(): Counts {
  const files: string[] = []
  walk(APP_ROOT, files)
  const flat: Counts = {}
  for (const f of files) {
    const rel = relative(APP_ROOT, f).split(sep).join('/')
    if (rel.startsWith('types/generated/')) continue
    for (const [k, n] of Object.entries(countPatterns(readFileSync(f, 'utf-8')))) flat[`${rel}\t${k}`] = n
  }
  return flat
}

// ── 分類表 ──
interface Row { path: string; line: number; kind: string; keys: string[]; snippet: string; cls: string; action: string }
function loadInventory(): Row[] {
  const lines = readFileSync(INVENTORY, 'utf-8').split('\n').filter((l) => l.trim() !== '' && !l.startsWith('#'))
  const [header, ...body] = lines
  expect(header).toBe('path\tline\tkind\tg2_key\tsnippet\tclass\taction')
  return body.map((l) => {
    const c = l.split('\t')
    expect(c, `列数は 7: ${l}`).toHaveLength(7)
    return { path: c[0] ?? '', line: Number(c[1]), kind: c[2] ?? '', keys: c[3] === '-' ? [] : (c[3] ?? '').split(','), snippet: c[4] ?? '', cls: c[5] ?? '', action: c[6] ?? '' }
  })
}
/** 分類別の「path\tkey → 件数」 */
function tally(rows: Row[], pick: (r: Row) => boolean): Counts {
  const t: Counts = {}
  for (const r of rows.filter((x) => x.kind === 'code' && pick(x))) {
    for (const k of r.keys) t[`${r.path}\t${k}`] = (t[`${r.path}\t${k}`] ?? 0) + 1
  }
  return t
}

describe('戦役Z-A PR5: FE の SYSTEM_ADMIN 判定の分類表', () => {
  it('INV-01: 分類表の形式が正しい', () => {
    for (const r of loadInventory()) {
      expect(['i', 'ii', 'iii'], `${r.path}:${r.line}`).toContain(r.cls)
      expect(['code', 'comment'], `${r.path}:${r.line}`).toContain(r.kind)
      if (r.kind === 'code') expect(r.keys.length, `code 行は g2_key 必須: ${r.path}:${r.line}`).toBeGreaterThan(0)
      expect(r.action.trim().length, `処置が必須: ${r.path}:${r.line}`).toBeGreaterThan(0)
    }
  })

  it('INV-02: code 行の分類件数（i=31 / ii=21 / iii=6）。付け替えは PR 差分で検分する', () => {
    const rows = loadInventory().filter((r) => r.kind === 'code')
    const n = (c: string) => rows.filter((r) => r.cls === c).length
    expect({ i: n('i'), ii: n('ii'), iii: n('iii') }).toEqual({ i: 31, ii: 21, iii: 6 })
  })

  it('INV-03: 実コードに分類表に無い SYSTEM_ADMIN 判定が無い（未分類の新規混入は赤）', () => {
    const all = tally(loadInventory(), () => true)
    const actual = actualCounts()
    const unknown = Object.entries(actual)
      .filter(([k, n]) => n > (all[k] ?? 0))
      .map(([k, n]) => `${k.replace('\t', '  ')} 実測 ${n} > 分類表 ${all[k] ?? 0}`)
    expect(unknown, '分類表に無い判定がある。分類（i/ii/iii）を付けて TSV に足すこと\n' + unknown.join('\n')).toEqual([])
  })

  it('INV-04: (ii)/(iii) の判定が残っている（拒否型・運営導線・Z-C 送りを PR5 で誤って消さない）', () => {
    const keep = tally(loadInventory(), (r) => r.cls !== 'i')
    const actual = actualCounts()
    const lost = Object.entries(keep)
      .filter(([k, n]) => (actual[k] ?? 0) < n)
      .map(([k, n]) => `${k.replace('\t', '  ')} 分類表 ${n} → 実測 ${actual[k] ?? 0}`)
    expect(lost, '(ii)/(iii) の判定が消えている\n' + lost.join('\n')).toEqual([])
  })

  it('INV-05【PR5 で green 化】: (i) の判定（スコープの role が SYSTEM_ADMIN を根拠にした導線）が実コードから消えている', () => {
    const rows = loadInventory()
    const keep = tally(rows, (r) => r.cls !== 'i')
    const remove = tally(rows, (r) => r.cls === 'i')
    const actual = actualCounts()
    const left = Object.keys(remove)
      .filter((k) => (actual[k] ?? 0) > (keep[k] ?? 0))
      .map((k) => `${k.replace('\t', '  ')} 実測 ${actual[k]}（PR5 後の期待 ${keep[k] ?? 0}）`)
    expect(left, '(i) の死にコードが残っている\n' + left.join('\n')).toEqual([])
  })

  it('INV-06【PR5 で green 化】: G2 基準線が (i) の分だけ下がっている', () => {
    const rows = loadInventory()
    const keep = tally(rows, (r) => r.cls !== 'i')
    const remove = tally(rows, (r) => r.cls === 'i')
    const baseline = JSON.parse(readFileSync(BASELINE, 'utf-8')) as Record<string, Counts>
    const stale = Object.keys(remove).flatMap((k) => {
      const [path = '', key = ''] = k.split('\t')
      const b = baseline[path]?.[key] ?? 0
      return b > (keep[k] ?? 0) ? [`${path}  ${key} 基準線 ${b}（PR5 後の期待 ${keep[k] ?? 0}）`] : []
    })
    expect(stale, 'G2 基準線を SYSADMIN_BASELINE_REGENERATE=1 で下げること\n' + stale.join('\n')).toEqual([])
  })
})
