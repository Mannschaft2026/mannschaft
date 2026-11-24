<script setup lang="ts">
import {
  APPLICATION_PAGE_SIZE,
  useParkingApplicationFlow,
} from '~/composables/parking/useParkingApplicationFlow'
import { fetchAllSpaces, isApplicableSpace } from '~/composables/parking/useParkingSpaceList'
import { useParkingSpacesApi } from '~/composables/parking/useParkingSpacesApi'
import type { ApplicationResponse, ParkingSpaceResponse } from '~/types/parking'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
}>()

const { t } = useI18n()
const confirm = useConfirm()
const auth = useAuthStore()
const { getSpaces } = useParkingSpacesApi()
const { loadPermissions, isAdmin, isMember, can } = useRoleAccess(props.scopeType, props.scopeId)

const spaces = ref<ParkingSpaceResponse[]>([])
const loading = ref(false)
const loadError = ref<unknown>(null)
const applyVisible = ref(false)

/**
 * 管理操作（区画の追加など）を出せるか。BE は ADMIN / MANAGE_PARKING 保有者だけを通し、
 * それ以外は 403 を返す。FE の出し分けは導線の整理であり、認可は BE が守る。
 * 承認・却下・抽選の画面は別戦役（台帳 CMP-261124-2331）で作る。
 */
const canManage = computed(() => isAdmin.value || can('MANAGE_PARKING'))
/** 申請できるのは MEMBER 以上（SUPPORTER は BE が 403）。 */
const canApply = computed(() => isMember.value)

const applicableSpaces = computed(() => spaces.value.filter(isApplicableSpace))

async function loadSpaces() {
  loading.value = true
  loadError.value = null
  try {
    spaces.value = await fetchAllSpaces((page, size) =>
      getSpaces(props.scopeType, props.scopeId, { page, size }),
    )
  } catch (e) {
    loadError.value = e
  } finally {
    loading.value = false
  }
}

const flow = useParkingApplicationFlow(props.scopeType, props.scopeId, {
  // 409/404/422 のあとは区画の受付状況が変わっている可能性があるので取り直す
  onStale: loadSpaces,
})

const spaceNumbers = computed<Record<number, string>>(() => {
  const map: Record<number, string> = {}
  for (const s of spaces.value) if (s.id != null && s.spaceNumber) map[s.id] = s.spaceNumber
  return map
})
const vehicleLabels = computed<Record<number, string>>(() => {
  const map: Record<number, string> = {}
  for (const v of flow.vehicles.value)
    if (v.id != null) map[v.id] = v.nickname ? `${v.plateNumber} (${v.nickname})` : String(v.plateNumber)
  return map
})
const currentUserId = computed(() => {
  const id = auth.user?.id
  return id == null ? null : Number(id)
})

async function openApply() {
  applyVisible.value = true
  // 開く直前に最新の受付状況・車両を取り直す（古い一覧のまま申請して 409/422 になるのを減らす）
  await Promise.all([loadSpaces(), flow.loadVehicles()])
}

async function onSubmit(body: { spaceId: number; vehicleId: number; message?: string }) {
  const ok = await flow.submit(body)
  if (ok) {
    applyVisible.value = false
    await loadSpaces()
  }
}

function onCancel(app: ApplicationResponse) {
  const space = app.spaceId != null ? spaceNumbers.value[app.spaceId] : undefined
  confirm.require({
    header: t('parking.applications.cancelConfirm.header'),
    message: t('parking.applications.cancelConfirm.message', { space: space ?? `#${app.spaceId}` }),
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: t('parking.applications.cancelConfirm.accept'),
    rejectLabel: t('parking.applications.cancelConfirm.reject'),
    acceptProps: { severity: 'danger' },
    accept: async () => {
      await flow.cancel(app)
    },
  })
}

onMounted(async () => {
  await Promise.all([
    loadSpaces(),
    loadPermissions(),
    flow.loadApplications(0),
    flow.loadVehicles(),
  ])
})
</script>

<template>
  <div class="space-y-4">
    <div class="flex items-center justify-between gap-2">
      <PageHeader :title="t('parking.title')" />
      <div class="flex gap-2">
        <Button
          v-if="canApply"
          :label="t('parking.apply.button')"
          icon="pi pi-send"
          data-testid="parking-apply-open"
          @click="openApply"
        />
        <Button v-if="canManage" :label="t('parking.spaces.add')" icon="pi pi-plus" severity="secondary" />
      </div>
    </div>

    <SectionCard :title="t('parking.spaces.title')">
      <PageLoading v-if="loading && spaces.length === 0" />
      <DashboardErrorState
        v-else-if="loadError && spaces.length === 0"
        :error="loadError"
        @retry="loadSpaces"
      />
      <DashboardEmptyState
        v-else-if="spaces.length === 0"
        icon="pi pi-car"
        :message="t('parking.spaces.empty')"
      />
      <template v-else>
        <p v-if="loadError" class="mb-2 text-sm text-red-600" data-testid="parking-spaces-refresh-error">
          {{ t('parking.spaces.refreshError') }}
          <Button :label="t('parking.apply.retry')" text size="small" @click="loadSpaces" />
        </p>
        <ParkingSpaceGrid :spaces="spaces" />
      </template>
    </SectionCard>

    <SectionCard
      :title="canManage ? t('parking.applications.titleAll') : t('parking.applications.title')"
    >
      <DashboardErrorState
        v-if="flow.loadError.value"
        :error="flow.loadError.value"
        @retry="flow.loadApplications()"
      />
      <PageLoading v-else-if="flow.loading.value && flow.applications.value.length === 0" />
      <DashboardEmptyState
        v-else-if="flow.applications.value.length === 0"
        icon="pi pi-inbox"
        :message="t('parking.applications.empty')"
      />
      <ParkingApplicationList
        v-else
        :applications="flow.applications.value"
        :total="flow.total.value"
        :page="flow.page.value"
        :rows="APPLICATION_PAGE_SIZE"
        :space-numbers="spaceNumbers"
        :vehicle-labels="vehicleLabels"
        :current-user-id="currentUserId"
        @cancel="onCancel"
        @page="flow.loadApplications"
      />
    </SectionCard>

    <ParkingApplicationForm
      v-model:visible="applyVisible"
      :spaces="applicableSpaces"
      :vehicles="flow.vehicles.value"
      :vehicles-loaded="flow.vehiclesLoaded.value"
      :vehicles-error="flow.vehiclesError.value"
      :submitting="flow.submitting.value"
      @submit="onSubmit"
      @register-vehicle="flow.registerVehicle"
      @retry-vehicles="flow.loadVehicles"
    />
  </div>
</template>
