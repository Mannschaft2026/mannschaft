import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import { blogPublishRedirectPath } from './blogPublishRedirect'

describe('blogPublishRedirectPath', () => {
  let consoleError: MockInstance<typeof console.error>

  beforeEach(() => {
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    consoleError.mockRestore()
  })

  it('チーム記事は数値IDではなく記事応答の teamSlug のブログへ遷移する', () => {
    expect(blogPublishRedirectPath({ scopeType: 'TEAM', teamSlug: 'tokyo-fc', organizationSlug: null }))
      .toBe('/teams/tokyo-fc/blog')
    expect(consoleError).not.toHaveBeenCalled()
  })

  it('組織記事は数値IDではなく記事応答の organizationSlug へ遷移する', () => {
    expect(blogPublishRedirectPath({ scopeType: 'ORGANIZATION', teamSlug: null, organizationSlug: 'kanto-league' }))
      .toBe('/organizations/kanto-league')
    expect(consoleError).not.toHaveBeenCalled()
  })

  it('個人記事は /dashboard（契約違反ではないので痕跡を残さない）', () => {
    expect(blogPublishRedirectPath({ scopeType: null, teamSlug: null, organizationSlug: null })).toBe('/dashboard')
    expect(consoleError).not.toHaveBeenCalled()
  })

  it('slug は URL エンコードする', () => {
    expect(blogPublishRedirectPath({ scopeType: 'TEAM', teamSlug: 'a b', organizationSlug: null }))
      .toBe('/teams/a%20b/blog')
  })

  it('チーム記事なのに teamSlug が無い（BE の契約違反）は console.error を残して /dashboard', () => {
    expect(blogPublishRedirectPath({ scopeType: 'TEAM', teamSlug: null, organizationSlug: null })).toBe('/dashboard')
    expect(consoleError).toHaveBeenCalledTimes(1)
    expect(String(consoleError.mock.calls[0]?.[0])).toContain('teamSlug')
  })

  it('チーム記事に組織の slug しか無くても、組織ページへは遷移しない', () => {
    expect(blogPublishRedirectPath({ scopeType: 'TEAM', teamSlug: '', organizationSlug: 'kanto-league' }))
      .toBe('/dashboard')
    expect(consoleError).toHaveBeenCalledTimes(1)
  })

  it('組織記事なのに organizationSlug が無い（BE の契約違反）は console.error を残して /dashboard', () => {
    expect(blogPublishRedirectPath({ scopeType: 'ORGANIZATION', teamSlug: null, organizationSlug: null }))
      .toBe('/dashboard')
    expect(consoleError).toHaveBeenCalledTimes(1)
    expect(String(consoleError.mock.calls[0]?.[0])).toContain('organizationSlug')
  })

  it('不明な scopeType は console.error を残して /dashboard', () => {
    expect(blogPublishRedirectPath({ scopeType: 'PUBLIC', teamSlug: 'tokyo-fc', organizationSlug: null }))
      .toBe('/dashboard')
    expect(consoleError).toHaveBeenCalledTimes(1)
  })
})
