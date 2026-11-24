<script setup lang="ts">
import type { ParkingSpaceResponse } from '~/types/parking'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
}>()

const { t } = useI18n()
const { getSpaces } = useParkingSpacesApi()
const { loadPermissions, isAdmin, can } = useRoleAccess(props.scopeType, props.scopeId)

const spaces = ref<ParkingSpaceResponse[]>([])
const loading = ref(false)
const loadError = ref<unknown>(null)

/**
 * 管理操作（区画の追加など）を出せるか。BE は ADMIN / MANAGE_PARKING 保有者だけを通し、
 * それ以外は 403 を返す。FE の出し分けは導線の整理であり、認可は BE が守る。
 */
const canManage = computed(() => isAdmin.value || can('MANAGE_PARKING'))

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

onMounted(async () => {
  await Promise.all([loadSpaces(), loadPermissions()])
})
</script>

<template>
  <div class="space-y-4">
    <div class="flex items-center justify-between gap-2">
      <PageHeader :title="t('parking.title')" />
      <Button v-if="canManage" :label="t('parking.spaces.add')" icon="pi pi-plus" />
    </div>

    <SectionCard :title="t('parking.spaces.title')">
      <PageLoading v-if="loading" />
      <DashboardErrorState
        v-else-if="loadError"
        :error="loadError"
        @retry="loadSpaces"
      />
      <DashboardEmptyState
        v-else-if="spaces.length === 0"
        icon="pi pi-car"
        :message="t('parking.spaces.empty')"
      />
      <ParkingSpaceGrid v-else :spaces="spaces" />
    </SectionCard>
  </div>
</template>
