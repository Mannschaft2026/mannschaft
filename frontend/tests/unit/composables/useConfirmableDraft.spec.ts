import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h, nextTick, ref } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { useConfirmableDraft } from '~/composables/useConfirmableDraft'

/**
 * CMP-261126-1558 FE-AC11: ルート離脱の保護。
 * onBeforeRouteLeave は <router-view> の子でないと登録されないため、本物の vue-router の下に置いたハーネスで
 * router.push() によるガードの実発火を確かめる（mountSuspended は router-view の外に置くので使えない）。
 */

const mocks = vi.hoisted(() => ({
  listDrafts: vi.fn(),
  createDraft: vi.fn(),
  getDraft: vi.fn(),
  updateDraft: vi.fn(),
  deleteDraft: vi.fn(),
  handleApiError: vi.fn(),
  confirmRequire: vi.fn(),
}))

mockNuxtImport('useConfirmableNotificationApi', () => () => ({
  listDrafts: mocks.listDrafts,
  createDraft: mocks.createDraft,
  getDraft: mocks.getDraft,
  updateDraft: mocks.updateDraft,
  deleteDraft: mocks.deleteDraft,
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useConfirm', () => () => ({ require: mocks.confirmRequire, close: vi.fn() }))
mockNuxtImport('useToast', () => () => ({ add: vi.fn() }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

type ConfirmOptions = { accept?: () => unknown, reject?: () => unknown, onHide?: () => unknown, message?: string }
const lastConfirm = () => mocks.confirmRequire.mock.calls.at(-1)?.[0] as ConfirmOptions | undefined

const title = ref('')
const wrappers: VueWrapper[] = []

async function mountHarness(): Promise<Router> {
  const draftPage = defineComponent({
    setup() {
      useConfirmableDraft({
        scopeType: () => 'TEAM',
        scopeId: () => '5',
        snapshot: () => ({ title: title.value }),
        apply: (detail) => { title.value = detail?.title ?? '' },
      })
      return () => h('div', 'draft')
    },
  })
  const other = defineComponent({ setup: () => () => h('div', 'other') })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: draftPage },
      { path: '/other', component: other },
    ],
  })
  router.push('/')
  await router.isReady()
  wrappers.push(mount(defineComponent({ render: () => h(RouterView) }), { global: { plugins: [router] } }))
  await flushPromises()
  return router
}

describe('useConfirmableDraft ルート離脱（FE-AC11）', () => {
  beforeEach(() => {
    title.value = ''
    for (const fn of Object.values(mocks)) fn.mockReset()
    mocks.listDrafts.mockResolvedValue({ data: [] })
    mocks.createDraft.mockResolvedValue({ data: { id: 'n-1', version: 0 } })
  })
  afterEach(() => {
    while (wrappers.length > 0) wrappers.pop()?.unmount()
    vi.useRealTimers()
  })

  it('入力が無ければ確認なしで離れる', async () => {
    const router = await mountHarness()
    await router.push('/other')
    expect(mocks.confirmRequire).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/other')
  })

  it('保留中の自動保存を先に流し、保存できれば確認なしで離れる', async () => {
    const router = await mountHarness()
    title.value = '保留中の件名'
    await nextTick()
    await router.push('/other')
    expect(mocks.createDraft).toHaveBeenCalledTimes(1)
    expect(mocks.confirmRequire).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/other')
  })

  describe('保存に失敗して入力が残るとき', () => {
    beforeEach(() => {
      mocks.createDraft.mockRejectedValue(Object.assign(new Error('x'), { status: 500, statusCode: 500 }))
    })

    async function leaveWithUnsaved() {
      const router = await mountHarness()
      title.value = '保存できていない件名'
      await nextTick()
      const navigation = router.push('/other')
      await vi.waitFor(() => expect(mocks.confirmRequire).toHaveBeenCalledTimes(1))
      return { router, navigation }
    }

    it('確認を出し、拒否なら離れない', async () => {
      const { router, navigation } = await leaveWithUnsaved()
      expect(lastConfirm()?.message).toBe('confirmable.draft.unsaved_leave_confirm')
      lastConfirm()?.reject?.()
      await navigation
      expect(router.currentRoute.value.path).toBe('/')
      expect(title.value).toBe('保存できていない件名')
    })

    it('× や Esc で閉じても（onHide）離れない', async () => {
      const { router, navigation } = await leaveWithUnsaved()
      lastConfirm()?.onHide?.()
      await navigation
      expect(router.currentRoute.value.path).toBe('/')
    })

    it('承認したら離れる（承認後の onHide で取り消されない）', async () => {
      const { router, navigation } = await leaveWithUnsaved()
      lastConfirm()?.accept?.()
      lastConfirm()?.onHide?.()
      await navigation
      expect(router.currentRoute.value.path).toBe('/other')
    })
  })
})
