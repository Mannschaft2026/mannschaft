// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter, RouterLink } from 'vue-router'
import PrimeVue from 'primevue/config'
import en from '~/locales/en/common.json'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * CMP-261005-2103 I1/I3: スコープの管理メニュー（設定ハブ）から
 * 無断欠席管理（/scopes/{TYPE}/{数値ID}/no-shows）と
 * ペナルティ設定（/scopes/{TYPE}/{数値ID}/penalty-settings）へ到達できること。
 *
 * /scopes/ 配下の scopeId には ScopeSlugIdConverter がかかり、slug は TEAM として解決されるため、
 * 導線は slug ではなく数値 ID（TeamResponse / OrganizationResponse の numericId）を渡す。
 * 数値 ID の取得経路は問わない（useApi 層で詳細 API の応答だけを固定する）。
 */
const state = vi.hoisted(() => ({
  role: 'ADMIN',
  permissions: vi.fn(),
  probe: vi.fn(),
  api: vi.fn(),
}))

vi.mock('~/composables/useApi', () => ({ useApi: () => state.api }))
mockNuxtImport('useRoleAccess', () => () => ({
  roleName: ref(state.role),
  isScopeAdmin: ref(state.role === 'ADMIN'),
  loadPermissions: state.permissions,
}))
mockNuxtImport('useFeatureFlagStore', () => () => ({
  publicLoaded: true,
  isEnabled: () => false,
  loadPublicFlags: vi.fn(),
}))
mockNuxtImport('useScopeTabApi', () => () => ({ getAdminActionRequired: state.probe }))

const AdminSettingsHub = (await import('./AdminSettingsHub.vue')).default

const TEAM_NUMERIC_ID = 42
const ORG_NUMERIC_ID = 77

async function mountHub(scopeType: 'team' | 'organization') {
  const router = createRouter({ history: createMemoryHistory(), routes: [] })
  const wrapper = mount(AdminSettingsHub, {
    props: { scopeType, slug: 'sample' },
    global: {
      plugins: [
        router,
        PrimeVue,
        createI18n({
          legacy: false,
          locale: 'en',
          missingWarn: false,
          fallbackWarn: false,
          messages: { ja: {}, en, zh: {}, ko: {}, es: {}, de: {} },
        }),
      ],
      stubs: { NuxtLink: RouterLink, PageHeader: true },
    },
  })
  await flushPromises()
  return wrapper
}

const cases = [
  ['team', 'TEAM', TEAM_NUMERIC_ID],
  ['organization', 'ORGANIZATION', ORG_NUMERIC_ID],
] as const

describe('無断欠席・ペナルティ管理への導線（CMP-261005-2103）', () => {
  beforeEach(() => {
    state.role = 'ADMIN'
    state.permissions.mockReset().mockResolvedValue({ ok: true })
    state.probe.mockReset().mockRejectedValue({ statusCode: 403 })
    state.api.mockReset().mockImplementation((url: string) => {
      if (url === '/api/v1/teams/sample') {
        return Promise.resolve({ data: { id: 'sample', slug: 'sample', numericId: TEAM_NUMERIC_ID } })
      }
      if (url === '/api/v1/organizations/sample') {
        return Promise.resolve({ data: { id: 'sample', slug: 'sample', numericId: ORG_NUMERIC_ID } })
      }
      return Promise.resolve({ data: [] })
    })
  })

  describe.each(['ADMIN', 'DEPUTY_ADMIN'])('I1: %s', (role) => {
    it.each(cases)(
      'I1: %s の管理メニューに無断欠席管理とペナルティ設定へのリンクが数値IDで出る',
      async (scopeType, apiScopeType, numericId) => {
        state.role = role
        const wrapper = await mountHub(scopeType)
        // 前提: 管理メニュー自体は描画されている（既存の公開設定リンク）
        const scopePath = scopeType === 'team' ? 'teams' : 'organizations'
        expect(wrapper.find(`a[href="/${scopePath}/sample/settings/public-settings"]`).exists()).toBe(true)
        expect(
          wrapper.find(`a[href="/scopes/${apiScopeType}/${numericId}/no-shows"]`).exists(),
        ).toBe(true)
        expect(
          wrapper.find(`a[href="/scopes/${apiScopeType}/${numericId}/penalty-settings"]`).exists(),
        ).toBe(true)
        // slug を /scopes/ 配下へ渡すと ScopeSlugIdConverter が TEAM として解決し別スコープを誤照会しうる
        expect(wrapper.find('a[href^="/scopes/"][href*="/sample/"]').exists()).toBe(false)
      },
    )
  })

  describe.each(['MEMBER', 'SUPPORTER', 'GUEST'])('I3: %s', (role) => {
    it.each(cases)('I3: %s の管理メニューに無断欠席・ペナルティ管理の導線を出さない', async (scopeType) => {
      state.role = role
      const wrapper = await mountHub(scopeType)
      expect(wrapper.find('a[href$="/no-shows"]').exists()).toBe(false)
      expect(wrapper.find('a[href$="/penalty-settings"]').exists()).toBe(false)
    })
  })
})
