// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * AppShell が <main> を持つため、その内側に載るレイアウトは <main> を重ねない（a11y）。
 * AppShell の外で使うレイアウト（public・landing・auth）は <main id="main-content"> をちょうど1つ持つ。
 */
const OUTSIDE_APP_SHELL = new Set(['public.vue', 'landing.vue', 'auth.vue'])
const all = readdirSync(__dirname).filter((f) => f.endsWith('.vue'))
const inside = all.filter((f) => !OUTSIDE_APP_SHELL.has(f))
const outside = all.filter((f) => OUTSIDE_APP_SHELL.has(f))

describe.each(inside)('layouts/%s (AppShell 内)', (name) => {
  it('<main> を含まない', () => {
    const src = readFileSync(resolve(__dirname, name), 'utf8')
    expect(src).not.toMatch(/<\/?main[\s>]/)
  })
})

describe.each(outside)('layouts/%s (AppShell 外)', (name) => {
  it('<main id="main-content"> をちょうど1つ持つ', () => {
    const src = readFileSync(resolve(__dirname, name), 'utf8')
    expect(src.match(/<main[\s>]/g)).toHaveLength(1)
    expect(src).toMatch(/<main[^>]*id="main-content"/)
  })
})
