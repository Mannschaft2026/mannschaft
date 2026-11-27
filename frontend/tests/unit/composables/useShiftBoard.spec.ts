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

  it('別枠の PATCH 成功が先に反映された後でも、409 の枠は再取得で最新版に更新され次の操作が成功する', async () => {
    const other = { ...slot(0, []), id: 6 }
    const slots = ref<ShiftSlotResponse[]>([slot(0, []), other])
    const board = useShiftBoard(ref(1), slots)
    let resolveGet!: (v: { data: ShiftSlotResponse[] }) => void
    api.patchSlotAssignments.mockImplementation(async (slotId: number) => {
      if (slotId === 5) throw Object.assign(new Error('conflict'), { statusCode: 409 })
      return { data: { ...other, version: 1, assignedUserIds: [7] } }
    })
    api.getShiftSlots.mockImplementationOnce(() => new Promise((resolve) => { resolveGet = resolve }))

    const failing = board.addUser(5, 9)
    await vi.waitFor(() => expect(api.getShiftSlots).toHaveBeenCalledTimes(1))
    // 再取得の応答待ち中に、別枠の PATCH が成功して反映される
    await board.addUser(6, 7)
    expect(slots.value.find((s) => s.id === 6)!.version).toBe(1)
    // 応答は別枠の更新前の版（6 は version 0）と、枠 5 の最新版（3）
    resolveGet({ data: [slot(3, []), { ...other, version: 0 }] })
    await expect(failing).rejects.toThrow('conflict')

    expect(slots.value.find((s) => s.id === 5)!.version).toBe(3)
    expect(slots.value.find((s) => s.id === 6)!.version).toBe(1)
    expect(board.localAssignments.value[6]).toEqual([7])

    api.patchSlotAssignments.mockImplementation(async () => ({ data: slot(4, [9]) }))
    await board.addUser(5, 9)
    expect(api.patchSlotAssignments).toHaveBeenLastCalledWith(5, { addUserIds: [9], slotVersion: 3 })
  })
})
