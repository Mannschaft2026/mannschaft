<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamSlug = String(route.params.slug)
const { isMember, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const showGuide = ref(false)
onMounted(loadPermissions)
</script>

<template>
  <div>
    <PageHeader :title="$t('activity.pageTitle')" help @help="showGuide = true">
      <template v-if="isAdminOrDeputy" #actions>
        <NuxtLink :to="`/teams/${teamSlug}/activity-templates`">
          <Button :label="$t('activity.templateLink')" icon="pi pi-file-edit" outlined size="small" />
        </NuxtLink>
      </template>
    </PageHeader>

    <ActivityRecordList scope-type="TEAM" :scope-id="teamSlug" :is-member="isMember" />

    <ActivityGuideModal v-model:visible="showGuide" />
  </div>
</template>
