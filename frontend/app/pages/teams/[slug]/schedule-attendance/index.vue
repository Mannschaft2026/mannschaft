<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const { state: gateState, error: gateError, load: reloadGate } = useTeamAccessGate(() => String(route.params.slug))
const { t } = useI18n()
useHead({ title: () => t('dashboard.attendance_results.listTitle') })
</script>

<template>
  <div>
    <ScopeNotFound v-if="gateState === 'notFound'" />
    <div v-else-if="gateState === 'loading'" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
    <DashboardErrorState v-else-if="gateState === 'error'" :error="gateError" @retry="reloadGate" />
    <template v-else>
  <AttendanceResultsList scope-type="team" :scope-id="String(route.params.slug)" />
    </template>
  </div>
</template>
