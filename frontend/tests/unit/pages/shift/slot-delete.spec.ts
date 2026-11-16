import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import Button from 'primevue/button'
import ShiftEditPage from '~/pages/shift/[id]/edit.vue'
import type { ShiftScheduleResponse, ShiftSlotResponse } from '~/types/shift'

const getSchedule = vi.fn()
const listSlots = vi.fn()
const listPositions = vi.fn()
const deleteSlot = vi.fn()
const listRequests = vi.fn()
const notifySuccess = vi.fn()
const handleApiError = vi.fn()
let role = 'ADMIN'
let confirmation: { message: string; accept: () => Promise<void>; reject?: () => void } | null = null

const teamStore = {
  get myTeams() { return [{ id: 1, role }] },
  fetchMyTeams: vi.fn(async () => {}),
}

mockNuxtImport('useRoute', () => () => ({ params: { id: '91' } }))
mockNuxtImport('useTeamStore', () => () => teamStore)
mockNuxtImport('useShiftApi', () => () => ({ getSchedule }))
mockNuxtImport('useShiftSlotApi', () => () => ({ listSlots, deleteSlot, bulkCreateSlots: vi.fn() }))
mockNuxtImport('useShiftRequestApi', () => () => ({ listRequests }))
mockNuxtImport('useShiftPositionApi', () => () => ({ listPositions }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useNotification', () => () => ({ success: notifySuccess }))
mockNuxtImport('useConfirm', () => () => ({
  require: (options: NonNullable<typeof confirmation>) => { confirmation = options },
}))

const schedule: ShiftScheduleResponse = {
  id: 91, teamId: 1,
  content: { title: '希望提出済み枠のあるシフト', periodType: 'WEEKLY', note: null },
  period: { startDate: '2026-10-10', endDate: '2026-10-11', requestDeadline: null },
  status: { status: 'COLLECTING', publishedAt: null, publishedBy: null },
  audit: { createdBy: 3, createdAt: '2026-09-29T00:00:00', updatedAt: '2026-09-29T00:00:00' },
}
const slot: ShiftSlotResponse = {
  version: 0, id: 301, scheduleId: 91,
  time: { slotDate: '2026-10-10', startTime: '09:00:00', endTime: '12:00:00', endsNextDay: false },
  position: { positionId: null, positionName: null, requiredCount: 1 },
  assignedUserIds: [], assignmentMasked: false, note: null,
}

async function mountPage() {
  const wrapper = await mountSuspended(ShiftEditPage)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  role = 'ADMIN'
  confirmation = null
  getSchedule.mockResolvedValue(schedule)
  listSlots.mockResolvedValue([slot])
  listPositions.mockResolvedValue([])
  deleteSlot.mockReset().mockResolvedValue(undefined)
  listRequests.mockReset().mockResolvedValue([])
})

describe('シフト枠削除 — 警告と明示確認（CMP-260909-2153）', () => {
  it('希望0件・割当0件では確認文言が「この枠を削除しますか」相当のみで、確認前はDELETEしない', async () => {
    const wrapper = await mountPage()
    await wrapper.findAllComponents(Button).find(button => button.props('icon') === 'pi pi-trash')!.trigger('click')

    expect(deleteSlot).not.toHaveBeenCalled()
    expect(confirmation?.message).toBe('Delete this slot?')
  })

  it('確認を取り消しても枠を残し、DELETEと成功通知を行わない', async () => {
    const wrapper = await mountPage()
    await wrapper.findAllComponents(Button).find(button => button.props('icon') === 'pi pi-trash')!.trigger('click')
    expect(confirmation).not.toBeNull()
    confirmation?.reject?.()
    await flushPromises()

    expect(deleteSlot).not.toHaveBeenCalled()
    expect(notifySuccess).not.toHaveBeenCalled()
    expect(wrapper.findAllComponents(Button).filter(button => button.props('icon') === 'pi pi-trash')).toHaveLength(1)
  })

  it('承諾した対象枠を1回だけ削除し、成功してから一覧から取り除く', async () => {
    const wrapper = await mountPage()
    await wrapper.findAllComponents(Button).find(button => button.props('icon') === 'pi pi-trash')!.trigger('click')
    expect(confirmation).not.toBeNull()
    await confirmation!.accept()
    await flushPromises()

    expect(deleteSlot).toHaveBeenCalledExactlyOnceWith(301)
    expect(notifySuccess).toHaveBeenCalledOnce()
    expect(wrapper.findAllComponents(Button).filter(button => button.props('icon') === 'pi pi-trash')).toHaveLength(0)
  })

  it('送信中に承諾が重なってもDELETEを重複送信しない', async () => {
    let finish!: () => void
    deleteSlot.mockImplementation(() => new Promise<void>(resolve => { finish = resolve }))
    const wrapper = await mountPage()
    await wrapper.findAllComponents(Button).find(button => button.props('icon') === 'pi pi-trash')!.trigger('click')
    expect(confirmation).not.toBeNull()
    const pending = confirmation!.accept()
    await confirmation!.accept()
    expect(deleteSlot).toHaveBeenCalledExactlyOnceWith(301)
    expect(wrapper.findAllComponents(Button).find(button => button.props('icon') === 'pi pi-trash')!.attributes('disabled')).toBeDefined()
    finish()
    await pending
  })

  it('DELETE失敗時は枠を保持し、エラーを表示して再操作できる', async () => {
    const error = new Error('削除失敗')
    deleteSlot.mockRejectedValue(error)
    const wrapper = await mountPage()
    await wrapper.findAllComponents(Button).find(button => button.props('icon') === 'pi pi-trash')!.trigger('click')
    expect(confirmation).not.toBeNull()
    await confirmation!.accept()
    await flushPromises()

    expect(handleApiError).toHaveBeenCalledWith(error)
    expect(notifySuccess).not.toHaveBeenCalled()
    const button = wrapper.findAllComponents(Button).find(button => button.props('icon') === 'pi pi-trash')!
    expect(button.exists()).toBe(true)
    expect(button.attributes('disabled')).toBeUndefined()
  })

  it('MEMBERには既存どおり枠削除の操作を表示しない', async () => {
    role = 'MEMBER'
    const wrapper = await mountPage()
    expect(wrapper.findAllComponents(Button).filter(button => button.props('icon') === 'pi pi-trash')).toHaveLength(0)
    expect(deleteSlot).not.toHaveBeenCalled()
  })
})

const req = (id: number, slotId: number | null) => ({
  id, scheduleId: 91, userId: 10 + id, slotId, slotDate: '2026-10-10', preference: 'AVAILABLE', note: null, submittedAt: '2026-09-29T00:00:00',
})

async function openConfirm() {
  const wrapper = await mountPage()
  await wrapper.findAllComponents(Button).find(button => button.props('icon') === 'pi pi-trash')!.trigger('click')
  await flushPromises()
  return wrapper
}

describe('シフト枠削除 — 確認文言の件数表示', () => {
  it('希望0・割当0: 件数にも元に戻せない旨にも触れない', async () => {
    await openConfirm()
    expect(listRequests).toHaveBeenCalledWith(91)
    expect(confirmation?.message).toBe('Delete this slot?')
  })

  it('希望のみ: 対象枠(slotId一致)の希望だけ数え、他枠・日単位(slotId=null)は数えない', async () => {
    listRequests.mockResolvedValue([req(1, 301), req(2, 301), req(3, 999), req(4, null)])
    await openConfirm()
    expect(confirmation?.message).toContain('2 request(s)')
    expect(confirmation?.message).not.toContain('assignment')
    expect(confirmation?.message).toContain('cannot be undone')
  })

  it('日単位希望(slotId=null)だけなら希望0件扱いで素の文言', async () => {
    listRequests.mockResolvedValue([req(4, null)])
    await openConfirm()
    expect(confirmation?.message).toBe('Delete this slot?')
  })

  it('割当のみ: 割当件数を添える', async () => {
    listSlots.mockResolvedValue([{ ...slot, assignedUserIds: [5, 6, 7] }])
    await openConfirm()
    expect(confirmation?.message).toContain('3 assignment(s)')
    expect(confirmation?.message).not.toContain('request(s)')
    expect(confirmation?.message).toContain('cannot be undone')
  })

  it('希望と割当の両方: 両件数を添える', async () => {
    listRequests.mockResolvedValue([req(1, 301)])
    listSlots.mockResolvedValue([{ ...slot, assignedUserIds: [5, 6] }])
    await openConfirm()
    expect(confirmation?.message).toContain('1 request(s)')
    expect(confirmation?.message).toContain('2 assignment(s)')
  })

  it('assignmentMasked のときは割当件数を出さない', async () => {
    listSlots.mockResolvedValue([{ ...slot, assignedUserIds: [5, 6], assignmentMasked: true }])
    await openConfirm()
    expect(confirmation?.message).toBe('Delete this slot?')
  })

  it('希望一覧の取得に失敗したら件数なしの安全側文言で続行し、エラーを通知して削除は可能', async () => {
    const error = new Error('取得失敗')
    listRequests.mockRejectedValue(error)
    await openConfirm()
    expect(handleApiError).toHaveBeenCalledWith(error)
    expect(confirmation?.message).toContain('Any requests submitted for it')
    expect(confirmation?.message).not.toMatch(/d/)
    await confirmation!.accept()
    expect(deleteSlot).toHaveBeenCalledExactlyOnceWith(301)
  })

  it('ダイアログを開くたびに希望一覧を取り直し、古い件数を出さない', async () => {
    const wrapper = await openConfirm()
    listRequests.mockResolvedValue([req(1, 301)])
    await wrapper.findAllComponents(Button).find(button => button.props('icon') === 'pi pi-trash')!.trigger('click')
    await flushPromises()
    expect(listRequests).toHaveBeenCalledTimes(2)
    expect(confirmation?.message).toContain('1 request(s)')
  })

  it('MEMBERは希望一覧も取得しない', async () => {
    role = 'MEMBER'
    await mountPage()
    expect(listRequests).not.toHaveBeenCalled()
  })
})
