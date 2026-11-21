// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/** AppShell が <main> を持つため、その内側に載るレイアウトは <main> を重ねない（a11y）。 */
describe.each(['team', 'organization'])('layouts/%s.vue', (name) => {
  it('<main> を含まない', () => {
    const src = readFileSync(resolve(__dirname, `${name}.vue`), 'utf8')
    expect(src).not.toMatch(/<\/?main[\s>]/)
  })
})
