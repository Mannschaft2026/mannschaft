import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent } from 'vue'
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
  getTeam: vi.fn(),
  handleApiError: vi.fn(),
  listRecipientGroups: vi.fn(),
  noop: vi.fn(),
}))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useOrganizationApi', () => () => ({ getTeamsInOrg: mocks.getTeamsInOrg }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.getTeam }))
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
    mocks.getTeam.mockReset()
    mocks.getTeam.mockResolvedValue({ data: { id: 'team-a', slug: 'team-a', numericId: 101 } })
    mocks.handleApiError.mockReset()
    mocks.listRecipientGroups.mockReset()
    mocks.api.mockResolvedValue({ data: [{ id: 7, slug: 'sub-org', name: '配下組織', visibility: 'PUBLIC', memberCount: 1, archived: false }], meta: { nextCursor: null, size: 50, hasNext: false } })
    mocks.getTeamsInOrg.mockResolvedValue({ data: [{ id: 'team-a', slug: 'team-a', name: 'Aチーム' }] })
  })

  it('配下チーム・配下組織を slug で要求し、候補を表示する', async () => {
    const wrapper = await mountSuspended(ConfirmableTargetPicker, {
      ...mountOpts,
      props: { scopeType: 'ORGANIZATION', scopeId: '42', scopeSlug: 'acme', modelValue: [] },
    })
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledTimes(1)
    const firstApiCall = mocks.api.mock.calls[0]
    expect(firstApiCall).toBeDefined()
    if (!firstApiCall) throw new Error('mocks.api が呼ばれていない')
    expect(String(firstApiCall[0])).toMatch(/^\/api\/v1\/organizations\/acme\/children\?/)
    expect(mocks.getTeamsInOrg).toHaveBeenCalledWith('acme')
    expect(mocks.handleApiError).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Aチーム')
    expect(wrapper.text()).toContain('配下組織')
    expect(mocks.getTeam).toHaveBeenCalledWith('team-a')
  })

  it('チーム対象は数値IDで emit され、スラッグ文字列は送らない', async () => {
    const wrapper = await mountSuspended(ConfirmableTargetPicker, {
      ...mountOpts,
      props: { scopeType: 'ORGANIZATION', scopeId: '42', scopeSlug: 'acme', modelValue: [] },
    })
    await flushPromises()
    const teamBox = wrapper.findAll('input[type="checkbox"]').find(box => box.attributes('id')?.endsWith('TEAM:101'))
    expect(teamBox).toBeDefined()
    await teamBox!.setValue(true)
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toEqual([{ type: 'TEAM', id: 101 }])
  })

  it('チーム詳細の取得に失敗したら展開済みにせず、再取得できる', async () => {
    mocks.getTeam.mockRejectedValueOnce(new Error('network'))
    const wrapper = await mountSuspended(ConfirmableTargetPicker, {
      ...mountOpts,
      props: { scopeType: 'ORGANIZATION', scopeId: '42', scopeSlug: 'acme', modelValue: [] },
    })
    await flushPromises()
    expect(mocks.handleApiError).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('Aチーム')
    const reload = wrapper.findAll('button').find(btn => btn.find('.pi-angle-down').exists())
    expect(reload).toBeDefined()
    await reload!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Aチーム')
  })

  it('2つ描画してもチェックボックスの id は重複しない', async () => {
    // 実画面と同じく、同一アプリ内に2つ描画する（別アプリ同士では useId が衝突しうるため）
    const Twin = defineComponent({
      components: { ConfirmableTargetPicker },
      template: '<div><ConfirmableTargetPicker scope-type="ORGANIZATION" scope-id="42" scope-slug="acme" :model-value="[]" /><ConfirmableTargetPicker scope-type="ORGANIZATION" scope-id="42" scope-slug="acme" :model-value="[]" /></div>',
    })
    const wrapper = await mountSuspended(Twin, mountOpts)
    await flushPromises()
    const ids = wrapper.findAll('input[type="checkbox"]').map(box => box.attributes('id'))
    expect(ids.length).toBeGreaterThan(2)
    expect(new Set(ids).size).toBe(ids.length)
  })
})

describe('ConfirmableRecipientGroupManager', () => {
  beforeEach(() => {
    mocks.api.mockReset()
    mocks.getTeamsInOrg.mockReset()
    mocks.getTeam.mockReset()
    mocks.getTeam.mockResolvedValue({ data: { id: 'team-a', slug: 'team-a', numericId: 101 } })
    mocks.listRecipientGroups.mockReset()
    mocks.api.mockResolvedValue({ data: [], meta: { nextCursor: null, size: 50, hasNext: false } })
    mocks.getTeamsInOrg.mockResolvedValue({ data: [{ id: 'team-a', slug: 'team-a', name: 'Aチーム' }] })
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
