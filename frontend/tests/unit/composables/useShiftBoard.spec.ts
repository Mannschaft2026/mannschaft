import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import { useShiftBoard } from '~/composables/useShiftBoard'
import type { ShiftSlotResponse } from '~/types/shift'

/** useShiftBoard — CMP-261125-1336: 409/5xx の後は枠を再取得し、次の操作が最新の版で成功する。 */
const api = vi.hoisted(() => ({ getShiftSlots: vi.fn(), patchSlotAssignments: vi.fn() }))
mockNuxtImport('useShiftApi', () => () => api)

function slot(version: number, assigned: number[]): ShiftSlotResponse {
  return {
    version,
    id: 5,
    scheduleId: 1,
    time: { slotDate: '2027-03-09', startTime: '09:00:00', endTime: '13:00:00', endsNextDay: false },
    position: { positionId: 1, positionName: 'A', requiredCount: 3 },
    assignedUserIds: assigned,
    assignmentMasked: false,
    note: null,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('useShiftBoard 失敗後の再取得', () => {
  it('追加が 409 で失敗したら枠を再取得し、次の追加は最新の版で成功する', async () => {
    const slots = ref<ShiftSlotResponse[]>([slot(0, [])])
    const board = useShiftBoard(ref(1), slots)
    api.patchSlotAssignments.mockRejectedValueOnce(Object.assign(new Error('conflict'), { statusCode: 409 }))
    api.getShiftSlots.mockResolvedValueOnce({ data: [slot(3, [])] })

    await expect(board.addUser(5, 9)).rejects.toThrow('conflict')
    expect(api.getShiftSlots).toHaveBeenCalledTimes(1)
    expect(slots.value[0]!.version).toBe(3)

    api.patchSlotAssignments.mockResolvedValueOnce({ data: slot(4, [9]) })
    await board.addUser(5, 9)
    expect(api.patchSlotAssignments).toHaveBeenLastCalledWith(5, { addUserIds: [9], slotVersion: 3 })
    expect(board.localAssignments.value[5]).toEqual([9])
  })

  it('外すが 5xx で失敗しても枠を再取得し、元のエラーを隠さない', async () => {
    const slots = ref<ShiftSlotResponse[]>([slot(0, [9])])
    const board = useShiftBoard(ref(1), slots)
    api.patchSlotAssignments.mockRejectedValueOnce(Object.assign(new Error('server'), { statusCode: 500 }))
    api.getShiftSlots.mockResolvedValueOnce({ data: [slot(2, [9])] })

    await expect(board.removeUser(5, 9)).rejects.toThrow('server')
    expect(slots.value[0]!.version).toBe(2)
  })

  it('再取得も失敗したときは、再取得のエラーではなく元の操作エラーを投げる', async () => {
    const slots = ref<ShiftSlotResponse[]>([slot(0, [])])
    const board = useShiftBoard(ref(1), slots)
    api.patchSlotAssignments.mockRejectedValueOnce(new Error('original'))
    api.getShiftSlots.mockRejectedValueOnce(new Error('reload failed'))
    await expect(board.addUser(5, 9)).rejects.toThrow('original')
  })
})
