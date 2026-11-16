import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import ConfirmableNotificationsPage from './confirmable-notifications.vue'

/**
 * CMP-261115-1635: 組織の確認通知設定ページは、親シェルの org（settings 配下では未ロードで null）に
 * 依存せず、slug から自前で組織を取得して数値IDを子へ渡す。
 * 空 ID のまま子を描画すると /api/v1/organizations//confirmable-* を要求してしまう。
 */
const mocks = vi.hoisted(() => ({ getOrganization: vi.fn(), showError: vi.fn() }))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'acme' } }))
mockNuxtImport('useOrganizationApi', () => () => ({ getOrganization: mocks.getOrganization }))
mockNuxtImport('useOrgShellContext', () => () => ({ org: ref(null) }))
mockNuxtImport('showError', () => mocks.showError)

function childStub(name: string) {
  return defineComponent({
    name,
    props: { scopeType: { type: String, default: '' }, scopeId: { type: String, default: '' } },
    setup(props) {
      return () => h('div', { 'data-stub': name, 'data-scope-id': props.scopeId, 'data-scope-type': props.scopeType })
    },
  })
}

const CHILDREN = [
  'ConfirmableNotificationSender',
  'ConfirmableNotificationHistory',
  'ConfirmableRecipientGroupManager',
  'ConfirmableTemplateManager',
  'ConfirmableNotificationSettings',
]
const stubs = Object.fromEntries([
  ...CHILDREN.map(n => [n, childStub(n)]),
  ['ConfirmableCirculationGuide', true],
  ['PageHeader', true],
  ['PageLoading', defineComponent({ setup: () => () => h('div', { 'data-stub': 'PageLoading' }) })],
])

function mountPage() {
  return mountSuspended(ConfirmableNotificationsPage, { global: { stubs } })
}

describe('organizations/[slug]/settings/confirmable-notifications', () => {
  beforeEach(() => {
    mocks.getOrganization.mockReset()
    mocks.showError.mockReset()
  })

  it('シェルの org が null でも slug から組織を取得し、子へ数値IDを渡す', async () => {
    mocks.getOrganization.mockResolvedValue({ data: { numericId: 42 } })
    const wrapper = await mountPage()
    await flushPromises()
    expect(mocks.getOrganization).toHaveBeenCalledWith('acme')
    for (const name of CHILDREN) {
      const el = wrapper.find(`[data-stub="${name}"]`)
      expect(el.exists(), name).toBe(true)
      expect(el.attributes('data-scope-id'), name).toBe('42')
      expect(el.attributes('data-scope-type'), name).toBe('ORGANIZATION')
    }
  })

  it('組織取得中は子を描画せず、空IDの API を要求させない', async () => {
    mocks.getOrganization.mockReturnValue(new Promise(() => {}))
    const wrapper = await mountPage()
    await flushPromises()
    expect(wrapper.find('[data-stub="PageLoading"]').exists()).toBe(true)
    for (const name of CHILDREN) {
      expect(wrapper.find(`[data-stub="${name}"]`).exists(), name).toBe(false)
    }
  })

  it('組織取得に失敗したらエラーを表示し、子は描画しない', async () => {
    mocks.getOrganization.mockRejectedValue(new Error('boom'))
    const wrapper = await mountPage()
    await flushPromises()
    expect(mocks.showError).toHaveBeenCalledTimes(1)
    for (const name of CHILDREN) {
      expect(wrapper.find(`[data-stub="${name}"]`).exists(), name).toBe(false)
    }
  })

  it('numericId が無い応答も失敗として扱い、子は描画しない', async () => {
    mocks.getOrganization.mockResolvedValue({ data: {} })
    const wrapper = await mountPage()
    await flushPromises()
    expect(mocks.showError).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-stub="ConfirmableNotificationSender"]').exists()).toBe(false)
  })
})
