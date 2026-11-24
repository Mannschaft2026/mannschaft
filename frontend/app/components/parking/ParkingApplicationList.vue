<script setup lang="ts">
import { isCancellableApplication } from '~/composables/parking/useParkingApplicationFlow'
import type { ApplicationResponse } from '~/types/parking'

const props = defineProps<{
  applications: ApplicationResponse[]
  total: number
  /** 0 始まり */
  page: number
  rows: number
  /** 区画 ID → 区画番号 */
  spaceNumbers: Record<number, string>
  /** 車両 ID → 表示名 */
  vehicleLabels: Record<number, string>
  /** ログイン中の本人。他人の申請（管理者の全件表示時）には取消を出さない。 */
  currentUserId: number | null
}>()

const emit = defineEmits<{
  cancel: [app: ApplicationResponse]
  page: [page: number]
}>()

const { t, te } = useI18n()
const { formatDateTime } = useDatetime()

function statusLabel(status: string | undefined): string {
  if (!status) return ''
  const key = `parking.applications.status.${status}`
  return te(key) ? t(key) : status
}

function statusClass(status: string | undefined): string {
  switch (status) {
    case 'APPROVED':
      return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300'
    case 'REJECTED':
      return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
    case 'PENDING':
    case 'LOTTERY_PENDING':
      return 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300'
    default:
      return 'bg-surface-100 text-surface-500 dark:bg-surface-700 dark:text-surface-300'
  }
}

function canCancel(app: ApplicationResponse): boolean {
  return isCancellableApplication(app) && props.currentUserId != null && app.userId === props.currentUserId
}

function spaceName(app: ApplicationResponse): string {
  return (app.spaceId != null ? props.spaceNumbers[app.spaceId] : undefined) ?? `#${app.spaceId}`
}
function vehicleName(app: ApplicationResponse): string {
  return (app.vehicleId != null ? props.vehicleLabels[app.vehicleId] : undefined) ?? `#${app.vehicleId}`
}
</script>

<template>
  <div>
    <ul class="divide-y divide-surface-200 dark:divide-surface-700">
      <li
        v-for="app in applications"
        :key="app.id"
        class="flex flex-wrap items-start justify-between gap-2 py-3"
        data-testid="parking-application"
      >
        <div class="min-w-0 space-y-0.5">
          <p class="font-medium">
            {{ spaceName(app) }}
            <span
              :class="statusClass(app.status)"
              class="ml-2 inline-block rounded px-2 py-0.5 text-xs font-medium"
              data-testid="parking-application-status"
              >{{ statusLabel(app.status) }}</span
            >
          </p>
          <p class="text-xs text-surface-500">
            {{ t('parking.applications.vehicle') }}: {{ vehicleName(app) }} /
            {{ formatDateTime(app.createdAt) }}
          </p>
          <p v-if="app.message" class="break-words text-xs text-surface-500">{{ app.message }}</p>
          <p v-if="app.status === 'REJECTED' && app.rejectionReason" class="break-words text-xs text-red-600">
            {{ t('parking.applications.rejectionReason', { reason: app.rejectionReason }) }}
          </p>
        </div>
        <Button
          v-if="canCancel(app)"
          :label="t('parking.applications.cancel')"
          severity="danger"
          text
          size="small"
          data-testid="parking-application-cancel"
          @click="emit('cancel', app)"
        />
      </li>
    </ul>
    <Paginator
      v-if="total > rows"
      :first="page * rows"
      :rows="rows"
      :total-records="total"
      @page="(e: { page: number }) => emit('page', e.page)"
    />
  </div>
</template>
