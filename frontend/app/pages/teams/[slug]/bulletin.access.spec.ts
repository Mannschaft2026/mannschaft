// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import BulletinPage from './bulletin.vue'

// CMP-261124-0159 PR-F1 / AC-0159-87: 権限取得の失敗を無表示で倒さない（空の一覧に化けさせない）。
const mocks = vi.hoisted(() => ({
  roleLoadFailed: { value: false },
  loadPermissions: vi.fn(),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useRoute', () => () => ({ path: '/teams/a/bulletin', params: { slug: 'a' }, query: {} }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdminOrDeputy: ref(false),
  isMember: ref(true),
  roleLoadFailed: mocks.roleLoadFailed,
  loadPermissions: mocks.loadPermissions,
}))
mockNuxtImport('useBulletinApi', () => () => ({ getScopedThread: vi.fn() }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))

const stub = (testid: string) => defineComponent({ setup: () => () => h('div', { 'data-testid': testid }) })

function mountPage() {
  return mount(BulletinPage, {
    global: {
      stubs: {
        PageHeader: true,
        PageLoading: true,
        BulletinThreadList: stub('thread-list'),
        BulletinArchiveView: stub('archive'),
        BulletinThreadForm: true,
        BulletinThreadDetail: true,
        DashboardErrorState: stub('role-error'),
      },
    },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('bulletin.vue の権限取得失敗（AC-0159-87）', () => {
  it('権限取得に失敗したら一覧に化けず、エラー表示を出す', async () => {
    mocks.roleLoadFailed.value = true
    mocks.loadPermissions.mockResolvedValue({ ok: false, error: new Error('x') })
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('[data-testid="role-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="thread-list"]').exists()).toBe(false)
  })

  it('権限取得に成功したら従来どおり一覧を出す', async () => {
    mocks.roleLoadFailed.value = false
    mocks.loadPermissions.mockResolvedValue({ ok: true })
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('[data-testid="thread-list"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="role-error"]').exists()).toBe(false)
  })
})
