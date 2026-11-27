<script setup lang="ts">
definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
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
      <div class="p-4">
        <div class="flex items-center justify-between mb-4">
          <h1 class="text-xl font-bold">メンバーフィールド管理</h1>
        </div>
        <MemberFieldsManager scope-type="TEAM" :scope-id="teamSlug" />
      </div>
    </template>
  </div>
</template>
