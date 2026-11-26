import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import Page from './shift.vue'

/**
 * チーム設定 > シフト設定（CMP-261125-2114）。
 * 副管理者への閲覧許可の切替2つを GET で初期表示し、PATCH で保存する。
 * 変更できるのは管理操作の権限（ADMIN / MANAGE_SHIFTS 付き DEPUTY_ADMIN）を持つ人だけ。
 */
const mocks = vi.hoisted(() => ({
  getShiftSettings: vi.fn(),
  updateShiftSettings: vi.fn(),
  loadPermissions: vi.fn(),
  notifyError: vi.fn(),
  notifySuccess: vi.fn(),
}))
const perm = {
  canManageShift: ref(true),
  isScopeAdmin: ref(true),
  isDeputyWithoutManage: ref(false),
}

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-a' }, query: {} }))
mockNuxtImport('useNotification', () => () => ({ error: mocks.notifyError, success: mocks.notifySuccess }))
mockNuxtImport('useTeamShiftSettingsApi', () => () => ({
  getShiftSettings: mocks.getShiftSettings,
  updateShiftSettings: mocks.updateShiftSettings,
}))
mockNuxtImport('useShiftPermission', () => () => ({ ...perm, loadPermissions: mocks.loadPermissions }))

const ToggleSwitch = defineComponent({
  props: { modelValue: Boolean, disabled: Boolean },
  emits: ['update:modelValue'],
  setup: (props, { emit }) => () => h('input', {
    type: 'checkbox',
    checked: props.modelValue,
    disabled: props.disabled,
    onChange: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).checked),
  }),
})
const Button = defineComponent({
  props: { label: String, loading: Boolean },
  emits: ['click'],
  setup: (props, { emit }) => () => h('button', { onClick: () => emit('click') }, props.label),
})

const settings = {
  teamId: 10,
  reminder48hEnabled: true,
  reminder24hEnabled: true,
  reminder12hEnabled: false,
  deputyShiftViewEnabled: false,
  deputyHourlyRateViewEnabled: true,
}

async function mountPage() {
  const wrapper = await mountSuspended(Page, {
    global: { stubs: { ToggleSwitch, Button, PageHeader: true, LoadingBounce: true, ShiftManageNotice: true } },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  perm.canManageShift.value = true
  perm.isScopeAdmin.value = true
  perm.isDeputyWithoutManage.value = false
  mocks.loadPermissions.mockResolvedValue({ ok: true })
  mocks.getShiftSettings.mockResolvedValue(settings)
  mocks.updateShiftSettings.mockResolvedValue({ ...settings, deputyShiftViewEnabled: true })
})

describe('シフト設定: 副管理者への閲覧許可の切替', () => {
  it('GET の値を切替の初期状態に反映する', async () => {
    const wrapper = await mountPage()
    expect(mocks.getShiftSettings).toHaveBeenCalledWith('team-a')
    const shift = wrapper.get('[data-testid="deputy-shift-view-toggle"]').element as HTMLInputElement
    const rate = wrapper.get('[data-testid="deputy-hourly-rate-view-toggle"]').element as HTMLInputElement
    expect(shift.checked).toBe(false)
    expect(rate.checked).toBe(true)
  })

  it('切替を変えて保存すると、PATCH に副管理者の2項目を載せて送る', async () => {
    const wrapper = await mountPage()
    await wrapper.get('[data-testid="deputy-shift-view-toggle"]').setValue(true)
    await wrapper.get('[data-testid="deputy-hourly-rate-view-toggle"]').setValue(false)
    await wrapper.get('[data-testid="reminder-settings-save-btn"]').trigger('click')
    await flushPromises()
    expect(mocks.updateShiftSettings).toHaveBeenCalledWith('team-a', expect.objectContaining({
      deputyShiftViewEnabled: true,
      deputyHourlyRateViewEnabled: false,
    }))
    expect(mocks.notifySuccess).toHaveBeenCalled()
  })

  it('権限（ADMIN / MANAGE_SHIFTS）の無い副管理者は、切替を操作できず保存ボタンも出ない', async () => {
    perm.canManageShift.value = false
    perm.isScopeAdmin.value = false
    perm.isDeputyWithoutManage.value = true
    const wrapper = await mountPage()
    expect((wrapper.get('[data-testid="deputy-shift-view-toggle"]').element as HTMLInputElement).disabled).toBe(true)
    expect((wrapper.get('[data-testid="deputy-hourly-rate-view-toggle"]').element as HTMLInputElement).disabled).toBe(true)
    expect(wrapper.find('[data-testid="reminder-settings-save-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="shift-settings-readonly-notice"]').exists()).toBe(true)
    expect(mocks.updateShiftSettings).not.toHaveBeenCalled()
  })

  it('MANAGE_SHIFTS 付きの副管理者は閲覧切替を操作して保存できる', async () => {
    perm.isScopeAdmin.value = false
    const wrapper = await mountPage()
    expect((wrapper.get('[data-testid="deputy-shift-view-toggle"]').element as HTMLInputElement).disabled).toBe(false)
    expect(wrapper.find('[data-testid="reminder-settings-save-btn"]').exists()).toBe(true)
  })

  it('時給を見せる切替は ADMIN だけ操作でき、説明は出ない', async () => {
    const wrapper = await mountPage()
    expect((wrapper.get('[data-testid="deputy-hourly-rate-view-toggle"]').element as HTMLInputElement).disabled).toBe(false)
    expect(wrapper.find('[data-testid="deputy-hourly-rate-admin-only-notice"]').exists()).toBe(false)
  })

  it('MANAGE_SHIFTS 付きの副管理者（ADMIN ではない）は時給切替を操作できず、「管理者だけ」の説明が出る', async () => {
    perm.isScopeAdmin.value = false
    const wrapper = await mountPage()
    expect((wrapper.get('[data-testid="deputy-hourly-rate-view-toggle"]').element as HTMLInputElement).disabled).toBe(true)
    expect(wrapper.find('[data-testid="deputy-hourly-rate-admin-only-notice"]').exists()).toBe(true)
  })

  it('ADMIN 以外が保存するとき、PATCH に時給切替を含めない（閲覧切替とリマインドは送る）', async () => {
    perm.isScopeAdmin.value = false
    const wrapper = await mountPage()
    await wrapper.get('[data-testid="deputy-shift-view-toggle"]').setValue(true)
    await wrapper.get('[data-testid="reminder-settings-save-btn"]').trigger('click')
    await flushPromises()
    expect(mocks.updateShiftSettings).toHaveBeenCalledTimes(1)
    const body = mocks.updateShiftSettings.mock.calls[0]![1] as Record<string, unknown>
    expect(body).not.toHaveProperty('deputyHourlyRateViewEnabled')
    expect(body).toMatchObject({ deputyShiftViewEnabled: true, reminder48hEnabled: true })
  })

  it('PATCH の失敗は通知する（握りつぶさない）', async () => {
    mocks.updateShiftSettings.mockRejectedValue(new Error('403'))
    const wrapper = await mountPage()
    await wrapper.get('[data-testid="reminder-settings-save-btn"]').trigger('click')
    await flushPromises()
    expect(mocks.notifyError).toHaveBeenCalled()
    expect(mocks.notifySuccess).not.toHaveBeenCalled()
  })

  it('権限の取得に失敗したら通知する', async () => {
    mocks.loadPermissions.mockResolvedValue({ ok: false, error: new Error('x') })
    await mountPage()
    expect(mocks.notifyError).toHaveBeenCalled()
  })
})
