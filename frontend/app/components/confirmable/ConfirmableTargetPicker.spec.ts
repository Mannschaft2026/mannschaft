import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ConfirmableTargetPicker from './ConfirmableTargetPicker.vue'
import ConfirmableRecipientGroupManager from './ConfirmableRecipientGroupManager.vue'

/**
 * CMP-261115-2056: 宛先ピッカーは配下チーム／配下組織を slug で要求する（数値IDだと BE が 404 ORG_001）。
 * 宛先グループの対象は「チーム #数値」ではなくチーム名で表示する。
 */
const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  getTeamsInOrg: vi.fn(),
  handleApiError: vi.fn(),
  listRecipientGroups: vi.fn(),
  noop: vi.fn(),
}))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useOrganizationApi', () => () => ({ getTeamsInOrg: mocks.getTeamsInOrg }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useConfirmableNotificationApi', () => () => ({
  listRecipientGroups: mocks.listRecipientGroups,
  createRecipientGroup: mocks.noop,
  updateRecipientGroup: mocks.noop,
  deleteRecipientGroup: mocks.noop,
}))

const mountOpts = { global: { stubs: { PageLoading: true } } }

describe('ConfirmableTargetPicker', () => {
  beforeEach(() => {
    mocks.api.mockReset()
    mocks.getTeamsInOrg.mockReset()
    mocks.handleApiError.mockReset()
    mocks.listRecipientGroups.mockReset()
    mocks.api.mockResolvedValue({ data: [{ id: 7, slug: 'sub-org', name: '配下組織', visibility: 'PUBLIC', memberCount: 1, archived: false }], meta: { nextCursor: null, size: 50, hasNext: false } })
    mocks.getTeamsInOrg.mockResolvedValue({ data: [{ id: 101, slug: 'team-a', name: 'Aチーム' }] })
  })

  it('配下チーム・配下組織を slug で要求し、候補を表示する', async () => {
    const wrapper = await mountSuspended(ConfirmableTargetPicker, {
      ...mountOpts,
      props: { scopeType: 'ORGANIZATION', scopeId: '42', scopeSlug: 'acme', modelValue: [] },
    })
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledTimes(1)
    expect(String(mocks.api.mock.calls[0][0])).toMatch(/^\/api\/v1\/organizations\/acme\/children\?/)
    expect(mocks.getTeamsInOrg).toHaveBeenCalledWith('acme')
    expect(mocks.handleApiError).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Aチーム')
    expect(wrapper.text()).toContain('配下組織')
  })
})

describe('ConfirmableRecipientGroupManager', () => {
  beforeEach(() => {
    mocks.api.mockReset()
    mocks.getTeamsInOrg.mockReset()
    mocks.listRecipientGroups.mockReset()
    mocks.api.mockResolvedValue({ data: [], meta: { nextCursor: null, size: 50, hasNext: false } })
    mocks.getTeamsInOrg.mockResolvedValue({ data: [{ id: 101, slug: 'team-a', name: 'Aチーム' }] })
    mocks.listRecipientGroups.mockResolvedValue({ data: [{ id: 'g1', name: 'G', targets: [{ type: 'TEAM', id: 101 }] }] })
  })

  it('宛先グループの対象をチーム名で表示する', async () => {
    const wrapper = await mountSuspended(ConfirmableRecipientGroupManager, {
      ...mountOpts,
      props: { scopeType: 'ORGANIZATION', scopeId: '42', scopeSlug: 'acme' },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Aチーム')
    expect(wrapper.text()).not.toContain('#101')
  })
})
