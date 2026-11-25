<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamSlug = String(route.params.slug)
const { isMember, loadPermissions } = useRoleAccess('team', teamSlug)

const showGuide = ref(false)
onMounted(loadPermissions)
</script>

<template>
  <div>
    <PageHeader :title="$t('activity.pageTitle')" help @help="showGuide = true">
      <template #actions>
        <NuxtLink
          v-if="isMember"
          :to="`/teams/${teamSlug}/effort-records`"
          class="inline-flex min-h-11 items-center gap-1 text-sm font-medium text-primary hover:underline"
          data-testid="activities-effort-link"
        >
          <i class="pi pi-chart-line" aria-hidden="true" />
          {{ $t('effort.teamHistory.link') }}
        </NuxtLink>
      </template>
    </PageHeader>

    <ActivityRecordList scope-type="TEAM" :scope-id="teamSlug" :is-member="isMember" />

    <ActivityGuideModal v-model:visible="showGuide" />
  </div>
</template>
