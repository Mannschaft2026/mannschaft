// @vitest-environment node
/**
 * 番人 G2: FE における SYSTEM_ADMIN 判定の新規混入を検出する基準線番人（戦役 Z-A PR1、
 * docs/security/03_role_authority_model.md §3.6）。BE の SysAdminBypassGuardTest と同型。
 *
 * - frontend/app 配下（*.spec.ts・*.test.ts・types/generated を除く）の次のパターンを、
 *   ファイル別・パターン別・「所属する関数名」別に数える（行番号に依存しない。BE の「パターン @ 所属メソッド」と同型）:
 *   引用符付きの `'SYSTEM_ADMIN'` / `'ROLE_SYSTEM_ADMIN'`、引用符なしのオブジェクトキー `SYSTEM_ADMIN:`
 *   （useAdminScopeGuard.ts の `SYSTEM_ADMIN: 2` 等）、`isSystemAdmin`。
 *   所属関数名をキーに含めるのは、同一パターン内の入れ替え（拒否型を 1 本消して許可型を別の関数へ 1 本足す等）で
 *   合計件数が変わらずに素通しが混入するのを検出するため。行の中身を編集しても関数名が同じなら赤にならない
 *   （行ハッシュ方式の脆さの解消）。関数の外（トップレベル・テンプレート・オブジェクトリテラル）は `-`。
 *   既知の限界: 同一関数内での拒否↔許可の入れ替え（`return false` → `return true` 等）と、同名関数の合算は検出できない。
 * - 基準線（sysadmin-bypass-baseline.json）より増えたら赤、基準線に無いキーに現れたら赤、
 *   減ったのに基準線を下げていなければ赤（縮める一方）。
 * - 固定 allowlist（sysadmin-bypass-allowlist.json）は理由必須。運営コンソールそのもの
 *   （pages/system-admin/ 配下・middleware/system-admin.ts）と、SYSTEM_ADMIN の定義・拒否型の正本のみ。
 *   allowlist のファイルは数えず、基準線と同じファイルを両方に載せることも禁止（排他）。実在しなくなった行も赤。
 *   注: useAuthStore（isSystemAdmin getter の定義元）と useAppNavGroups（ナビ導線の振り分け）は丸ごと除外している
 *   ため、これらへの新規混入は本番人では見えない。BE 側の基準線と PR レビューで補う（Z-A 後続で基準線へ移す）。
 * - コメント（// と block と HTML コメント）内の言及は数えない。
 *
 * 基準線の再生成（縮めたとき・意図した変更のとき）:
 *   cd frontend && SYSADMIN_BASELINE_REGENERATE=1 npx vitest run tests/unit/guards/sysadmin-bypass-guard.spec.ts
 * 環境変数が '1' のときだけ発動する。増える方向の再生成は PR レビューで拒否する
 * （基準線ファイルは CODEOWNERS 対象で、件数が増える変更は PR 差分で目立つ）。
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
  "'ROLE_SYSTEM_ADMIN'": /(['"`])ROLE_SYSTEM_ADMIN\1/g,
  // 引用符なしのオブジェクトキー（例: `SYSTEM_ADMIN: 2`）
  'SYSTEM_ADMIN:': /(?<![\w'"`])SYSTEM_ADMIN\s*:/g,
  isSystemAdmin: /\bisSystemAdmin\b/g,
}

const NOT_FUNCTION_KEYWORDS = new Set(['if', 'for', 'while', 'switch', 'catch', 'function', 'return', 'await', 'typeof'])

/** `{` の直前の見出しから「ブロックの名前」を決める。関数・メソッド・関数代入の const → 名前、それ以外は空文字（外側を引き継ぐ）。 */
export function blockName(code: string, braceIdx: number): string {
  let j = braceIdx
  while (j > 0 && !';{}'.includes(code[j - 1])) j--
  const hdr = code.slice(Math.max(j, braceIdx - 400), braceIdx).trim()
  const fn = hdr.match(/\bfunction\s*\*?\s*(\w+)\s*[(<][^]*$/)
  if (fn) return fn[1]
  const assigned = hdr.match(/\b(?:const|let|var)\s+(\w+)\b[^]*?(?:=>|\bfunction\b)[^]*$/)
  if (assigned) return assigned[1]
  const method = hdr.match(/(?:^|[\s.])(?:async\s+)?(\w+)\s*(?:<[^>()]*>)?\s*\([^]*\)\s*(?::\s*[^{}]+)?$/)
  if (method && !NOT_FUNCTION_KEYWORDS.has(method[1])) return method[1]
  return ''
}

/** 昇順の位置ごとの所属関数名（関数の外は `-`）。code はコメント除去済み。 */
export function enclosingFunctions(code: string, positions: number[]): string[] {
  const result: string[] = []
  const stack: string[] = []
  let pi = 0
  for (let i = 0; i < code.length && pi < positions.length; i++) {
    while (pi < positions.length && positions[pi] <= i) {
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

/** 行コメント・ブロックコメント・HTML コメントを空白化する（文字列内の // は URL 等なので触らない簡易版）。 */
export function stripComments(src: string): string {
  return src
    .replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[\s;{}(),])\/\/[^\n]*/g, (m, p1: string) => p1 + ' '.repeat(m.length - p1.length))
}

export function countPatterns(src: string): Counts {
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

export function isAllowed(path: string, allow: AllowEntry[]): boolean {
  return allow.some((a) => (a.path.endsWith('/') ? path.startsWith(a.path) : path === a.path))
}

/** 実測（allowlist 除外後）→ 平坦マップ "path\tpattern#所属関数" → 件数 */
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

  it('allowlist と基準線に同じファイルが載っていない（排他）', () => {
    const baseline = JSON.parse(readFileSync(BASELINE_PATH, 'utf-8')) as Baseline
    const allow = loadAllowlist()
    for (const path of Object.keys(baseline)) {
      expect(isAllowed(path, allow), `allowlist と基準線に同じファイルがある: ${path}`).toBe(false)
    }
  })

  it('実測が基準線と一致する', () => {
    const sources = loadAppSources()
    expect(Object.keys(sources).length, '走査対象が少なすぎる').toBeGreaterThan(100)
    const allow = loadAllowlist()
    const actual = scan(sources, allow)

    if (process.env.SYSADMIN_BASELINE_REGENERATE === '1') {
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
  const base = { 'composables/useX.ts': 'export const a = (s) => s.isSystemAdmin ? 1 : 0' }
  const baseline = scan(base, [])
  const baseKey = Object.keys(baseline)[0]

  it('基準線どおりなら緑、新規ファイル・件数増加は赤、減少の下げ忘れも赤', () => {
    expect(baseKey).toBe('composables/useX.ts\tisSystemAdmin#-')
    expect(baseline[baseKey]).toBe(1)
    expect(diff(baseline, scan(base, []))).toEqual([])
    const withNew = { ...base, 'pages/new.vue': "<script setup>const r = u.role === 'SYSTEM_ADMIN'</script>" }
    expect(diff(baseline, scan(withNew, []))).toHaveLength(1)
    const withDq = { ...base, 'pages/new2.vue': 'const r = u.role === "SYSTEM_ADMIN"' }
    expect(diff(baseline, scan(withDq, []))).toHaveLength(1)
    const withMore = { 'composables/useX.ts': 'export const a = (s) => s.isSystemAdmin ? 1 : 0\nconst b = s.isSystemAdmin' }
    expect(diff(baseline, scan(withMore, []))).toHaveLength(1)
    expect(diff(baseline, scan({ 'composables/useX.ts': 'export {}' }, []))).toHaveLength(1)
  })

  it.each([
    ["const ROLE_RANK = { SYSTEM_ADMIN: 2, ADMIN: 1 }"],
    ["const r = u.authority === 'ROLE_SYSTEM_ADMIN'"],
    ['const r = u.authority === "ROLE_SYSTEM_ADMIN"'],
    ['const k = { SYSTEM_ADMIN : 1 }'],
  ])('引用符なしキー・ROLE_ 付きも新規混入として検出する: %s', (line) => {
    const withNew = { ...base, 'pages/new.vue': line }
    expect(diff(baseline, scan(withNew, []))).toHaveLength(1)
  })

  it('同一パターン内の入れ替え（別の関数へ移す）は合計件数が同じでも赤。関数名が同じなら行の編集では赤にならない', () => {
    const before = { 'composables/useY.ts': "function deny(role) { return role === 'SYSTEM_ADMIN' }\nfunction other() { return 1 }" }
    const moved = { 'composables/useY.ts': "function deny(role) { return 1 }\nfunction other(role) { return role === 'SYSTEM_ADMIN' }" }
    const b = scan(before, [])
    expect(Object.keys(b)).toEqual(["composables/useY.ts\t'SYSTEM_ADMIN'#deny"])
    expect(diff(b, scan(before, []))).toEqual([])
    expect(diff(b, scan(moved, []))).toHaveLength(2)
    // 空白・インデント・行の増減・引数名の変更だけでは赤にならない
    const edited = {
      'composables/useY.ts':
        "function deny(r) {\n  const x = 1\n    return r   ===  'SYSTEM_ADMIN'\n}\nfunction other() { return 1 }",
    }
    expect(diff(b, scan(edited, []))).toEqual([])
    // アロー関数・メソッド・computed の所属名
    const arrow = "const canSee = computed(() => {\n  return u.role === 'SYSTEM_ADMIN'\n})\nconst o = { m(x) { return x.isSystemAdmin } }"
    expect(Object.keys(countPatterns(arrow)).sort()).toEqual(["'SYSTEM_ADMIN'#canSee", 'isSystemAdmin#m'])
  })

  it('コメント内の言及は数えない', () => {
    const src = [
      '// isSystemAdmin は使わない',
      "/* 'SYSTEM_ADMIN' */",
      '<!-- isSystemAdmin -->',
      "const x = 1 // 'SYSTEM_ADMIN'",
      '// SYSTEM_ADMIN: 2',
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
