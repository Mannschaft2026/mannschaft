import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * useActivityScopeId.resolveScopeId ユニットテスト（AC-79 / F06.4.1 先行修正・試練）
 *
 * 契約:
 *   - 数値文字列なら API を呼ばずに数値を返す
 *   - 取得に成功して該当なしなら null（未所属）
 *   - 取得に失敗したら例外を投げる（null に潰して未所属と区別がつかなくしない）
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// eslint-disable-next-line import/first
import { useActivityScopeId } from '~/composables/useActivityScopeId'

describe('useActivityScopeId.resolveScopeId', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('AC-79: 数値文字列なら API を呼ばず数値を返す（TEAM / ORGANIZATION とも）', async () => {
    const { resolveScopeId } = useActivityScopeId()

    expect(await resolveScopeId('TEAM', '42')).toBe(42)
    expect(await resolveScopeId('ORGANIZATION', '7')).toBe(7)

    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('AC-79: TEAM の slug は /api/v1/me/teams から数値 id を解決する', async () => {
    mockFetch.mockResolvedValueOnce({ data: [{ id: 11, slug: 'other' }, { id: 12, slug: 'my-team' }] })
    const { resolveScopeId } = useActivityScopeId()

    expect(await resolveScopeId('TEAM', 'my-team')).toBe(12)
    expect(mockFetch).toHaveBeenCalledTimes(1)
    expect(mockFetch.mock.calls[0]![0]).toBe('/api/v1/me/teams')
  })

  it('AC-79: ORGANIZATION の slug は /api/v1/me/organizations から数値 id を解決する', async () => {
    mockFetch.mockResolvedValueOnce({ data: [{ id: 21, slug: 'my-org' }] })
    const { resolveScopeId } = useActivityScopeId()

    expect(await resolveScopeId('ORGANIZATION', 'my-org')).toBe(21)
    expect(mockFetch.mock.calls[0]![0]).toBe('/api/v1/me/organizations')
  })

  it.each(['TEAM', 'ORGANIZATION'] as const)(
    'AC-79: %s で取得に成功し該当なしなら null を返す',
    async (scopeType) => {
      mockFetch.mockResolvedValueOnce({ data: [{ id: 1, slug: 'someone-else' }] })
      const { resolveScopeId } = useActivityScopeId()

      expect(await resolveScopeId(scopeType, 'not-mine')).toBeNull()
    },
  )

  it('AC-79: 取得に成功し data が空・欠落でも null を返す（例外にしない）', async () => {
    mockFetch.mockResolvedValueOnce({ data: [] })
    mockFetch.mockResolvedValueOnce({})
    const { resolveScopeId } = useActivityScopeId()

    expect(await resolveScopeId('TEAM', 'x')).toBeNull()
    expect(await resolveScopeId('ORGANIZATION', 'x')).toBeNull()
  })

  it.each(['TEAM', 'ORGANIZATION'] as const)(
    'AC-79: %s で取得に失敗したら null に潰さず例外を投げる',
    async (scopeType) => {
      mockFetch.mockRejectedValueOnce(new Error('network down'))
      const { resolveScopeId } = useActivityScopeId()

      await expect(resolveScopeId(scopeType, 'my-slug')).rejects.toThrow('network down')
    },
  )
})
