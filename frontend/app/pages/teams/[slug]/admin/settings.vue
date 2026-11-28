<script setup lang="ts">
definePageMeta({ layout: 'team', middleware: ['auth', 'admin-console'] })
const route = useRoute()
const slug = computed(() => String(route.params.slug))
const { state: gateState, error: gateError, load: reloadGate } = useTeamAccessGate(slug)
</script>

<template>
  <div>
    <ScopeNotFound v-if="gateState === 'notFound'" />
    <div v-else-if="gateState === 'loading'" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
    <DashboardErrorState v-else-if="gateState === 'error'" :error="gateError" @retry="reloadGate" />
    <template v-else>
        <AdminSettingsHub scope-type="team" :slug="slug" />
    </template>
  </div>
</template>
