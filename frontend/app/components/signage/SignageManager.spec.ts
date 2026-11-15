// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import SignageManager from './SignageManager.vue'

const api = vi.hoisted(() => ({
  getScreens: vi.fn(), createScreen: vi.fn(), updateScreen: vi.fn(),
  getSlots: vi.fn(), getTokens: vi.fn(), addSlot: vi.fn(),
}))
mockNuxtImport('useSignageApi', () => () => api)
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useDatetime', () => () => ({ formatDateTime: (value: string) => value }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const screen = {
  id: 1, scopeType: 'TEAM', scopeId: '42', name: '既存画面', description: null,
  layout: 'LANDSCAPE', defaultSlideDuration: 10, transitionEffect: 'FADE', isActive: true,
  createdAt: '2026-01-01T00:00:00',
}
const Button = defineComponent({
  props: ['label', 'type'],
  setup: (props) => () => h('button', { type: props.type ?? 'button' }, props.label),
})
const Dialog = defineComponent({
  props: ['visible'],
  setup: (props, { slots }) => () => props.visible ? h('section', slots.default?.()) : null,
})
const Input = defineComponent({
  props: ['modelValue'], emits: ['update:modelValue'],
  setup: (props, { emit }) => () => h('input', {
    value: props.modelValue,
    onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value),
  }),
})
const Select = defineComponent({
  props: ['modelValue', 'options'], emits: ['update:modelValue'],
  setup: (props, { emit }) => () => h('select', {
    value: props.modelValue,
    onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value),
  }, props.options.map((option: { value: string; label: string }) => h('option', { value: option.value }, option.label))),
})
function render() {
  return mount(SignageManager, {
    props: { scopeType: 'TEAM', scopeId: '42' },
    global: { stubs: {
      Button, Dialog, Select, InputText: Input, Textarea: Input, InputNumber: true,
      PageHeader: true, LoadingBounce: true, DashboardEmptyState: true, SectionCard: { template: '<div><slot /></div>' },
      Tag: true, DataTable: true, Column: true,
    } },
  })
}
beforeEach(() => {
  vi.clearAllMocks()
  api.getScreens.mockResolvedValue({ data: [screen] })
  api.getSlots.mockResolvedValue({ data: [] })
  api.getTokens.mockResolvedValue({ data: [] })
  api.createScreen.mockResolvedValue({ data: screen })
  api.updateScreen.mockResolvedValue({ data: screen })
  api.addSlot.mockResolvedValue({ data: { id: 1 } })
})

describe('サイネージ管理のBE enum契約', () => {
  it.each(['LANDSCAPE', 'PORTRAIT', 'STANDARD'])('レイアウト%sで作成でき、未対応transitionを選べない', async (layout) => {
    const wrapper = render()
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '画面を追加')!.trigger('click')
    await wrapper.find('input').setValue('新しい画面')
    const selects = wrapper.findAll('select')
    expect(selects[1]!.findAll('option').map((option) => option.element.value)).toEqual(['NONE', 'FADE', 'SLIDE'])
    expect(selects[0]!.element.value).toBe('LANDSCAPE')
    await selects[0]!.setValue(layout)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(api.createScreen).toHaveBeenCalledWith(expect.objectContaining({ layout, transitionEffect: 'FADE', scopeId: '42' }))
    wrapper.unmount()
  })

  it('既存LANDSCAPE画面を編集してそのまま保存できる', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '編集')!.trigger('click')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(api.updateScreen).toHaveBeenCalledWith(1, expect.objectContaining({ layout: 'LANDSCAPE' }))
    wrapper.unmount()
  })

  it.each(['ANNOUNCEMENT', 'SCHEDULE', 'ACTIVITY_HIGHLIGHT', 'RANKING', 'FREE_TEXT', 'FREE_IMAGE', 'WEATHER', 'CLOCK'])('正規slotType=%sを追加できる', async (slotType) => {
    const wrapper = render()
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '管理')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === 'スロット追加')!.trigger('click')
    const select = wrapper.find('select')
    expect(select.findAll('option').map((option) => option.element.value)).toHaveLength(8)
    await select.setValue(slotType)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(api.addSlot).toHaveBeenCalledWith(1, expect.objectContaining({ slotType }))
    wrapper.unmount()
  })
})
