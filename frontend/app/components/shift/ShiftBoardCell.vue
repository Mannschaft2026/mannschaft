<template>
  <div
    class="min-h-16 p-1 border border-surface-200 rounded-lg transition-colors"
    :class="dropZoneClass"
    :data-testid="`shift-cell-${slotId}`"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <!-- 割当数 / 必要数。割当を伏せた枠は人数も空表示も出さず、概要と同じ中立表示にする -->
    <div class="mb-1 flex flex-wrap items-center gap-x-2 text-xs tabular-nums" data-testid="shift-cell-count">
      <span
        v-if="masked"
        class="rounded bg-surface-100 px-1 font-medium text-surface-600"
        :title="$t('shift.slot.assignmentMaskedHint')"
        data-testid="shift-cell-masked"
      >
        {{ $t('shift.slot.assignmentHidden') }}
      </span>
      <template v-else>
        <span class="font-semibold" :class="isFull ? 'text-green-700' : 'text-surface-600'">
          {{ $t('shift.board.assignedOfRequired', { assigned: assignments.length, required: requiredCount }) }}
        </span>
        <span v-if="isFull" class="font-semibold text-green-700" data-testid="shift-cell-full">
          {{ $t('shift.board.full') }}
        </span>
      </template>
    </div>

    <div v-if="!masked" class="flex flex-wrap gap-1">
      <ShiftMemberChip
        v-for="assignment in assignments"
        :key="assignment.userId"
        :display-name="assignment.displayName"
        :has-warning="hasWarning(assignment.userId)"
        :warning-message="getWarningMessage(assignment.userId)"
        :removable="canAssign"
        :disabled="busy"
        :remove-label="$t('shift.board.removeAssignment', { name: assignment.displayName })"
        @remove="onRemove(assignment.userId)"
      />
    </div>

    <!-- 割当操作は権限のある人にだけ出す。満員の枠は無効にして理由を見せる -->
    <template v-if="canAssign && !masked">
      <button
        type="button"
        class="mt-1 text-xs text-primary-500 hover:text-primary-700 transition-colors disabled:cursor-not-allowed disabled:text-surface-400 max-md:inline-flex max-md:min-h-11 max-md:min-w-11 max-md:items-center max-md:justify-center max-md:px-2"
        :disabled="isFull || busy"
        data-testid="shift-cell-add"
        @click="onAdd"
      >
        + {{ $t('shift.board.addMember') }}
      </button>
      <p v-if="isFull" class="text-xs text-surface-500" data-testid="shift-cell-full-reason">
        {{ $t('shift.board.fullReason') }}
      </p>
    </template>
  </div>
</template>

<script setup lang="ts">
interface AssignedMember {
  userId: number
  displayName: string
  avatarUrl: string | null
}

interface WarningItem {
  userId: number
  message: string
}

interface Props {
  slotId: number
  assignments: AssignedMember[]
  warnings?: WarningItem[]
  /** 必要人数 */
  requiredCount: number
  /** 割当をサーバー側で伏せた枠（人数・空表示を出さない） */
  masked?: boolean
  /** 割当操作（追加・外す・ドロップ）をしてよいか */
  canAssign?: boolean
  /** この枠の割当操作を処理中か（二度押し防止） */
  busy?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  warnings: () => [],
  masked: false,
  canAssign: false,
  busy: false,
})

const emit = defineEmits<{
  drop: [userId: number]
  removeUser: [userId: number]
  addUser: []
}>()

const isDragOver = ref(false)

const isFull = computed(() => !props.masked && props.assignments.length >= props.requiredCount)
const droppable = computed(() => props.canAssign && !props.masked && !props.busy && !isFull.value)

const dropZoneClass = computed(() => ({
  'bg-primary-50 border-primary-400 border-dashed': isDragOver.value,
  'bg-white': !isDragOver.value,
}))

function hasWarning(userId: number): boolean {
  return props.warnings.some((w) => w.userId === userId)
}

function getWarningMessage(userId: number): string | undefined {
  return props.warnings.find((w) => w.userId === userId)?.message
}

function onRemove(userId: number): void {
  if (!props.canAssign || props.busy) return
  emit('removeUser', userId)
}

function onAdd(): void {
  if (!props.canAssign || props.busy || isFull.value) return
  emit('addUser')
}

function onDragOver(event: DragEvent): void {
  // 権限が無い・処理中・伏せた枠はドロップ先にしない（preventDefault しない＝ドロップ不可のカーソルになる）
  if (!droppable.value) return
  event.preventDefault()
  isDragOver.value = true
}

function onDragLeave(): void {
  isDragOver.value = false
}

function onDrop(event: DragEvent): void {
  isDragOver.value = false
  if (!droppable.value) return
  const userId = Number(event.dataTransfer?.getData('text/plain'))
  if (!isNaN(userId) && userId > 0) {
    emit('drop', userId)
  }
}
</script>
