<script setup lang="ts">
definePageMeta({
  layout: 'team',
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { state: gateState, error: gateError, load: reloadGate, onOk: onGateOk } = useTeamAccessGate(teamSlug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

function onSaved() {
  listRef.value?.refresh()
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
          <PageHeader title="フォームテンプレート" :back-to="`/teams/${teamSlug}/forms`" back-label="フォームに戻る" />
          <Button
            v-if="isAdminOrDeputy"
            label="テンプレート作成"
            icon="pi pi-plus"
            @click="showCreateDialog = true"
          />
        </div>

        <FormTemplateList
          ref="listRef"
          scope-type="team"
          :scope-id="teamSlug"
          :can-edit="isAdminOrDeputy"
        />

        <FormTemplateEditor
          v-model:visible="showCreateDialog"
          scope-type="team"
          :scope-id="teamSlug"
          @saved="onSaved"
        />
      </div>
    </template>
  </div>
</template>
