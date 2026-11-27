<script setup lang="ts">
import type { ParkingSpaceResponse, VehicleResponse } from '~/types/parking'

type VehicleKind = 'CAR' | 'MOTORCYCLE' | 'BICYCLE'

const props = defineProps<{
  /** 申請できる区画（受付中・来場者用でない・空き）。絞り込みは呼び出し側で済ませる。 */
  spaces: ParkingSpaceResponse[]
  vehicles: VehicleResponse[]
  vehiclesLoaded: boolean
  vehiclesError: unknown
  submitting: boolean
}>()

const visible = defineModel<boolean>('visible', { required: true })

const emit = defineEmits<{
  submit: [body: { spaceId: number; vehicleId: number; message?: string }]
  registerVehicle: [body: { vehicleType: VehicleKind; plateNumber: string; nickname?: string }]
  retryVehicles: []
}>()

const { t, te } = useI18n()

const MESSAGE_MAX = 500
const PLATE_MAX = 30
const NICKNAME_MAX = 50

const spaceId = ref<number | null>(null)
const vehicleId = ref<number | null>(null)
const message = ref('')

const showRegister = ref(false)
const newPlate = ref('')
const newKind = ref<VehicleKind>('CAR')
const newNickname = ref('')

const spaceOptions = computed(() =>
  props.spaces.map((s) => {
    const typeKey = `parking.spaces.type.${s.spaceType}`
    const type = s.spaceType && te(typeKey) ? t(typeKey) : (s.spaceType ?? '')
    return { value: s.id as number, label: type ? `${s.spaceNumber} (${type})` : String(s.spaceNumber) }
  }),
)
const vehicleOptions = computed(() =>
  props.vehicles.map((v) => ({
    value: v.id as number,
    label: v.nickname ? `${v.plateNumber} (${v.nickname})` : String(v.plateNumber),
  })),
)
const kindOptions = computed(() =>
  (['CAR', 'MOTORCYCLE', 'BICYCLE'] as const).map((k) => ({
    value: k,
    label: t(`parking.vehicle.kind.${k}`),
  })),
)

const noVehicle = computed(() => props.vehiclesLoaded && props.vehicles.length === 0)
const canSubmit = computed(
  () =>
    spaceId.value != null &&
    vehicleId.value != null &&
    message.value.length <= MESSAGE_MAX &&
    !props.submitting,
)
const canRegister = computed(
  () =>
    newPlate.value.trim().length > 0 &&
    newPlate.value.length <= PLATE_MAX &&
    newNickname.value.length <= NICKNAME_MAX,
)

// 開くたびに入力を初期化する（前回の入力・選択済み区画が既に埋まった区画のまま残らないように）
watch(visible, (open) => {
  if (!open) return
  spaceId.value = null
  vehicleId.value = props.vehicles.length === 1 ? (props.vehicles[0]?.id ?? null) : null
  message.value = ''
  showRegister.value = false
}, { immediate: true })

// 車両が増えた（登録直後）ら選択し、登録フォームを閉じる
watch(
  () => props.vehicles,
  (now, before) => {
    if (now.length > (before?.length ?? 0) && vehicleId.value == null && visible.value) {
      vehicleId.value = now[now.length - 1]?.id ?? null
      showRegister.value = false
      newPlate.value = ''
      newNickname.value = ''
    }
  },
)

// 区画一覧が取り直されて選択中の区画が無くなったら選択を外す
watch(
  () => props.spaces,
  (now) => {
    if (spaceId.value != null && !now.some((s) => s.id === spaceId.value)) spaceId.value = null
  },
)

function onSubmit() {
  if (!canSubmit.value || spaceId.value == null || vehicleId.value == null) return
  const text = message.value.trim()
  emit('submit', {
    spaceId: spaceId.value,
    vehicleId: vehicleId.value,
    ...(text ? { message: text } : {}),
  })
}

function onRegister() {
  if (!canRegister.value) return
  const nickname = newNickname.value.trim()
  emit('registerVehicle', {
    vehicleType: newKind.value,
    plateNumber: newPlate.value.trim(),
    ...(nickname ? { nickname } : {}),
  })
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('parking.apply.dialogTitle')"
    :style="{ width: '32rem', maxWidth: '95vw' }"
    data-testid="parking-apply-dialog"
  >
    <div class="space-y-4">
      <p v-if="spaceOptions.length === 0" class="text-sm text-surface-500" data-testid="parking-no-spaces">
        {{ t('parking.apply.noSpaces') }}
      </p>
      <div v-else class="flex flex-col gap-1">
        <label for="parking-apply-space" class="text-sm font-medium">{{ t('parking.apply.space') }}</label>
        <Select
          v-model="spaceId"
          input-id="parking-apply-space"
          :options="spaceOptions"
          option-label="label"
          option-value="value"
          :placeholder="t('parking.apply.spacePlaceholder')"
          class="w-full"
          data-testid="parking-apply-space"
        />
      </div>

      <div class="flex flex-col gap-1">
        <label for="parking-apply-vehicle" class="text-sm font-medium">{{ t('parking.apply.vehicle') }}</label>
        <p v-if="vehiclesError" class="text-sm text-red-600" data-testid="parking-vehicles-error">
          {{ t('parking.apply.loadVehiclesError') }}
          <Button
            :label="t('parking.apply.retry')"
            text
            size="small"
            @click="emit('retryVehicles')"
          />
        </p>
        <template v-else>
          <Select
            v-if="!noVehicle"
            v-model="vehicleId"
            input-id="parking-apply-vehicle"
            :options="vehicleOptions"
            option-label="label"
            option-value="value"
            :placeholder="t('parking.apply.vehiclePlaceholder')"
            class="w-full"
            data-testid="parking-apply-vehicle"
          />
          <p v-else class="text-sm text-surface-500" data-testid="parking-no-vehicle">
            {{ t('parking.apply.noVehicle') }}
          </p>
          <Button
            v-if="!showRegister"
            :label="t('parking.vehicle.register')"
            icon="pi pi-plus"
            text
            size="small"
            class="self-start"
            data-testid="parking-register-vehicle-open"
            @click="showRegister = true"
          />
        </template>
      </div>

      <div
        v-if="showRegister"
        class="space-y-3 rounded-lg border border-surface-200 p-3 dark:border-surface-700"
        data-testid="parking-register-vehicle"
      >
        <div class="flex flex-col gap-1">
          <label for="parking-vehicle-kind" class="text-sm font-medium">{{ t('parking.vehicle.type') }}</label>
          <Select
            v-model="newKind"
            input-id="parking-vehicle-kind"
            :options="kindOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div class="flex flex-col gap-1">
          <label for="parking-vehicle-plate" class="text-sm font-medium">{{ t('parking.vehicle.plate') }}</label>
          <InputText
            id="parking-vehicle-plate"
            v-model="newPlate"
            :maxlength="PLATE_MAX"
            class="w-full"
            data-testid="parking-vehicle-plate"
          />
        </div>
        <div class="flex flex-col gap-1">
          <label for="parking-vehicle-nickname" class="text-sm font-medium">{{ t('parking.vehicle.nickname') }}</label>
          <InputText
            id="parking-vehicle-nickname"
            v-model="newNickname"
            :maxlength="NICKNAME_MAX"
            class="w-full"
          />
        </div>
        <div class="flex justify-end gap-2">
          <Button :label="t('parking.apply.close')" severity="secondary" text @click="showRegister = false" />
          <Button
            :label="t('parking.vehicle.save')"
            :disabled="!canRegister"
            data-testid="parking-register-vehicle-save"
            @click="onRegister"
          />
        </div>
      </div>

      <div class="flex flex-col gap-1">
        <label for="parking-apply-message" class="text-sm font-medium">{{ t('parking.apply.message') }}</label>
        <Textarea
          id="parking-apply-message"
          v-model="message"
          rows="3"
          :maxlength="MESSAGE_MAX"
          class="w-full"
          data-testid="parking-apply-message"
        />
        <p class="text-xs text-surface-400">{{ t('parking.apply.messageHint', { max: MESSAGE_MAX }) }}</p>
      </div>
    </div>

    <template #footer>
      <Button :label="t('parking.apply.close')" severity="secondary" text @click="visible = false" />
      <Button
        :label="t('parking.apply.submit')"
        :loading="submitting"
        :disabled="!canSubmit"
        data-testid="parking-apply-submit"
        @click="onSubmit"
      />
    </template>
  </Dialog>
</template>
