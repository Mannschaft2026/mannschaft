<script setup lang="ts">
import { computed } from 'vue'

definePageMeta({
  layout: 'team',
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const { state: gateState, error: gateError, load: reloadGate, onOk: onGateOk } = useTeamAccessGate(teamSlug)

const { loadMyDisclosedEvaluations } = useAttendanceDisclosure()

onGateOk(async () => {
  await loadMyDisclosedEvaluations()
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
  <div class="flex flex-col min-h-screen" data-testid="disclosed-inbox-page">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton :to="`/teams/${teamSlug}`" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0">
        {{ $t('school.disclosure.inbox.title') }}
      </h1>
    </header>

    <main class="flex-1 p-4 max-w-2xl mx-auto w-full">
      <DisclosedRequirementInbox />
    </main>
  </div>
    </template>
  </div>
</template>
