<template>
  <Dialog
    v-model:visible="visibleModel"
    :header="$t('shift.board.addMemberTitle')"
    modal
    :style="{ width: 'min(90vw, 360px)' }"
    data-testid="shift-add-member-dialog"
  >
    <div class="space-y-2">
      <p v-if="targetSlot" class="text-sm text-surface-600 [overflow-wrap:anywhere]" data-testid="shift-add-member-target">
        {{ $t('shift.board.addMemberSlotLabel', { slot: slotLabel(targetSlot) }) }}
      </p>

      <p v-if="isFull" class="text-sm text-surface-500" data-testid="shift-add-member-full">
        {{ $t('shift.board.fullReason') }}
      </p>

      <InputText
        v-model="filterQuery"
        :placeholder="$t('button.search')"
        class="w-full"
        data-testid="shift-add-member-search"
      />

      <div class="max-h-[50vh] space-y-1 overflow-y-auto">
        <button
          v-for="option in options"
          :key="option.member.userId"
          type="button"
          class="flex w-full items-center gap-3 rounded p-2 text-left transition-colors hover:bg-surface-50 disabled:cursor-not-allowed disabled:opacity-60 max-md:min-h-11"
          :disabled="busy || isFull || option.overlap !== null"
          :data-testid="`shift-add-member-option-${option.member.userId}`"
          @click="$emit('select', option.member.userId)"
        >
          <Avatar
            v-if="option.member.avatarUrl"
            :image="option.member.avatarUrl"
            size="small"
            shape="circle"
          />
          <Avatar v-else :label="option.member.displayName.charAt(0)" size="small" shape="circle" />
          <span class="min-w-0 flex-1">
            <span class="block text-sm [overflow-wrap:anywhere]">{{ option.member.displayName }}</span>
            <span
              v-if="option.overlap"
              class="block text-xs text-red-600 [overflow-wrap:anywhere]"
              data-testid="shift-add-member-overlap-reason"
            >
              {{ $t('shift.board.overlapReason', { slot: slotLabel(option.overlap) }) }}
            </span>
          </span>
        </button>

        <DashboardEmptyState
          v-if="options.length === 0"
          icon="pi pi-users"
          :message="$t(candidates.length === 0 ? 'shift.board.noAddableMembers' : 'shift.board.noMatchingMembers')"
        />
      </div>
    </div>
  </Dialog>
</template>

<script setup lang="ts">
import type { ShiftSlotResponse } from '~/types/shift'
import { findOverlappingSlot } from '~/utils/shiftOverlap'

interface MemberItem {
  userId: number
  displayName: string
  avatarUrl: string | null
}

interface Props {
  visible: boolean
  /** 追加先の枠 */
  targetSlot: ShiftSlotResponse | null
  /** 同じシフト表の全枠（時間の重なり判定に使う） */
  slots: ShiftSlotResponse[]
  members: MemberItem[]
  /** 画面の現在の割当（枠ID → ユーザーID 配列） */
  assignments: Record<number, number[]>
  /** 追加の処理中（二度押し防止） */
  busy?: boolean
}

const props = withDefaults(defineProps<Props>(), { busy: false })

const emit = defineEmits<{
  'update:visible': [value: boolean]
  select: [userId: number]
}>()

const { t } = useI18n()
const { formatDate, formatTimeRange } = useDatetime()

const visibleModel = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value),
})

const filterQuery = ref('')
watch(
  () => props.visible,
  (visible) => {
    if (visible) filterQuery.value = ''
  },
)

const assignedHere = computed((): number[] => {
  if (!props.targetSlot) return []
  return props.assignments[props.targetSlot.id] ?? props.targetSlot.assignedUserIds ?? []
})

const isFull = computed(() => props.targetSlot !== null && assignedHere.value.length >= props.targetSlot.position.requiredCount)

// この枠に既にいる人は出さない
const candidates = computed(() => props.members.filter((m) => !assignedHere.value.includes(m.userId)))

const options = computed(() => {
  const target = props.targetSlot
  const q = filterQuery.value.trim().toLowerCase()
  const filtered = q ? candidates.value.filter((m) => m.displayName.toLowerCase().includes(q)) : candidates.value
  return filtered.map((member) => ({
    member,
    // 時間が一部でも重なる別の枠に入っている人は、その枠を理由に選べなくする
    overlap: target ? findOverlappingSlot(target, member.userId, props.slots, props.assignments) : null,
  }))
})

function slotLabel(slot: ShiftSlotResponse): string {
  return t('shift.board.overlapSlotLabel', {
    date: formatDate(slot.time.slotDate),
    time: formatTimeRange(slot.time.startTime, slot.time.endTime),
    position: slot.position.positionName ?? t('shift.slot.noPosition'),
  })
}
</script>
