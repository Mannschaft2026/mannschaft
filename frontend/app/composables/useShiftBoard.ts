import type { ShiftSlotResponse } from '~/types/shift'

export function useShiftBoard(scheduleId: Ref<number>, slots: Ref<ShiftSlotResponse[]>) {
  const shiftApi = useShiftApi()
  const localAssignments = ref<Record<number, number[]>>({})
  let latestLoad = 0

  function syncAssignments(): void {
    localAssignments.value = Object.fromEntries(
      slots.value.map((slot) => [slot.id, [...slot.assignedUserIds]]),
    )
  }

  async function loadSlots(): Promise<void> {
    const load = ++latestLoad
    const requestedSchedule = scheduleId.value
    const res = await shiftApi.getShiftSlots(requestedSchedule)
    // 古い応答（後から開始した GET がある）と別スケジュールの応答は捨てる。
    if (load !== latestLoad || requestedSchedule !== scheduleId.value) return
    // 並行した別枠の PATCH 成功で、すでに GET の応答より新しい版を持つ枠は巻き戻さず、
    // それ以外（失敗した枠など）は応答で必ず最新へ更新する。
    const localById = new Map(slots.value.map((slot) => [slot.id, slot]))
    slots.value = res.data.map((fetched) => {
      const local = localById.get(fetched.id)
      return local && local.version > fetched.version ? local : fetched
    })
    syncAssignments()
  }

  function versionOf(slotId: number): number {
    const version = slots.value.find((slot) => slot.id === slotId)?.version
    if (typeof version !== 'number' || !Number.isSafeInteger(version) || version < 0) {
      throw new Error('シフト枠の版を取得できません。再読み込みしてください。')
    }
    return version
  }

  function applySlot(slot: ShiftSlotResponse): void {
    // 切替前の API 成功は取り消さず、応答だけを現在の別スケジュールへ混入させない。
    const current = slots.value.find((current) => current.id === slot.id)
    // 新しい GET の版を、先に開始した PATCH の遅い応答で巻き戻さない。同版の警告は保持する。
    if (slot.scheduleId !== scheduleId.value || !current || slot.version < current.version) return
    // 警告・マスクを含む応答全体と、次の操作で送る実版を保持する。
    slots.value = slots.value.map((current) => current.id === slot.id ? slot : current)
    localAssignments.value[slot.id] = [...slot.assignedUserIds]
  }

  async function patch(
    slotId: number,
    slotVersion: number,
    change: { addUserIds?: number[]; removeUserIds?: number[] },
  ): Promise<void> {
    const res = await shiftApi.patchSlotAssignments(slotId, { ...change, slotVersion })
    applySlot(res.data)
  }

  async function moveUser(fromSlotId: number, toSlotId: number, userId: number): Promise<void> {
    if (fromSlotId === toSlotId) return
    // 両方の版を最初に確認し、片側の版不足で削除だけを実行しない。
    const fromVersion = versionOf(fromSlotId)
    const toVersion = versionOf(toSlotId)
    try {
      await patch(fromSlotId, fromVersion, { removeUserIds: [userId] })
      await patch(toSlotId, toVersion, { addUserIds: [userId] })
    } catch (error) {
      await reloadAfterFailure()
      throw error
    }
  }

  /**
   * 失敗（409 版競合・5xx 等）の後は枠一覧を取り直し、次の操作が最新の版で成功するようにする。
   * 再取得の失敗で元の操作エラーを隠さない（元のエラーを再送出する）。
   */
  async function reloadAfterFailure(): Promise<void> {
    try {
      await loadSlots()
    } catch (reloadError) {
      // 再取得失敗でも、利用者へは元の操作エラーを通知する。再取得の失敗は握りつぶさず記録する。
      const { captureQuiet } = useErrorReport()
      captureQuiet(reloadError, { context: 'useShiftBoard: 失敗後の枠再取得' })
    }
  }

  async function addUser(slotId: number, userId: number): Promise<void> {
    try {
      await patch(slotId, versionOf(slotId), { addUserIds: [userId] })
    } catch (error) {
      await reloadAfterFailure()
      throw error
    }
  }

  async function removeUser(slotId: number, userId: number): Promise<void> {
    try {
      await patch(slotId, versionOf(slotId), { removeUserIds: [userId] })
    } catch (error) {
      await reloadAfterFailure()
      throw error
    }
  }

  watch(scheduleId, () => {
    slots.value = []
    localAssignments.value = {}
  })

  return { localAssignments, loadSlots, moveUser, addUser, removeUser }
}
