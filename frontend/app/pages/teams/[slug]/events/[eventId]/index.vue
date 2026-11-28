<script setup lang="ts">
definePageMeta({
  layout: 'team',
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { state: gateState, error: gateError, load: reloadGate, onOk: onGateOk } = useTeamAccessGate(teamSlug)
const eventId = Number(route.params.eventId)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const loading = ref(true)

onGateOk(async () => {
  try {
    await loadPermissions()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <ScopeNotFound v-if="gateState === 'notFound'" />
    <div v-else-if="gateState === 'loading'" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
    <DashboardErrorState v-else-if="gateState === 'error'" :error="gateError" @retry="reloadGate" />
    <template v-else>
        <PageLoading v-if="loading" />
        <div v-else>
          <div class="mb-4">
            <BackButton :to="`/teams/${teamSlug}/events`" label="イベント一覧に戻る" />
          </div>

          <EventDetail
            scope-type="team"
            :scope-id="teamSlug"
            :event-id="eventId"
            :can-edit="isAdminOrDeputy"
          />
        </div>
    </template>
  </div>
</template>
