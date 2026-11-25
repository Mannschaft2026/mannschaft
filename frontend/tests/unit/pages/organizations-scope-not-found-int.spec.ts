import { describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import EquipmentPage from '~/pages/organizations/[slug]/equipment.vue'
import SupportersPage from '~/pages/organizations/[slug]/supporters.vue'
import EquipmentList from '~/components/equipment/EquipmentList.vue'
import SupporterManagementPanel from '~/components/SupporterManagementPanel.vue'

/**
 * CMP-261125-1108 統合: 組織の備品・サポーター管理ページも、
 * 403/404 を共通の見つかりません表示にするため scopeNotFoundOnDenied を true で渡す。
 */

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-a' }, query: {}, path: '/organizations/org-a/x' }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(true),
  isAdminOrDeputy: ref(true),
  loadPermissions: vi.fn(),
}))
mockNuxtImport('useOrgShellContext', () => () => ({
  isAdmin: ref(true),
  roleName: ref('ADMIN'),
  org: ref({ visibility: { supporterEnabled: true } }),
}))

describe('組織ページの scopeNotFoundOnDenied', () => {
  it('備品ページは EquipmentList に true を渡す', async () => {
    const wrapper = await mountSuspended(EquipmentPage, {
      global: { stubs: { EquipmentList: true } },
    })
    await nextTick()
    await nextTick()
    const list = wrapper.findComponent(EquipmentList)
    expect(list.exists()).toBe(true)
    expect(list.props('scopeNotFoundOnDenied')).toBe(true)
  })

  it('サポーター管理ページは SupporterManagementPanel に true を渡す', async () => {
    const wrapper = await mountSuspended(SupportersPage, {
      global: { stubs: { SupporterManagementPanel: true } },
    })
    const panel = wrapper.findComponent(SupporterManagementPanel)
    expect(panel.exists()).toBe(true)
    expect(panel.props('scopeNotFoundOnDenied')).toBe(true)
  })
})
