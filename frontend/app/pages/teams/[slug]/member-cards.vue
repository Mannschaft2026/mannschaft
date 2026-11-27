<script setup lang="ts">
definePageMeta({ layout: 'default', middleware: 'auth' })
const route = useRoute()
const teamSlug = String(route.params.slug)
const { state: gateState, error: gateError, load: reloadGate } = useTeamAccessGate(teamSlug)
</script>

<template>
  <div>
    <ScopeNotFound v-if="gateState === 'notFound'" />
    <div v-else-if="gateState === 'loading'" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
    <DashboardErrorState v-else-if="gateState === 'error'" :error="gateError" @retry="reloadGate" />
    <template v-else>
      <MemberCardManagement scope-type="team" :slug="String(route.params.slug)" />
    </template>
  </div>
</template>
