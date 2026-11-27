<script setup lang="ts">
definePageMeta({
  layout: 'team',
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { state: gateState, error: gateError, load: reloadGate, onOk: onGateOk } = useTeamAccessGate(teamSlug)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

function onSaved() {
  listRef.value?.refresh()
}

function onSelect(eventId: number) {
  navigateTo(`/teams/${teamSlug}/events/${eventId}`)
}

onGateOk(() => loadPermissions())
</script>

<template>
  <div>
    <ScopeNotFound v-if="gateState === 'notFound'" />
    <div v-else-if="gateState === 'loading'" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
    <DashboardErrorState v-else-if="gateState === 'error'" :error="gateError" @retry="reloadGate" />
    <template v-else>
        <div>
          <div class="mb-4 flex items-center justify-between">
            <PageHeader title="イベント" />
            <Button label="イベント作成" icon="pi pi-plus" data-testid="team-event-create" @click="showCreateDialog = true" />
          </div>

          <EventList
            ref="listRef"
            scope-type="team"
            :scope-id="teamSlug"
            :can-edit="isAdminOrDeputy"
            :can-delete="isAdmin"
            @select="onSelect"
          />

          <EventForm
            v-model:visible="showCreateDialog"
            scope-type="team"
            :scope-id="teamSlug"
            @saved="onSaved"
          />
        </div>
    </template>
  </div>
</template>
