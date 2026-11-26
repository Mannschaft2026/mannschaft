// @vitest-environment node
/**
 * 番人 G2: FE における SYSTEM_ADMIN 判定の新規混入を検出する基準線番人（戦役 Z-A PR1、
 * docs/security/03_role_authority_model.md §3.6）。BE の SysAdminBypassGuardTest と同型。
 *
 * - frontend/app 配下（*.spec.ts・*.test.ts・types/generated を除く）の `'SYSTEM_ADMIN'`（引用符付き文字列）と
 *   `isSystemAdmin` の件数を、ファイル別・パターン別に数える（行番号に依存しない）。
 * - 基準線（sysadmin-bypass-baseline.json）より増えたら赤、基準線に無いファイルに現れたら赤、
 *   減ったのに基準線を下げていなければ赤（縮める一方）。
 * - 固定 allowlist（sysadmin-bypass-allowlist.json）は理由必須。運営コンソールそのもの
 *   （pages/system-admin/ 配下・middleware/system-admin.ts）と、SYSTEM_ADMIN の定義・拒否型の正本のみ。
 *   allowlist のファイルは数えない。実在しなくなった行も赤。
 * - コメント（// と block と HTML コメント）内の言及は数えない。
 *
 * 基準線の再生成（縮めたとき）:
 *   cd frontend && SYSADMIN_BASELINE_REGENERATE=1 npx vitest run tests/unit/guards/sysadmin-bypass-guard.spec.ts
 * 増える方向の再生成は PR レビューで拒否する。
 */
import { describe, it, expect } from 'vitest'
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const APP_ROOT = resolve(HERE, '..', '..', '..', 'app')
const BASELINE_PATH = join(HERE, 'sysadmin-bypass-baseline.json')
const ALLOWLIST_PATH = join(HERE, 'sysadmin-bypass-allowlist.json')

type Counts = Record<string, number>
type Baseline = Record<string, Counts>
interface AllowEntry {
  path: string
  reason: string
}

const PATTERNS: Record<string, RegExp> = {
  "'SYSTEM_ADMIN'": /(['"`])SYSTEM_ADMIN\1/g,
  isSystemAdmin: /\bisSystemAdmin\b/g,
}

/** 行コメント・ブロックコメント・HTML コメントを空白化する（文字列内の // は URL 等なので触らない簡易版）。 */
export function stripComments(src: string): string {
  return src
    .replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[\s;{}(),])\/\/[^\n]*/g, (m, p1: string) => p1 + ' '.repeat(m.length - p1.length))
}

export function countPatterns(src: string): Counts {
  const body = stripComments(src)
  const result: Counts = {}
  for (const [name, re] of Object.entries(PATTERNS)) {
    const n = (body.match(re) ?? []).length
    if (n > 0) result[name] = n
  }
  return result
}

export function isAllowed(path: string, allow: AllowEntry[]): boolean {
  return allow.some((a) => (a.path.endsWith('/') ? path.startsWith(a.path) : path === a.path))
}

/** 実測（allowlist 除外後）→ 平坦マップ "path\tpattern" → 件数 */
export function scan(sources: Record<string, string>, allow: AllowEntry[]): Counts {
  const flat: Counts = {}
  for (const [path, src] of Object.entries(sources)) {
    if (isAllowed(path, allow)) continue
    for (const [p, n] of Object.entries(countPatterns(src))) flat[`${path}\t${p}`] = n
  }
  return flat
}

export function flatten(b: Baseline): Counts {
  const flat: Counts = {}
  for (const [path, counts] of Object.entries(b)) {
    for (const [p, n] of Object.entries(counts)) flat[`${path}\t${p}`] = n
  }
  return flat
}

export function diff(baseline: Counts, actual: Counts): string[] {
  const v: string[] = []
  for (const [k, n] of Object.entries(actual)) {
    const b = baseline[k]
    const shown = k.replace('\t', '  ')
    if (b === undefined) v.push(`新規混入（基準線に無い）: ${shown} = ${n}`)
    else if (n > b) v.push(`増加: ${shown} 基準線 ${b} → 実測 ${n}`)
    else if (n < b) v.push(`減少したが基準線が未更新（縮める一方）: ${shown} 基準線 ${b} → 実測 ${n}`)
  }
  for (const [k, b] of Object.entries(baseline)) {
    if (!(k in actual)) v.push(`消滅したが基準線が未更新（縮める一方）: ${k.replace('\t', '  ')} 基準線 ${b} → 0`)
  }
  return v
}

function walk(dir: string, out: string[]): void {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    const st = statSync(full)
    if (st.isDirectory()) {
      if (name === 'node_modules') continue
      walk(full, out)
    } else if (/\.(ts|vue|js)$/.test(name) && !/\.(spec|test)\.(ts|js)$/.test(name)) {
      out.push(full)
    }
  }
}

function loadAppSources(): Record<string, string> {
  const files: string[] = []
  walk(APP_ROOT, files)
  const sources: Record<string, string> = {}
  for (const f of files) {
    const rel = relative(APP_ROOT, f).split(sep).join('/')
    if (rel.startsWith('types/generated/')) continue
    sources[rel] = readFileSync(f, 'utf-8')
  }
  return sources
}

function loadAllowlist(): AllowEntry[] {
  return JSON.parse(readFileSync(ALLOWLIST_PATH, 'utf-8')) as AllowEntry[]
}

describe('番人 G2: FE の SYSTEM_ADMIN 判定は基準線から増えない（縮める一方）', () => {
  it('allowlist は理由必須で、ワイルドカードや広域一括を含まない', () => {
    for (const a of loadAllowlist()) {
      expect(a.reason?.trim().length, `理由が必須: ${a.path}`).toBeGreaterThan(0)
      expect(a.path, `ワイルドカード禁止: ${a.path}`).not.toContain('*')
      // ディレクトリ一括は運営コンソール配下のみ許す
      if (a.path.endsWith('/')) expect(a.path).toBe('pages/system-admin/')
    }
  })

  it('実測が基準線と一致する', () => {
    const sources = loadAppSources()
    expect(Object.keys(sources).length, '走査対象が少なすぎる').toBeGreaterThan(100)
    const allow = loadAllowlist()
    const actual = scan(sources, allow)

    if (process.env.SYSADMIN_BASELINE_REGENERATE) {
      const out: Baseline = {}
      for (const [k, n] of Object.entries(actual)) {
        const [path, p] = k.split('\t')
        ;(out[path] ??= {})[p] = n
      }
      writeFileSync(BASELINE_PATH, JSON.stringify(out, null, 2) + '\n')
      return
    }

    const stale = allow.filter((a) => !a.path.endsWith('/') && !existsSync(join(APP_ROOT, a.path)))
    expect(stale.map((a) => a.path), 'allowlist に実在しないファイルがある（削除すること）').toEqual([])

    const violations = diff(flatten(JSON.parse(readFileSync(BASELINE_PATH, 'utf-8')) as Baseline), actual)
    expect(
      violations,
      'FE の SYSTEM_ADMIN 判定が基準線と一致しません（docs/security/03 §3.6）。増えた場合は直書きをやめる。'
        + '減った場合は SYSADMIN_BASELINE_REGENERATE=1 で基準線を下げる。\n' + violations.join('\n'),
    ).toEqual([])
  })
})

describe('番人 G2 の自己検証（検出器の偽陰性・偽陽性を晒す）', () => {
  const base = { 'composables/useX.ts': "export const a = (s) => s.isSystemAdmin ? 1 : 0" }
  const baseline = scan(base, [])

  it('基準線どおりなら緑、新規ファイル・件数増加は赤、減少の下げ忘れも赤', () => {
    expect(baseline['composables/useX.ts\tisSystemAdmin']).toBe(1)
    expect(diff(baseline, scan(base, []))).toEqual([])
    const withNew = { ...base, 'pages/new.vue': "<script setup>const r = u.role === 'SYSTEM_ADMIN'</script>" }
    expect(diff(baseline, scan(withNew, []))).toHaveLength(1)
    const withDq = { ...base, 'pages/new2.vue': 'const r = u.role === "SYSTEM_ADMIN"' }
    expect(diff(baseline, scan(withDq, []))).toHaveLength(1)
    const withMore = { 'composables/useX.ts': 'a.isSystemAdmin && b.isSystemAdmin' }
    expect(diff(baseline, scan(withMore, []))).toHaveLength(1)
    expect(diff(baseline, scan({ 'composables/useX.ts': 'export {}' }, []))).toHaveLength(1)
  })

  it('コメント内の言及は数えない', () => {
    const src = [
      '// isSystemAdmin は使わない',
      "/* 'SYSTEM_ADMIN' */",
      '<!-- isSystemAdmin -->',
      'const x = 1 // \'SYSTEM_ADMIN\'',
    ].join('\n')
    expect(countPatterns(src)).toEqual({})
  })

  it('allowlist のファイルは数えない（ディレクトリ接頭辞も）', () => {
    const src = { 'pages/system-admin/a.vue': "x === 'SYSTEM_ADMIN'", 'stores/useAuthStore.ts': 'isSystemAdmin' }
    const allow = [
      { path: 'pages/system-admin/', reason: 'r' },
      { path: 'stores/useAuthStore.ts', reason: 'r' },
    ]
    expect(scan(src, allow)).toEqual({})
    expect(Object.keys(scan(src, []))).toHaveLength(2)
  })
})
