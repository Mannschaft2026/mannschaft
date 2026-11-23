<script setup lang="ts">
import type {
  ShiftSlotResponse,
  CreateShiftSlotRequest,
  UpdateShiftSlotRequest,
  ShiftPositionResponse,
} from '~/types/shift'

const props = defineProps<{
  visible: boolean
  scheduleId: number
  slotData?: ShiftSlotResponse | null
  positions: ShiftPositionResponse[]
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: [slot: ShiftSlotResponse]
}>()

const { t } = useI18n()
const { createSlot, updateSlot } = useShiftSlotApi()
const { handleApiError } = useErrorHandler()
const { success } = useNotification()
const confirm = useConfirm()
const { formatCalendarDay } = useDatetime()

const saving = ref(false)

interface FormState {
  slotDate: string
  startTime: string
  endTime: string
  positionId: number | null
  requiredCount: number
  note: string
}

const form = ref<FormState>({
  slotDate: '',
  startTime: '09:00',
  endTime: '17:00',
  positionId: null,
  requiredCount: 1,
  note: '',
})

watch(
  () => props.visible,
  (visible) => {
    if (!visible) return
    // 開き直したときだけ保存可能に戻す（成功後は閉じ終わるまで再入させない）
    saving.value = false
    if (props.slotData) {
      form.value = {
        slotDate: props.slotData.time.slotDate,
        startTime: props.slotData.time.startTime,
        endTime: props.slotData.time.endTime,
        positionId: props.slotData.position.positionId,
        requiredCount: props.slotData.position.requiredCount,
        note: props.slotData.note ?? '',
      }
    } else {
      form.value = {
        slotDate: '',
        startTime: '09:00',
        endTime: '17:00',
        positionId: null,
        requiredCount: 1,
        note: '',
      }
    }
  },
  { immediate: true },
)

async function save(moveRequestsConfirmed = false) {
  if (!form.value.slotDate || saving.value) return
  if (
    props.slotData &&
    form.value.slotDate !== props.slotData.time.slotDate &&
    !moveRequestsConfirmed
  ) {
    const slotId = props.slotData.id
    const oldDate = props.slotData.time.slotDate
    const newDate = form.value.slotDate
    confirm.require({
      header: t('shift.slot.moveDateTitle'),
      // ISO のまま出さず、ロケールに応じた曜日付きの日付で示す
      message: t('shift.slot.moveDateMessage', {
        oldDate: formatCalendarDay(oldDate),
        newDate: formatCalendarDay(newDate),
      }),
      icon: 'pi pi-exclamation-triangle',
      rejectLabel: t('common.cancel'),
      // キャンセルは枠を塗りつぶさない（承認ボタンを主役にする）
      rejectClass: 'p-button-text',
      acceptLabel: t('shift.slot.moveDateAccept'),
      accept: () => {
        // 確認中に別の枠へ切り替わった場合は、古い確認を新しい更新へ流用しない。
        if (
          !props.visible || props.slotData?.id !== slotId ||
          props.slotData.time.slotDate !== oldDate || form.value.slotDate !== newDate
        ) return
        return save(true)
      },
    })
    return
  }
  saving.value = true
  try {
    let saved: ShiftSlotResponse
    if (props.slotData) {
      const payload: UpdateShiftSlotRequest = {
        slotDate: form.value.slotDate,
        startTime: form.value.startTime,
        endTime: form.value.endTime,
        positionId: form.value.positionId ?? undefined,
        requiredCount: form.value.requiredCount,
        note: form.value.note.trim() || undefined,
        ...(moveRequestsConfirmed ? { moveRequestsConfirmed: true } : {}),
      }
      saved = await updateSlot(props.slotData.id, payload)
    } else {
      const payload: CreateShiftSlotRequest = {
        slotDate: form.value.slotDate,
        startTime: form.value.startTime,
        endTime: form.value.endTime,
        positionId: form.value.positionId ?? undefined,
        requiredCount: form.value.requiredCount,
        note: form.value.note.trim() || undefined,
      }
      saved = await createSlot(props.scheduleId, payload)
    }
    success(t('shift.slot.saveSuccess'))
    // 成功時は saving を解除しない。閉じるアニメーション中も保存ボタンは DOM に残るため、
    // ここで解除すると遅れて届いた連打が同じ内容で再保存（二重作成）してしまう。
    // 次に開かれたとき（visible の watch）に解除する。
    emit('saved', saved)
    close()
  } catch (error) {
    // 失敗時は再試行できるようボタンを戻す
    saving.value = false
    handleApiError(error)
  }
}

function close() {
  emit('update:visible', false)
}

const positionOptions = computed(() => [
  { label: t('shift.slot.noPosition'), value: null },
  ...props.positions.map((p) => ({ label: p.name, value: p.id })),
])

const dialogHeader = computed(() =>
  props.slotData ? t('shift.slot.editTitle') : t('shift.slot.createTitle'),
)
</script>

<template>
  <Dialog
    :visible="visible"
    :header="dialogHeader"
    :style="{ width: '480px' }"
    modal
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <!-- 日付 -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.date') }}</label>
        <InputText v-model="form.slotDate" type="date" class="w-full" />
      </div>

      <!-- 時間帯 -->
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.startTime') }}</label>
          <InputText v-model="form.startTime" type="time" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.endTime') }}</label>
          <InputText v-model="form.endTime" type="time" class="w-full" />
        </div>
      </div>

      <!-- ポジション -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.position') }}</label>
        <Select
          v-model="form.positionId"
          :options="positionOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>

      <!-- 必要人数 -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.requiredCount') }}</label>
        <InputNumber v-model="form.requiredCount" :min="1" :max="99" class="w-full" />
      </div>

      <!-- メモ -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.note') }}</label>
        <InputText v-model="form.note" class="w-full" :placeholder="t('shift.slot.notePlaceholder')" />
      </div>
    </div>

    <template #footer>
      <Button :label="t('common.cancel')" text @click="close" />
      <Button
        :label="t('common.save')"
        icon="pi pi-check"
        :loading="saving"
        :disabled="!form.slotDate"
        @click="save()"
      />
    </template>
  </Dialog>
</template>
