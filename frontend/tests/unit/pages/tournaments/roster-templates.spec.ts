import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import RosterPage from '~/pages/teams/[slug]/tournaments/[tId]/roster.vue'

/**
 * CMP-261124-1031 の根治テスト。
 *
 * roster.vue の loadTemplates は orgId を '0'、teamId を slug のまま
 * getEntryTemplates に渡して 404 となり、catch で握りつぶしていた。
 * useMatchOrgContext.resolveContext で解決した数値 orgId / teamId を渡し、
 * 失敗時は handleApiError ＋画面にエラーを表示する。
 *
 * 検証観点:
 *   RT-001 解決した orgId / teamId（'0' でも slug でもない）で getEntryTemplates が呼ばれる
 *   RT-002 取得失敗時に handleApiError が呼ばれ、エラーが画面に表示される
 *   RT-003 org 解決不能時はテンプレ取得を呼ばず、理由が画面に表示される
 *   RT-004 ?org= 指定が親組織に無い（orgInvalid）ときも取得せず理由を表示する
 */

const getEntryTemplates = vi.fn()
const resolveContext = vi.fn()
const handleApiError = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-slug', tId: '7' }, query: {} }))
mockNuxtImport('useTournamentApi', () => () => ({ getEntryTemplates }))
mockNuxtImport('useMatchOrgContext', () => () => ({ resolveContext }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }))
mockNuxtImport('useRoleAccess', () => () => ({ isAdminOrDeputy: ref(true), loadPermissions: vi.fn(async () => {}) }))

async function settle() {
  for (let i = 0; i < 5; i++) await new Promise((r) => setTimeout(r, 0))
}

beforeAll(async () => {
  resolveContext.mockResolvedValue({ orgId: 11, orgInvalid: false, teamId: 22, organizations: [] })
  getEntryTemplates.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(RosterPage)
  warmup.unmount()
})

describe('pages/teams/[slug]/tournaments/[tId]/roster.vue — エントリーテンプレ取得', () => {
  beforeEach(() => {
    getEntryTemplates.mockReset()
    resolveContext.mockReset()
    handleApiError.mockReset()
  })

  it('RT-001: 解決した orgId / teamId で getEntryTemplates を呼ぶ', async () => {
    resolveContext.mockResolvedValue({ orgId: 11, orgInvalid: false, teamId: 22, organizations: [] })
    getEntryTemplates.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(RosterPage)
    await settle()

    expect(resolveContext).toHaveBeenCalledWith('team-slug', expect.anything())
    expect(getEntryTemplates).toHaveBeenCalledWith('11', '22')
    expect(wrapper.find('[data-testid="roster-templates-error"]').exists()).toBe(false)
  })

  it('RT-002: 取得失敗時に handleApiError が呼ばれエラーが表示される', async () => {
    resolveContext.mockResolvedValue({ orgId: 11, orgInvalid: false, teamId: 22, organizations: [] })
    const err = new Error('boom')
    getEntryTemplates.mockRejectedValue(err)
    const wrapper = await mountSuspended(RosterPage)
    await settle()

    expect(handleApiError).toHaveBeenCalledWith(err, expect.any(String))
    expect(wrapper.find('[data-testid="roster-templates-error"]').exists()).toBe(true)
  })

  it('RT-003: org 解決不能時は取得せず理由を表示する', async () => {
    resolveContext.mockResolvedValue(null)
    const wrapper = await mountSuspended(RosterPage)
    await settle()

    expect(getEntryTemplates).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="roster-templates-unavailable"]').exists()).toBe(true)
  })

  it('RT-004: orgInvalid のときも取得せず理由を表示する', async () => {
    resolveContext.mockResolvedValue({ orgId: null, orgInvalid: true, teamId: 22, organizations: [] })
    const wrapper = await mountSuspended(RosterPage)
    await settle()

    expect(getEntryTemplates).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="roster-templates-unavailable"]').exists()).toBe(true)
  })
})
