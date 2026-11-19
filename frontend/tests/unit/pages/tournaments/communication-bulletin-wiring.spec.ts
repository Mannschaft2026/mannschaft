import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'

// CMP-261117-0859: 大会の連絡スペース画面で、掲示板のスレッド選択→詳細・新規作成→フォームの導線が配線されていること
const mocks = vi.hoisted(() => ({
  fetchContactSpaces: vi.fn(),
  fetchDivisionContactSpaces: vi.fn(),
  getDivisions: vi.fn(),
  route: { params: { slug: 'org-slug', tId: '5' }, query: {} },
}))
mockNuxtImport('useRoute', () => () => mocks.route)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdminOrDeputy: ref(true), loadPermissions: vi.fn().mockResolvedValue({ ok: true }),
}))
mockNuxtImport('useTournamentContact', () => () => ({
  fetchContactSpaces: mocks.fetchContactSpaces,
  fetchDivisionContactSpaces: mocks.fetchDivisionContactSpaces,
  toggleVisibility: vi.fn(),
  toggleDivisionVisibility: vi.fn(),
}))
mockNuxtImport('useTournamentApi', () => () => ({ getDivisions: mocks.getDivisions }))
mockNuxtImport('useChatApi', () => () => ({ getChannel: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ showSuccess: vi.fn(), showError: vi.fn() }))

const stubs = {
  PageHeader: true, PageLoading: true, LoadingBounce: true, DashboardEmptyState: true,
  ChatMessagePanel: true, ToggleSwitch: true,
  Button: { props: ['label'], emits: ['click'], template: '<button v-bind="$attrs" @click="$emit(\'click\')">{{ label }}</button>' },
  BulletinThreadList: { name: 'BulletinThreadList', props: ['scopeType', 'scopeId', 'canManage', 'canCreate'], emits: ['select', 'create'], template: '<div />' },
  BulletinThreadDetail: { name: 'BulletinThreadDetail', props: ['threadId', 'canManage'], emits: ['back'], template: '<div />' },
  BulletinThreadForm: { name: 'BulletinThreadForm', props: ['visible', 'scopeType', 'scopeId'], emits: ['saved', 'update:visible'], template: '<div />' },
}

const space = (scopeType: string, scopeId: number) => ({ id: scopeId, spaceKind: 'BULLETIN', scopeType, scopeId, isPublic: false })

async function open() {
  const page = await import('~/pages/organizations/[slug]/tournaments/[tId]/communication.vue')
  const wrapper = await mountSuspended(page.default, { global: { stubs } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.fetchContactSpaces.mockResolvedValue([space('TOURNAMENT', 5)])
  mocks.fetchDivisionContactSpaces.mockResolvedValue([space('TOURNAMENT_DIVISION', 9)])
  mocks.getDivisions.mockResolvedValue({ data: [{ id: 9, name: 'A部' }] })
})

describe('大会連絡スペースの掲示板導線', () => {
  it('作成ボタンを有効にして一覧を描画する', async () => {
    const wrapper = await open()
    const list = wrapper.findComponent({ name: 'BulletinThreadList' })
    expect(list.exists()).toBe(true)
    expect(list.props('scopeType')).toBe('TOURNAMENT')
    expect(list.props('canCreate')).toBe(true)
  })

  it('スレッド選択で同ページに詳細を開き、戻るで一覧へ戻る', async () => {
    const wrapper = await open()
    wrapper.findComponent({ name: 'BulletinThreadList' }).vm.$emit('select', { id: 77 })
    await flushPromises()
    const detail = wrapper.findComponent({ name: 'BulletinThreadDetail' })
    expect(detail.props('threadId')).toBe(77)
    expect(wrapper.findComponent({ name: 'BulletinThreadList' }).exists()).toBe(false)
    detail.vm.$emit('back')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'BulletinThreadDetail' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'BulletinThreadList' }).exists()).toBe(true)
  })

  it('作成要求で大会スコープのフォームが開く', async () => {
    const wrapper = await open()
    expect(wrapper.findComponent({ name: 'BulletinThreadForm' }).props('visible')).toBe(false)
    wrapper.findComponent({ name: 'BulletinThreadList' }).vm.$emit('create')
    await flushPromises()
    const form = wrapper.findComponent({ name: 'BulletinThreadForm' })
    expect(form.props('visible')).toBe(true)
    expect(form.props('scopeType')).toBe('TOURNAMENT')
    expect(form.props('scopeId')).toBe(5)
  })

  it('スコープ切替で選択スレッドと作成フォームをリセットする', async () => {
    const wrapper = await open()
    wrapper.findComponent({ name: 'BulletinThreadList' }).vm.$emit('create')
    await flushPromises()
    const divisionButton = wrapper.findAll('button').find(b => b.text() === 'A部')!
    await divisionButton.trigger('click')
    await flushPromises()
    const form = wrapper.findComponent({ name: 'BulletinThreadForm' })
    expect(form.props('visible')).toBe(false)
    expect(form.props('scopeType')).toBe('TOURNAMENT_DIVISION')
    wrapper.findComponent({ name: 'BulletinThreadList' }).vm.$emit('select', { id: 1 })
    await flushPromises()
    const tournamentButton = wrapper.findAll('button')[0]!
    await tournamentButton.trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'BulletinThreadDetail' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'BulletinThreadList' }).props('scopeType')).toBe('TOURNAMENT')
  })
})
