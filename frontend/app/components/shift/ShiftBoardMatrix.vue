<template>
  <div class="flex flex-col h-full min-w-0 overflow-hidden">
    <!-- 自動割当結果バナー -->
    <ShiftAutoAssignResultBanner
      :pending-run="pendingRun"
      @confirm="onConfirmAutoAssign"
      @revoke="$emit('revokeAutoAssign')"
    />

    <!-- ボード本体。md 未満では表の内側だけを横スクロールさせ（日付・時間帯の列は左に固定）、ページ本体は広げない -->
    <div class="flex-1 overflow-auto" data-testid="shift-board-scroll">
      <div v-if="rows.length === 0" class="flex items-center justify-center h-64 text-surface-400">
        <p>{{ $t('shift.board.unassignedSlots', { count: 0 }) }}</p>
      </div>

      <table v-else class="min-w-full border-collapse text-sm" data-testid="shift-board-table">
        <thead class="sticky top-0 bg-surface-50 z-10">
          <tr>
            <th class="sticky left-0 z-20 bg-surface-50 px-3 py-2 text-left text-surface-600 border-b border-surface-200 min-w-32 max-md:min-w-28">
              {{ $t('shift.board.colDateTime') }}
            </th>
            <th
              v-for="column in columns"
              :key="column.key"
              class="px-3 py-2 text-left text-surface-600 border-b border-surface-200 min-w-40"
              :data-testid="`shift-board-col-${column.key}`"
            >
              <span
                v-if="column.color"
                class="inline-block w-3 h-3 rounded-full mr-1"
                :style="{ backgroundColor: column.color }"
              />
              {{ column.name }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row.key"
            class="border-b border-surface-100"
          >
            <td class="sticky left-0 z-[1] bg-surface-0 dark:bg-surface-900 px-3 py-2 text-surface-600 whitespace-nowrap font-medium border-r border-surface-100">
              <div>{{ formatSlotDate(row.date) }}</div>
              <div class="text-xs text-surface-400">{{ formatTimeRange(row.startTime, row.endTime) }}</div>
            </td>
            <td
              v-for="column in columns"
              :key="column.key"
              class="px-2 py-1 border-l border-surface-100 align-top"
            >
              <div v-if="slotsIn(row, column).length > 0" class="space-y-1">
                <ShiftBoardCell
                  v-for="slot in slotsIn(row, column)"
                  :key="slot.id"
                  :slot-id="slot.id"
                  :assignments="getAssignments(slot)"
                  :warnings="getSlotWarnings(slot.id)"
                  :required-count="slot.position.requiredCount"
                  :masked="slot.assignmentMasked"
                  :can-assign="canAssign"
                  :busy="busySlotIds.includes(slot.id)"
                  @drop="(userId) => $emit('dropUser', { fromSlotId: null, toSlotId: slot.id, userId })"
                  @remove-user="(userId) => $emit('removeUser', { slotId: slot.id, userId })"
                  @add-user="$emit('addUser', slot.id)"
                />
              </div>
              <div v-else class="min-h-12 bg-surface-50 rounded opacity-30" />
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { ShiftSlotResponse, ShiftPositionResponse, AssignmentRun, AssignmentWarning } from '~/types/shift'

interface Props {
  slots: ShiftSlotResponse[]
  positions: ShiftPositionResponse[]
  localAssignments: Record<number, number[]>
  memberMap: Record<number, { userId: number; displayName: string; avatarUrl: string | null }>
  pendingRun: AssignmentRun | null
  scheduleVersion?: number
  /** 割当操作（追加・外す・ドロップ）をしてよいか。false なら閲覧のみ */
  canAssign?: boolean
  /** 割当操作を処理中の枠 ID（二度押し防止） */
  busySlotIds?: number[]
}

const props = withDefaults(defineProps<Props>(), {
  scheduleVersion: 0,
  canAssign: false,
  busySlotIds: () => [],
})

const emit = defineEmits<{
  dropUser: [payload: { fromSlotId: number | null; toSlotId: number; userId: number }]
  removeUser: [payload: { slotId: number; userId: number }]
  addUser: [slotId: number]
  confirmAutoAssign: [note: string | undefined]
  revokeAutoAssign: []
}>()

const { t } = useI18n()
const { formatDate, formatTimeRange } = useDatetime()

const NO_POSITION_KEY = 'none'

interface Column {
  key: string
  name: string
  color: string | null
}

interface Row {
  key: string
  date: string
  startTime: string
  endTime: string
  slots: ShiftSlotResponse[]
}

const knownPositionIds = computed(() => new Set(props.positions.map((p) => p.id)))

// ポジション未設定（または一覧に無い）の枠は「ポジションなし」の列へ入れる。列に無い枠を黙って落とさない。
function columnKeyOf(slot: ShiftSlotResponse): string {
  const id = slot.position.positionId
  return id !== null && knownPositionIds.value.has(id) ? String(id) : NO_POSITION_KEY
}

const columns = computed((): Column[] => {
  const list: Column[] = props.positions.map((p) => ({ key: String(p.id), name: p.name, color: p.color ?? null }))
  if (props.slots.some((slot) => columnKeyOf(slot) === NO_POSITION_KEY)) {
    list.push({ key: NO_POSITION_KEY, name: t('shift.slot.noPosition'), color: null })
  }
  return list
})

// 日付・時間帯が同じ枠は 1 行にまとめ、ポジションを列に並べる
const rows = computed((): Row[] => {
  const map = new Map<string, Row>()
  for (const slot of props.slots) {
    const { slotDate, startTime, endTime } = slot.time
    const key = `${slotDate}|${startTime}|${endTime}`
    const row = map.get(key) ?? { key, date: slotDate, startTime, endTime, slots: [] }
    row.slots.push(slot)
    map.set(key, row)
  }
  return [...map.values()].sort((a, b) => {
    if (a.date !== b.date) return a.date.localeCompare(b.date)
    if (a.startTime !== b.startTime) return a.startTime.localeCompare(b.startTime)
    return a.endTime.localeCompare(b.endTime)
  })
})

function slotsIn(row: Row, column: Column): ShiftSlotResponse[] {
  return row.slots.filter((slot) => columnKeyOf(slot) === column.key)
}

function getAssignments(
  slot: ShiftSlotResponse,
): Array<{ userId: number; displayName: string; avatarUrl: string | null }> {
  const localUserIds = props.localAssignments[slot.id]
  // ローカル割当が存在する場合はそちらを優先
  const userIds = localUserIds ?? slot.assignedUserIds ?? []
  return userIds.map((userId) => ({
    userId,
    displayName: props.memberMap[userId]?.displayName ?? String(userId),
    avatarUrl: props.memberMap[userId]?.avatarUrl ?? null,
  }))
}

function getSlotWarnings(slotId: number): Array<{ userId: number; message: string }> {
  if (!props.pendingRun?.warnings) return []
  return props.pendingRun.warnings
    .filter((w: AssignmentWarning) => w.slotId === slotId && w.userId !== undefined)
    .map((w: AssignmentWarning) => ({ userId: w.userId!, message: w.message }))
}

function formatSlotDate(dateStr: string): string {
  return formatDate(dateStr)
}

function onConfirmAutoAssign(note: string | undefined): void {
  emit('confirmAutoAssign', note)
}
</script>
