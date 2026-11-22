<script setup lang="ts">
import type { ActivityTemplate } from '~/types/activity'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))

const { t } = useI18n()
const { getTemplates, deleteTemplate, duplicateTemplate } = useActivityApi()
const { resolveScopeId } = useActivityScopeId()
const confirm = useConfirm()
const { showError, showSuccess } = useNotification()
const { formatDate } = useDatetime()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const templates = ref<ActivityTemplate[]>([])
const loading = ref(false)
const showForm = ref(false)
const editingTemplate = ref<ActivityTemplate | null>(null)
/** slug から解決した数値の teamId（BE の scope_id は Long） */
const teamId = ref<number | null>(null)
/** 取得失敗（再試行できる）と、取得は成功したが該当チームが無い（404）を区別する */
const loadFailed = ref(false)
const notFound = ref(false)

async function loadTemplates() {
  loading.value = true
  loadFailed.value = false
  notFound.value = false
  try {
    teamId.value = await resolveScopeId('TEAM', teamSlug.value)
    if (teamId.value === null) {
      notFound.value = true
      templates.value = []
      return
    }
    const res = await getTemplates('TEAM', teamId.value)
    templates.value = res.data
  } catch {
    loadFailed.value = true
    showError(t('activity.template.fetch_error'))
  } finally {
    loading.value = false
  }
}

/** 編集中のテンプレートを最新の内容で読み直す（照合漏れのエラー後） */
async function reloadEditing() {
  const id = editingTemplate.value?.id
  await loadTemplates()
  editingTemplate.value = templates.value.find((tpl) => tpl.id === id) ?? null
  if (!editingTemplate.value) showForm.value = false
}

onMounted(() => {
  loadTemplates()
  loadPermissions()
})

function openCreate() {
  editingTemplate.value = null
  showForm.value = true
}

function openEdit(t: ActivityTemplate) {
  editingTemplate.value = t
  showForm.value = true
}

function handleDelete(id: number) {
  confirm.require({
    message: t('activity.template.delete_confirm'),
    header: t('activity.template.tooltip_delete'),
    accept: async () => {
      try {
        await deleteTemplate(id)
        showSuccess(t('activity.template.delete_success'))
        await loadTemplates()
      } catch {
        showError(t('activity.template.delete_error'))
      }
    },
  })
}

async function handleDuplicate(id: number) {
  if (teamId.value === null) return
  try {
    await duplicateTemplate(id, { targetScopeType: 'TEAM', targetScopeId: teamId.value })
    showSuccess(t('activity.template.duplicate_success'))
    await loadTemplates()
  } catch {
    showError(t('activity.template.duplicate_error'))
  }
}

</script>

<template>
  <div class="p-4">
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-xl font-bold">{{ $t('activity.template.page_title') }}</h1>
      <Button
        v-if="isAdminOrDeputy && teamId !== null"
        :label="$t('activity.template.create_button')"
        icon="pi pi-plus"
        @click="openCreate"
      />
    </div>

    <PageLoading v-if="loading" size="40px" />

    <div v-else-if="loadFailed" class="py-12 text-center">
      <p class="mb-3 text-surface-500">{{ $t('activity.template.fetch_error') }}</p>
      <Button :label="$t('activity.template.retry')" icon="pi pi-refresh" outlined @click="loadTemplates" />
    </div>

    <div v-else-if="notFound" class="py-12 text-center text-surface-500">
      {{ $t('activity.template.scope_not_found') }}
    </div>

    <DataTable
      v-else
      :value="templates"
      striped-rows
      class="text-sm"
    >
      <template #empty>
        <div class="py-12 text-center text-surface-400">
          <i class="pi pi-file mb-3 text-4xl text-surface-300" />
          <p>{{ $t('activity.template.empty') }}</p>
        </div>
      </template>

      <Column field="name" :header="$t('activity.template.table_name')" />

      <Column :header="$t('activity.template.table_field_count')">
        <template #body="{ data }">
          {{ $t('activity.template.field_count_value', { count: data.fields?.length ?? 0 }) }}
        </template>
      </Column>

      <Column :header="$t('activity.template.table_created_at')">
        <template #body="{ data }">
          {{ formatDate(data.createdAt) }}
        </template>
      </Column>

      <Column v-if="isAdminOrDeputy" :header="$t('activity.template.table_actions')" style="width: 160px">
        <template #body="{ data }">
          <div class="flex items-center gap-1">
            <Button
              v-tooltip.top="$t('activity.template.tooltip_edit')"
              icon="pi pi-pencil"
              text
              rounded
              size="small"
              @click="openEdit(data)"
            />
            <Button
              v-tooltip.top="$t('activity.template.tooltip_duplicate')"
              icon="pi pi-copy"
              text
              rounded
              size="small"
              @click="handleDuplicate(data.id)"
            />
            <Button
              v-tooltip.top="$t('activity.template.tooltip_delete')"
              icon="pi pi-trash"
              text
              rounded
              size="small"
              severity="danger"
              @click="handleDelete(data.id)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <ActivityTemplateForm
      v-if="teamId !== null"
      v-model:visible="showForm"
      scope-type="TEAM"
      :scope-id="teamId"
      :template="editingTemplate"
      :can-manage-effort-settings="isAdminOrDeputy"
      @saved="loadTemplates"
      @reload="reloadEditing"
    />
  </div>
</template>
