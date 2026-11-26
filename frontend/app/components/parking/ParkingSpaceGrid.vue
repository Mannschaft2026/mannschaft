<script setup lang="ts">
import type { ParkingSpaceResponse } from '~/types/parking'

defineProps<{
  spaces: ParkingSpaceResponse[]
}>()

const { t, te, locale } = useI18n()
const { formatDate } = useDatetime()

function statusClass(status: string | undefined): string {
  switch (status) {
    case 'VACANT':
      return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300'
    case 'OCCUPIED':
      return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300'
    default:
      return 'bg-surface-100 text-surface-500 dark:bg-surface-700 dark:text-surface-300'
  }
}

/** 未知の値（BE に列挙が増えた場合）は空にせず原文をそのまま出す。 */
function label(group: string, value: string | undefined): string {
  if (!value) return ''
  const key = `parking.spaces.${group}.${value}`
  return te(key) ? t(key) : value
}

function formatPrice(value: number): string {
  return new Intl.NumberFormat(locale.value, {
    style: 'currency',
    currency: 'JPY',
    maximumFractionDigits: 0,
  }).format(value)
}
</script>

<template>
  <div class="grid gap-3 sm:grid-cols-3 lg:grid-cols-4">
    <div
      v-for="s in spaces"
      :key="s.id"
      class="rounded-lg border border-surface-200 p-3 text-center dark:border-surface-700"
      data-testid="parking-space"
    >
      <p class="text-lg font-bold">{{ s.spaceNumber }}</p>
      <span
        :class="statusClass(s.status)"
        class="mt-1 inline-block rounded px-2 py-0.5 text-xs font-medium"
        >{{ label('status', s.status) }}</span
      >
      <p class="mt-2 text-xs text-surface-500">
        {{ label('type', s.spaceType)
        }}<template v-if="s.floor"> / {{ t('parking.spaces.floor', { floor: s.floor }) }}</template>
      </p>
      <p v-if="s.pricePerMonth != null" class="text-xs text-surface-500">
        {{ t('parking.spaces.pricePerMonth', { price: formatPrice(s.pricePerMonth) }) }}
      </p>
      <p
        v-if="s.status === 'VACANT' && s.applicationStatus"
        class="mt-1 text-xs text-surface-400"
        data-testid="parking-space-application-status"
      >
        {{ label('applicationStatus', s.applicationStatus) }}
        <template v-if="s.applicationStatus === 'ACCEPTING' && s.allocationMethod">
          ({{ label('allocation', s.allocationMethod) }})
        </template>
      </p>
      <p
        v-if="s.applicationStatus === 'ACCEPTING' && s.applicationDeadline"
        class="text-xs text-surface-400"
      >
        {{ t('parking.spaces.deadline', { date: formatDate(s.applicationDeadline) }) }}
      </p>
    </div>
  </div>
</template>
