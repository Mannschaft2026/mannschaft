import { describe, expect, it } from 'vitest'
import { buildSearchQuery } from '~/composables/useSearchApi'

/** F04.6 グローバル検索 API のクエリ組み立て（CMP-261116-0722 PR-1）。BE の camelCase パラメータ名に合わせる。 */
describe('buildSearchQuery', () => {
  it('q のみなら type・page・size を付けない（概要ビュー）', () => {
    const query = new URLSearchParams(buildSearchQuery({ q: 'グラウンド' }))
    expect(query.get('q')).toBe('グラウンド')
    expect([...query.keys()]).toEqual(['q'])
  })

  it('種別はカンマ区切り、page は 0 始まり、size をそのまま渡す（旧 perPage は使わない）', () => {
    const query = new URLSearchParams(
      buildSearchQuery({ q: 'ab', types: ['POST', 'FILE'], page: 0, size: 20 }),
    )
    expect(query.get('type')).toBe('POST,FILE')
    expect(query.get('page')).toBe('0')
    expect(query.get('size')).toBe('20')
    expect(query.has('perPage')).toBe(false)
  })

  it('スコープと絞り込みを camelCase で渡す', () => {
    const query = new URLSearchParams(
      buildSearchQuery({
        q: 'ab',
        scopeType: 'TEAM',
        scopeId: 5,
        dateFrom: '2026-01-01',
        dateTo: '2026-01-31',
        authorId: 9,
      }),
    )
    expect(query.get('scopeType')).toBe('TEAM')
    expect(query.get('scopeId')).toBe('5')
    expect(query.get('dateFrom')).toBe('2026-01-01')
    expect(query.get('dateTo')).toBe('2026-01-31')
    expect(query.get('authorId')).toBe('9')
  })
})
