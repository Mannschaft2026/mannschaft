import { readFileSync } from 'node:fs'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ShiftSlotEditDialog from './ShiftSlotEditDialog.vue'
import type { ShiftSlotResponse } from '~/types/shift'

const mocks = vi.hoisted(() => ({
  create: vi.fn(),
  update: vi.fn(),
  require: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
}))
mockNuxtImport('useShiftSlotApi', () => () => ({ createSlot: mocks.create, updateSlot: mocks.update }))
mockNuxtImport('useConfirm', () => () => ({ require: mocks.require }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))
mockNuxtImport('useNotification', () => () => ({ success: mocks.success }))
mockNuxtImport('useI18n', () => () => ({
  t: (key: string, args?: Record<string, string>) =>
    args ? `${key}: ${args.oldDate} → ${args.newDate}` : key,
}))

const slot: ShiftSlotResponse = {
  id: 12,
  scheduleId: 4,
  version: 0,
  time: { slotDate: '2026-12-31', startTime: '09:00', endTime: '17:00', endsNextDay: false },
  position: { positionId: null, positionName: null, requiredCount: 1 },
  assignedUserIds: [],
  assignmentMasked: false,
  note: null,
}

async function mountForm(slotData: ShiftSlotResponse | null = slot) {
  return mountSuspended(ShiftSlotEditDialog, {
    props: { visible: true, scheduleId: 4, slotData, positions: [] },
    global: {
      stubs: {
        Dialog: { template: '<section><slot/><slot name="footer"/></section>' },
        InputText: {
          props: ['modelValue', 'type'],
          emits: ['update:modelValue'],
          template: '<input :type="type" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">',
        },
        InputNumber: true,
        Select: true,
        Button: {
          props: ['label', 'disabled'],
          template: '<button :disabled="disabled">{{ label }}</button>',
        },
      },
    },
  })
}

type Form = Awaited<ReturnType<typeof mountForm>>
async function save(wrapper: Form) {
  await wrapper.findAll('button').find((button) => button.text() === 'common.save')!.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.resetAllMocks()
  mocks.update.mockResolvedValue({ ...slot, time: { ...slot.time, slotDate: '2027-01-01' } })
  mocks.create.mockResolvedValue(slot)
})

describe('日付移動確認の文言', () => {
  it('希望の有無をFEが断定しない（ja は「あれば」の条件表現）', async () => {
    const raw = readFileSync('app/locales/ja/shift.json', 'utf8')
    const ja = JSON.parse(raw) as { shift: { slot: { moveDateMessage: string } } }
    expect(ja.shift.slot.moveDateMessage).toContain('あれば')
  })
})

describe('枠の日付移動確認', () => {
  it('日付変更は旧日付と新日付と希望移動を示し、キャンセル時はAPIを送らない', async () => {
    const wrapper = await mountForm()
    await wrapper.find('input[type="date"]').setValue('2027-01-01')
    await save(wrapper)

    expect(mocks.require).toHaveBeenCalledOnce()
    const confirmation = mocks.require.mock.calls[0]![0]
    expect(confirmation.header).toBe('shift.slot.moveDateTitle')
    expect(confirmation.message).toContain('shift.slot.moveDateMessage')
    expect(confirmation.message).toContain(' → ')
    // ISO 形式のまま出さず、ロケール整形した日付を渡す
    expect(confirmation.message).not.toContain('2026-12-31')
    expect(confirmation.message).not.toContain('2027-01-01')
    // キャンセルは強調しない（承認が主ボタン）
    expect(confirmation.rejectClass).toContain('p-button-text')
    confirmation.reject?.()
    await flushPromises()
    expect(mocks.update).not.toHaveBeenCalled()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(wrapper.emitted('saved')).toBeUndefined()
  })

  it('承認後だけ確認フラグ付きで一度更新する', async () => {
    const wrapper = await mountForm()
    await wrapper.find('input[type="date"]').setValue('2027-01-01')
    await save(wrapper)
    expect(mocks.update).not.toHaveBeenCalled()

    await mocks.require.mock.calls[0]![0].accept()
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledExactlyOnceWith(12, expect.objectContaining({
      slotDate: '2027-01-01', moveRequestsConfirmed: true,
    }))
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })

  it('同日編集は確認なしで更新し、不要な確認フラグを送らない', async () => {
    const wrapper = await mountForm()
    await save(wrapper)
    expect(mocks.require).not.toHaveBeenCalled()
    expect(mocks.update).toHaveBeenCalledOnce()
    expect(mocks.update.mock.calls[0]![1].moveRequestsConfirmed).not.toBe(true)
  })

  it('新規作成は移動確認を要求しない', async () => {
    const wrapper = await mountForm(null)
    await wrapper.find('input[type="date"]').setValue('2027-01-01')
    await save(wrapper)
    expect(mocks.require).not.toHaveBeenCalled()
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it('確認後の更新失敗を表示し成功として閉じない', async () => {
    const error = { statusCode: 409 }
    mocks.update.mockRejectedValue(error)
    const wrapper = await mountForm()
    await wrapper.find('input[type="date"]').setValue('2027-01-01')
    await save(wrapper)
    await mocks.require.mock.calls[0]![0].accept()
    await flushPromises()
    expect(mocks.error).toHaveBeenCalledWith(error)
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(mocks.success).not.toHaveBeenCalled()
  })
})

describe('保存成功後の再入防止（閉じるアニメーション中の連打）', () => {
  it('新規作成が成功して閉じ始めた後の2回目の保存クリックでAPIを再送しない', async () => {
    const wrapper = await mountForm(null)
    await wrapper.find('input[type="date"]').setValue('2027-01-01')
    await save(wrapper)
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(wrapper.emitted('update:visible')).toEqual([[false]])

    // 親はまだ visible を false に反映しておらず、保存ボタンが DOM に残っている状態で再度クリックが届く
    await save(wrapper)
    await save(wrapper)
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })

  it('保存失敗後は再試行できる', async () => {
    mocks.create.mockRejectedValueOnce({ statusCode: 500 })
    const wrapper = await mountForm(null)
    await wrapper.find('input[type="date"]').setValue('2027-01-01')
    await save(wrapper)
    expect(mocks.error).toHaveBeenCalledOnce()
    await save(wrapper)
    expect(mocks.create).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })

  it('閉じてから開き直せば、再び保存できる', async () => {
    const wrapper = await mountForm(null)
    await wrapper.find('input[type="date"]').setValue('2027-01-01')
    await save(wrapper)
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })
    await wrapper.find('input[type="date"]').setValue('2027-01-02')
    await save(wrapper)
    expect(mocks.create).toHaveBeenCalledTimes(2)
  })
})
