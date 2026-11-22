import { describe, expect, it } from 'vitest'
import { blogPublishRedirectPath } from './blogPublishRedirect'

const teams = [{ id: 1, slug: 'tokyo-fc' }, { id: 2, slug: 'osaka-fc' }]
const organizations = [{ id: 10, slug: 'kanto-league' }]

describe('blogPublishRedirectPath', () => {
  it('チームは数値IDではなく slug のブログへ遷移する', () => {
    expect(blogPublishRedirectPath('TEAM', '1', { teams, organizations })).toBe('/teams/tokyo-fc/blog')
  })

  it('組織は数値IDではなく slug へ遷移する', () => {
    expect(blogPublishRedirectPath('ORGANIZATION', '10', { teams, organizations })).toBe('/organizations/kanto-league')
  })

  it('個人スコープは /dashboard', () => {
    expect(blogPublishRedirectPath(null, null, { teams, organizations })).toBe('/dashboard')
  })

  it('slug を解決できないスコープは数値IDのURLを作らず /dashboard', () => {
    expect(blogPublishRedirectPath('TEAM', '999', { teams, organizations })).toBe('/dashboard')
    expect(blogPublishRedirectPath('ORGANIZATION', '999', { teams, organizations })).toBe('/dashboard')
  })
})
