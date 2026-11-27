/**
 * 戦役 Z-A PR5 AC9: 運営アカウントにテナント管理の導線（スコープのサイドバー管理メニュー）が出ないこと、
 * 一般利用者の導線が変わらないことを固定する。
 *
 * 前提（マスター決定 2026-11-26）: 運営は運営専用アカウントでテナントに所属しない。ロール表示は実ロールのみ。
 * PR3/PR5 以後、BE の /me/permissions は所属の無い運営アカウントに roleName=null を返し、
 * "SYSTEM_ADMIN" を返さない。FE も roleName==='SYSTEM_ADMIN' を理由に管理メニューを出してはならない
 * （BaseSidebar.vue の SYSTEM_ADMIN バイパスと、モジュール取得の省略分岐は死にコードとして削除する）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'

const { apiMock, policyMock } = vi.hoisted(() => ({ apiMock: vi.fn(), policyMock: vi.fn() }))
vi.mock('~/composables/useApi', () => ({
  useApi: () => (url: string, ...args: unknown[]) =>
    url.endsWith('/feature-display-policies') ? policyMock(url, ...args) : apiMock(url, ...args),
}))
const BaseSidebar = (await import('./BaseSidebar.vue')).default

const categories = [{
  key: 'home',
  labelKey: 'common.menu',
  icon: 'pi pi-home',
  items: [
    { labelKey: 'common.menu', icon: '', path: 'admin-only', moduleSlug: null, requiredRole: 'ADMIN' as const },
    { labelKey: 'common.menu', icon: '', path: 'deputy-only', moduleSlug: null, requiredRole: 'DEPUTY_ADMIN' as const },
    { labelKey: 'common.menu', icon: '', path: 'member-item', moduleSlug: null, requiredRole: 'MEMBER' as const },
  ],
}]

function respond(roleName: string | null, scopeAdmin = false) {
  apiMock.mockImplementation((url: string) => Promise.resolve(
    url.endsWith('/me/permissions')
      ? { data: { roleName, permissions: [], scopeAdmin } }
      : { data: [] },
  ))
}

function asPlatformAccount() {
  useAuthStore().user = { id: 9001, email: 'ops@example.com', fullName: '運営', profileImageUrl: null, systemRole: 'SYSTEM_ADMIN' }
}

function asTenantUser() {
  useAuthStore().user = { id: 9002, email: 'user@example.com', fullName: '利用者', profileImageUrl: null }
}

async function mountSidebar(scopeType: 'team' | 'organization' = 'team', memberLensActive = false) {
  const wrapper = await mountSuspended(BaseSidebar, {
    props: { scopeType, scopeId: 'scope-a', categories, memberLensActive },
  })
  await flushPromises()
  return wrapper
}

const base = (scopeType: 'team' | 'organization') => scopeType === 'team' ? '/teams/scope-a' : '/organizations/scope-a'
const linkExists = (w: Awaited<ReturnType<typeof mountSidebar>>, scopeType: 'team' | 'organization', path: string) =>
  w.find(`a[href="${base(scopeType)}/${path}"]`).exists()

describe('BaseSidebar — 戦役Z-A PR5 AC9: 運営アカウントにテナント管理メニューを出さない', () => {
  beforeEach(() => {
    localStorage.clear()
    clearNuxtState('scope-feature-display-policies')
    policyMock.mockReset().mockResolvedValue({ data: { features: [] } })
    apiMock.mockReset()
  })

  it.each(['team', 'organization'] as const)(
    '所属の無い運営アカウント（BE は roleName=null）の %s サイドバーに管理・副管理・メンバー項目が出ない',
    async (scopeType) => {
      asPlatformAccount()
      respond(null)
      const wrapper = await mountSidebar(scopeType)
      expect(linkExists(wrapper, scopeType, 'admin-only')).toBe(false)
      expect(linkExists(wrapper, scopeType, 'deputy-only')).toBe(false)
      expect(linkExists(wrapper, scopeType, 'member-item')).toBe(false)
    },
  )

  it.each(['team', 'organization'] as const)(
    '旧応答 roleName="SYSTEM_ADMIN" を受けても %s の管理・副管理メニューへのバイパスを効かせない',
    async (scopeType) => {
      asPlatformAccount()
      respond('SYSTEM_ADMIN')
      const wrapper = await mountSidebar(scopeType)
      expect(linkExists(wrapper, scopeType, 'admin-only')).toBe(false)
      expect(linkExists(wrapper, scopeType, 'deputy-only')).toBe(false)
    },
  )

  it('旧応答 roleName="SYSTEM_ADMIN" はメンバーでない人として扱い、モジュールを取らず、エラー面も出さない（運営専用の分岐ではなく、メンバーでない人全員に効く判定）', async () => {
    asPlatformAccount()
    respond('SYSTEM_ADMIN')
    const wrapper = await mountSidebar('team')
    expect(apiMock).not.toHaveBeenCalledWith('/api/v1/teams/scope-a/modules')
    expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(false)
  })

  it('BaseSidebar.vue のコードに SYSTEM_ADMIN の文字列判定が残っていない（コメントを除く）', () => {
    const src = readFileSync(resolve(process.cwd(), 'app/components/BaseSidebar.vue'), 'utf-8')
    const code = src
      .replace(/<!--[\s\S]*?-->/g, '')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/(^|[\s;{}(),])\/\/[^\n]*/g, '$1')
    expect(code).not.toMatch(/(['"`])SYSTEM_ADMIN\1/)
  })
})

describe('BaseSidebar — 戦役Z-A PR5 回帰: 一般利用者の導線は変わらない', () => {
  beforeEach(() => {
    localStorage.clear()
    clearNuxtState('scope-feature-display-policies')
    policyMock.mockReset().mockResolvedValue({ data: { features: [] } })
    apiMock.mockReset()
    asTenantUser()
  })

  const cases = [
    { role: 'ADMIN', admin: true, deputy: true, member: true },
    { role: 'DEPUTY_ADMIN', admin: false, deputy: true, member: true },
    { role: 'MEMBER', admin: false, deputy: false, member: true },
    { role: 'SUPPORTER', admin: false, deputy: false, member: false },
  ]

  for (const scopeType of ['team', 'organization'] as const) {
    it.each(cases)(`${scopeType}: $role の管理=$admin 副管理=$deputy メンバー=$member`, async ({ role, admin, deputy, member }) => {
      respond(role, role === 'ADMIN')
      const wrapper = await mountSidebar(scopeType)
      expect(linkExists(wrapper, scopeType, 'admin-only')).toBe(admin)
      expect(linkExists(wrapper, scopeType, 'deputy-only')).toBe(deputy)
      expect(linkExists(wrapper, scopeType, 'member-item')).toBe(member)
    })
  }

  it('ADMIN でもメンバーレンズ中は管理・副管理項目を隠しメンバー項目だけ出す', async () => {
    respond('ADMIN', true)
    const wrapper = await mountSidebar('organization', true)
    expect(linkExists(wrapper, 'organization', 'admin-only')).toBe(false)
    expect(linkExists(wrapper, 'organization', 'deputy-only')).toBe(false)
    expect(linkExists(wrapper, 'organization', 'member-item')).toBe(true)
  })
})
