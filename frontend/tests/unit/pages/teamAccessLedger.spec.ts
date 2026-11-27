// @vitest-environment node
import { readdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { TEAM_ACCESS_LEDGER } from './teamAccessLedger'

// CMP-261124-0159 PR-F1 / AC-0159-108: FE 画面台帳と pages/teams/[slug]/** の glob・親シェルの SHELL_SEGMENTS を双方向に突き合わせる番人。
const appDir = resolve(__dirname, '../../../app')
const pagesDir = join(appDir, 'pages/teams/[slug]')

function listVueFiles(dir: string, prefix = ''): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) out.push(...listVueFiles(join(dir, entry.name), `${prefix}${entry.name}/`))
    else if (entry.name.endsWith('.vue')) out.push(`${prefix}${entry.name}`)
  }
  return out
}

const segmentOf = (file: string) => (file.includes('/') ? file.split('/')[0]! : file.replace(/\.vue$/, ''))

function readShellSegments(): string[] {
  const src = readFileSync(join(appDir, 'pages/teams/[slug].vue'), 'utf8')
  const block = /const SHELL_SEGMENTS = new Set\(\[([\s\S]*?)\]\)/.exec(src)?.[1]
  if (!block) throw new Error('SHELL_SEGMENTS が [slug].vue から読めない（番人の前提が崩れた）')
  return [...block.matchAll(/'([^']+)'/g)].map(m => m[1]!)
}

describe('チーム配下画面の台帳（AC-0159-108）', () => {
  const actual = listVueFiles(pagesDir).sort()
  const ledgerFiles = TEAM_ACCESS_LEDGER.map(e => e.file).sort()

  it('台帳と pages/teams/[slug]/** の .vue が過不足なく一致する（1 件の欠落・余剰で赤）', () => {
    expect(ledgerFiles).toEqual(actual)
  })

  it('補助 ID は F-<segment>-<n> で一意、n は同一セグメント内で 1 から連番', () => {
    const ids = TEAM_ACCESS_LEDGER.map(e => e.id)
    expect(new Set(ids).size).toBe(ids.length)
    const counters = new Map<string, number>()
    for (const e of TEAM_ACCESS_LEDGER) {
      const seg = segmentOf(e.file)
      const n = (counters.get(seg) ?? 0) + 1
      counters.set(seg, n)
      expect(e.id).toBe(`F-${seg}-${n}`)
    }
  })

  it('系統 1 は親シェルの SHELL_SEGMENTS と一致し、handling は shell。系統 2 は shell にならない', () => {
    const shell = new Set(readShellSegments())
    for (const e of TEAM_ACCESS_LEDGER) {
      const inShell = shell.has(segmentOf(e.file))
      expect(e.system, e.id).toBe(inShell ? 1 : 2)
      if (e.system === 1) expect(e.handling, e.id).toBe('shell')
      else expect(e.handling, e.id).not.toBe('shell')
    }
    const ledgerSegs = new Set(TEAM_ACCESS_LEDGER.filter(e => e.system === 1).map(e => segmentOf(e.file)))
    expect([...ledgerSegs].sort()).toEqual([...shell].sort())
  })

  it('handling が gate の行は useTeamAccessGate を使い、pending の行は使っていない（ラチェット）', () => {
    for (const e of TEAM_ACCESS_LEDGER.filter(x => x.system === 2)) {
      const uses = readFileSync(join(pagesDir, e.file), 'utf8').includes('useTeamAccessGate')
      if (e.handling === 'gate') expect(uses, `${e.id} は gate 宣言だが useTeamAccessGate が無い`).toBe(true)
      else expect(uses, `${e.id} は pending 宣言だが適用済み（台帳を gate に更新する）`).toBe(false)
    }
  })

  it('pending は担当 PR を名指しする', () => {
    for (const e of TEAM_ACCESS_LEDGER.filter(x => x.handling.startsWith('pending'))) {
      expect(e.handling, e.id).toMatch(/^pending:PR-[A-Za-z0-9]+$/)
    }
  })
})
