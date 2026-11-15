import { describe, expect, it } from 'vitest'
import { resolveChannelDisplayName } from './channelDisplayName'

type Src = Parameters<typeof resolveChannelDisplayName>[0]

function src(over: {
  partner?: string | null
  name?: string | null
  derived?: string | null
}): Src {
  return {
    dmPartner:
      over.partner == null ? null : { userId: 1, displayName: over.partner, avatarUrl: null },
    meta: { name: over.name ?? null, iconKey: null, description: null },
    derivedName: over.derived ?? null,
  }
}

describe('resolveChannelDisplayName', () => {
  const UNNAMED = '名称未設定'

  it('DM は相手名を最優先する', () => {
    expect(resolveChannelDisplayName(src({ partner: '花子', name: 'x', derived: 'y' }), UNNAMED)).toBe('花子')
  })
  it('明示名があれば導出名より優先する', () => {
    expect(resolveChannelDisplayName(src({ name: '作戦会議', derived: 'A, B' }), UNNAMED)).toBe('作戦会議')
  })
  it('空白のみの名前は飛ばして導出名を使う', () => {
    expect(resolveChannelDisplayName(src({ name: '   ', derived: 'A, B' }), UNNAMED)).toBe('A, B')
  })
  it('空文字の名前は飛ばして導出名を使う', () => {
    expect(resolveChannelDisplayName(src({ name: '', derived: 'A, B' }), UNNAMED)).toBe('A, B')
  })
  it('導出名が空白なら未命名文言になる', () => {
    expect(resolveChannelDisplayName(src({ name: null, derived: '  ' }), UNNAMED)).toBe(UNNAMED)
  })
  it('すべて null なら未命名文言になる', () => {
    expect(resolveChannelDisplayName(src({}), UNNAMED)).toBe(UNNAMED)
  })
})
