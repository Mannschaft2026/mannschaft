import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref, computed } from 'vue'
import ParkingScopePage from './ParkingScopePage.vue'

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))

const access = {
  roleName: 'SYSTEM_ADMIN',
  isScopeAdmin: false,
  isAdmin: true,
  isMember: true,
  permissions: [] as string[],
}

mockNuxtImport('useI18n', () => () => ({ t: (k: string) => k, te: () => true }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))
mockNuxtImport('useConfirm', () => () => ({ require: vi.fn() }))
mockNuxtImport('useAuthStore', () => () => ({ user: { id: 1 } }))
mockNuxtImport('useDatetime', () => () => ({ formatDateTime: (v: string) => v }))
mockNuxtImport('useRoleAccess', () => () => ({
  loadPermissions: vi.fn(),
  roleName: ref(access.roleName),
  isScopeAdmin: computed(() => access.isScopeAdmin),
  isAdmin: computed(() => access.isAdmin),
  isMember: computed(() => access.isMember),
  can: (p: string) => access.permissions.includes(p),
}))

const stubs = { Paginator: true, ParkingApplicationForm: true, ParkingSpaceGrid: true }

async function mountPage() {
  return mountSuspended(ParkingScopePage, {
    props: { scopeType: 'team', scopeId: '5' },
    global: { stubs },
  })
}

describe('ParkingScopePage の権限出し分け', () => {
  beforeEach(() => {
    mockApi.mockReset()
    mockApi.mockResolvedValue({ data: [], meta: { total: 0 } })
  })

  it('所属のない SYSTEM_ADMIN には申請ボタンも管理ボタンも出さない', async () => {
    Object.assign(access, { roleName: 'SYSTEM_ADMIN', isScopeAdmin: false, isAdmin: true, permissions: [] })
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="parking-apply-open"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('parking.applications.titleAll')
  })

  it('スコープ ADMIN は申請ボタンが出て、申請一覧は全件表示の見出しになる', async () => {
    Object.assign(access, { roleName: 'ADMIN', isScopeAdmin: true, isAdmin: true, permissions: [] })
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="parking-apply-open"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('parking.applications.titleAll')
  })

  it('MANAGE_PARKING 保有の DEPUTY_ADMIN は全件表示の見出し、権限なしの DEPUTY_ADMIN は本人分の見出し', async () => {
    Object.assign(access, { roleName: 'DEPUTY_ADMIN', isScopeAdmin: false, isAdmin: false, permissions: ['MANAGE_PARKING'] })
    expect((await mountPage()).text()).toContain('parking.applications.titleAll')
    Object.assign(access, { permissions: [] })
    expect((await mountPage()).text()).not.toContain('parking.applications.titleAll')
  })

  it('死んだ「区画を追加」ボタンは存在しない', async () => {
    Object.assign(access, { roleName: 'ADMIN', isScopeAdmin: true, isAdmin: true, permissions: [] })
    const wrapper = await mountPage()
    expect(wrapper.text()).not.toContain('parking.spaces.add')
  })

  it('申請一覧は区画一覧より DOM 上で先に来る（モバイルで申請結果が見えるように）', async () => {
    Object.assign(access, { roleName: 'MEMBER', isScopeAdmin: false, isAdmin: false, permissions: [] })
    const wrapper = await mountPage()
    const html = wrapper.html()
    const apps = html.indexOf('parking.applications.title')
    const spaces = html.indexOf('parking.spaces.title')
    expect(apps).toBeGreaterThan(-1)
    expect(spaces).toBeGreaterThan(-1)
    expect(apps).toBeLessThan(spaces)
  })
})
