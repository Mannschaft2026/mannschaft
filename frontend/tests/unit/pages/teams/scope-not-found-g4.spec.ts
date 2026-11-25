import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useScopeNotFound } from '~/composables/useScopeNotFound'
import MatchAnalyticsPage from '~/pages/teams/[slug]/members/[userId]/match-analytics.vue'
import SkillsPage from '~/pages/teams/[slug]/skills.vue'
import LoyaltySettingsPage from '~/pages/teams/[slug]/settings/loyalty.vue'

/**
 * CMP-261125-1108 g4: チームの子ページ（個人分析・スキル・ロイヤルティ設定）。
 *
 *   TSG-1 主 API が 403/404 → notFound を立て、エラートースト・独自の権限なし表示（pi-lock）を出さない
 *   TSG-2 主 API が 500 → 従来どおり（notFound は立てない）
 *   TSG-3 スキル: 非メンバーは API を呼ばず notFound を立てる（FE ガード。権限の文言は出さない）
 */

const getUserTeamStats = vi.fn()
const resolveContext = vi.fn()
const getSkillCategories = vi.fn()
const loadPermissions = vi.fn()
const loyaltyLoad = vi.fn()
const loyaltySettings = vi.fn()
const notifyError = vi.fn()
const handleApiError = vi.fn()
const role = vi.hoisted(() => ({ member: true }))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-a', userId: '5' }, query: {} }))
mockNuxtImport('useMatchOrgContext', () => () => ({ resolveContext }))
mockNuxtImport('useMatchAnalytics', () => () => ({ getUserTeamStats }))
mockNuxtImport('useSkillApi', () => () => ({ getSkillCategories }))
mockNuxtImport('useRoleAccess', () => () => ({
  roleName: ref('MEMBER'),
  isAdmin: computed(() => false),
  isAdminOrDeputy: computed(() => false),
  isMember: computed(() => role.member),
  can: () => false,
  loadPermissions,
}))
mockNuxtImport('useTeamLoyaltyContext', () => () => ({
  slug: computed(() => 'team-a'),
  teamId: ref(1),
  teamName: ref('チームA'),
  access: { isScopeAdmin: computed(() => true) },
  load: loyaltyLoad,
}))
mockNuxtImport('useTeamLoyaltyApi', () => () => ({ settings: loyaltySettings, saveSettings: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  error: notifyError,
  warn: vi.fn(),
  info: vi.fn(),
}))

const skillStubs = { SkillList: true, SkillMatrix: true, SkillCategoryManager: true, SkillForm: true }

async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve()
  await nextTick()
}

beforeEach(() => {
  for (const f of [getUserTeamStats, resolveContext, getSkillCategories, loadPermissions, loyaltyLoad, loyaltySettings, notifyError, handleApiError]) f.mockReset()
  role.member = true
  loadPermissions.mockResolvedValue({ ok: true })
  loyaltyLoad.mockResolvedValue(true)
  resolveContext.mockResolvedValue({ orgId: 1, teamId: 2, organizations: [], orgInvalid: false })
  useScopeNotFound().reset()
})
afterEach(() => {
  useScopeNotFound().reset()
})

describe('teams/[slug]/members/[userId]/match-analytics.vue — 非所属・権限なし（CMP-261125-1108）', () => {
  for (const status of [403, 404] as const) {
    it(`TSG-1: 個人統計が ${status} なら notFound を立て、権限なし表示を出さない`, async () => {
      getUserTeamStats.mockRejectedValue({ statusCode: status })
      const wrapper = await mountSuspended(MatchAnalyticsPage)
      await flush()

      expect(useScopeNotFound().notFound.value).toBe(true)
      expect(wrapper.find('.pi-lock').exists()).toBe(false)
      wrapper.unmount()
    })
  }

  it('TSG-2: 個人統計が 500 なら notFound を立てない', async () => {
    getUserTeamStats.mockRejectedValue({ statusCode: 500 })
    const wrapper = await mountSuspended(MatchAnalyticsPage)
    await flush()

    expect(useScopeNotFound().notFound.value).toBe(false)
    wrapper.unmount()
  })
})

describe('teams/[slug]/skills.vue — 非所属・権限なし（CMP-261125-1108）', () => {
  it('TSG-3: 非メンバーは API を呼ばず notFound を立て、権限なし表示を出さない', async () => {
    role.member = false
    const wrapper = await mountSuspended(SkillsPage, { global: { stubs: skillStubs } })
    await flush()

    expect(getSkillCategories).not.toHaveBeenCalled()
    expect(useScopeNotFound().notFound.value).toBe(true)
    expect(wrapper.find('.pi-lock').exists()).toBe(false)
    expect(notifyError).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('TSG-1: カテゴリ取得が 403 なら notFound を立て、エラートーストを出さない', async () => {
    getSkillCategories.mockRejectedValue({ statusCode: 403 })
    const wrapper = await mountSuspended(SkillsPage, { global: { stubs: skillStubs } })
    await flush()

    expect(useScopeNotFound().notFound.value).toBe(true)
    expect(notifyError).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('TSG-2: カテゴリ取得が 500 なら従来どおりエラートーストを出し、notFound は立てない', async () => {
    getSkillCategories.mockRejectedValue({ statusCode: 500 })
    const wrapper = await mountSuspended(SkillsPage, { global: { stubs: skillStubs } })
    await flush()

    expect(useScopeNotFound().notFound.value).toBe(false)
    expect(notifyError).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})

describe('teams/[slug]/settings/loyalty.vue — 非所属・権限なし（CMP-261125-1108）', () => {
  for (const status of [403, 404] as const) {
    it(`TSG-1: 設定取得が ${status} なら notFound を立て、エラー処理（トースト）を呼ばない`, async () => {
      loyaltySettings.mockRejectedValue({ statusCode: status })
      const wrapper = await mountSuspended(LoyaltySettingsPage)
      await flush()

      expect(useScopeNotFound().notFound.value).toBe(true)
      expect(handleApiError).not.toHaveBeenCalled()
      expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
      wrapper.unmount()
    })
  }

  it('TSG-2: 設定取得が 500 なら従来どおりエラー処理を呼び、notFound は立てない', async () => {
    loyaltySettings.mockRejectedValue({ statusCode: 500 })
    const wrapper = await mountSuspended(LoyaltySettingsPage)
    await flush()

    expect(useScopeNotFound().notFound.value).toBe(false)
    expect(handleApiError).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
