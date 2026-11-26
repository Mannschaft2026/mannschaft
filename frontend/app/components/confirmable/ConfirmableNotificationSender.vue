<script setup lang="ts">
import type { ConfirmableNotificationDraftDetail, ConfirmableNotificationDraftSaveRequest, ConfirmableNotificationPriority, ConfirmableNotificationTemplate, ConfirmableRecipientGroup, ConfirmableTarget, ConfirmableRecipientPreviewRequest, CreateConfirmableNotificationRequest, UnconfirmedVisibility } from '~/types/confirmable'

import { CONFIRMABLE_DRAFT_LIMIT } from '~/types/confirmable'

const props = defineProps<{ scopeType: 'TEAM' | 'ORGANIZATION'; scopeId: string; scopeSlug?: string; groupsVersion?: number }>()
const emit = defineEmits<{ sent: [] }>()
const { sendNotification, previewRecipients, listTemplates, listRecipientGroups } = useConfirmableNotificationApi()
const { handleApiError } = useErrorHandler()
const { t, locale } = useI18n()
const toast = useToast()
const sending = ref(false)
const showHelp = ref(false)
const templates = ref<ConfirmableNotificationTemplate[]>([])
const groups = ref<ConfirmableRecipientGroup[]>([])
const selectedTemplateId = ref<number | null>(null)
const selectedGroupId = ref<string | null>(null)
const audienceMode = ref<'DEFAULT' | 'TARGETS' | 'GROUP'>('DEFAULT')
const selectedTargets = ref<ConfirmableTarget[]>([])
const title = ref('')
const body = ref('')
const priority = ref<ConfirmableNotificationPriority>('NORMAL')
const deadlineAt = ref<Date | null>(null)
const unconfirmedVisibility = ref<UnconfirmedVisibility>('CREATOR_AND_ADMIN')
const acceptedCount = ref<number | null>(null)
const estimatedCount = ref<number | null>(null)
const previewing = ref(false)
/** タイトル欄の入口。下書きを開く・新しく書くとき、一覧の下にあるフォームを画面内へ出す */
const draftFormAnchor = ref<HTMLElement | null>(null)
function scrollToDraftForm() {
  void nextTick(() => draftFormAnchor.value?.scrollIntoView?.({ block: 'center', behavior: 'smooth' }))
}
let previewSequence = 0
const templateOptions = computed(() => [{ label: t('label.optional'), value: null }, ...templates.value.map(template => ({ label: template.name, value: template.id }))])
const groupOptions = computed(() => [{ label: t('confirmable.current_scope'), value: null }, ...groups.value.map(group => ({ label: group.name, value: group.id }))])
const audienceModeOptions = computed(() => ['DEFAULT', 'TARGETS', 'GROUP'].map(value => ({ label: t(`confirmable.audience_mode.${value}`), value })))
const priorityOptions = computed(() => ['NORMAL', 'HIGH', 'URGENT'].map(value => ({ label: t(`confirmable.priority.${value}`), value })))
const visibilityOptions = computed(() => ['HIDDEN', 'CREATOR_AND_ADMIN', 'ALL_MEMBERS'].map(value => ({ label: t(`confirmable.unconfirmed_visibility.${value}`), value })))

const { drafts, currentId: currentDraftId, conflict: draftConflict, limitReached: draftLimitReached, init: initDrafts, resetScope: resetDraftScope, newDraft, openDraft, requestDelete: requestDeleteDraft, reloadLatest: reloadLatestDraft, overwriteWithMine: overwriteDraft, settleForSend, afterSent, resumeAfterFailedSend } = useConfirmableDraft({
  scopeType: () => props.scopeType,
  scopeId: () => props.scopeId,
  snapshot: draftSnapshot,
  apply: applyDraft,
  onSwitched: scrollToDraftForm,
})
/** 自動保存する下書きの形。キー順は固定（変更検知に使う）。未確認者表示は下書きに含めない */
function draftSnapshot(): ConfirmableNotificationDraftSaveRequest {
  return {
    title: title.value,
    body: body.value,
    priority: priority.value,
    deadlineAt: deadlineAt.value ? deadlineAt.value.toISOString() : undefined,
    audienceMode: audienceMode.value,
    selectedTargets: selectedTargets.value.map(target => ({ type: target.type, id: target.id })),
    selectedGroupId: selectedGroupId.value ?? undefined,
    selectedTemplateId: selectedTemplateId.value ?? undefined,
  }
}
/** 下書きをフォームへ反映する。null は空のフォーム（新規状態） */
function applyDraft(draft: ConfirmableNotificationDraftDetail | null) {
  title.value = draft?.title ?? ''
  body.value = draft?.body ?? ''
  priority.value = draft?.priority ?? 'NORMAL'
  deadlineAt.value = draft?.deadlineAt ? new Date(draft.deadlineAt) : null
  audienceMode.value = draft?.audienceMode ?? 'DEFAULT'
  selectedTargets.value = (draft?.selectedTargets ?? []).map(target => ({ type: target.type, id: target.id }))
  selectedGroupId.value = draft?.selectedGroupId ?? null
  selectedTemplateId.value = draft?.selectedTemplateId ?? null
}
function formatUpdatedAt(value?: string): string {
  return value ? new Date(value).toLocaleString(locale.value, { dateStyle: 'short', timeStyle: 'short' }) : ''
}
function applyTemplate(templateId: number | null) {
  const template = templates.value.find(item => item.id === templateId)
  if (!template) return
  title.value = template.title
  body.value = template.body ?? ''
  priority.value = template.defaultPriority
  selectedGroupId.value = groups.value.some(group => group.id === template.defaultRecipientGroupId) ? template.defaultRecipientGroupId : null
  audienceMode.value = selectedGroupId.value ? 'GROUP' : 'DEFAULT'
}
async function loadOptions() {
  try {
    const [templateResponse, groupResponse] = await Promise.all([listTemplates(props.scopeType, props.scopeId), listRecipientGroups(props.scopeType, props.scopeId)])
    templates.value = templateResponse.data
    groups.value = groupResponse.data
    if (selectedGroupId.value && !groups.value.some(group => group.id === selectedGroupId.value)) {
      selectedGroupId.value = null
      audienceMode.value = 'DEFAULT'
    }
  } catch (error) { handleApiError(error, 'confirmable sender options') }
}
function audienceRequest(): ConfirmableRecipientPreviewRequest | null {
  if (audienceMode.value === 'GROUP') return selectedGroupId.value ? { recipientGroupId: selectedGroupId.value } : null
  if (audienceMode.value === 'TARGETS') return selectedTargets.value.length ? { targets: selectedTargets.value } : null
  return {}
}
async function loadPreview() {
  const current = ++previewSequence
  const audience = audienceRequest()
  estimatedCount.value = null
  if (!audience || !props.scopeId) return
  previewing.value = true
  try {
    const response = await previewRecipients(props.scopeType, props.scopeId, audience)
    if (current === previewSequence) estimatedCount.value = response.data.estimatedRecipientCount
  } catch (error) {
    if (current === previewSequence) handleApiError(error, 'confirmable recipient preview')
  } finally { if (current === previewSequence) previewing.value = false }
}
async function onSend() {
  if (!title.value.trim()) { toast.add({ severity: 'warn', summary: t('confirmable.title_required'), life: 3000 }); return }
  const audience = audienceRequest()
  if (!audience) { toast.add({ severity: 'warn', summary: t('confirmable.targets_required'), life: 3000 }); return }
  if (estimatedCount.value === null || estimatedCount.value === 0) { toast.add({ severity: 'warn', summary: t('confirmable.preview_required'), life: 3000 }); return }
  sending.value = true
  try {
    const draftId = await settleForSend()
    const request: CreateConfirmableNotificationRequest = { title: title.value.trim(), priority: priority.value, unconfirmedVisibility: unconfirmedVisibility.value, ...audience }
    if (body.value.trim()) request.body = body.value.trim()
    if (deadlineAt.value) request.deadlineAt = deadlineAt.value.toISOString()
    if (selectedTemplateId.value !== null) request.templateId = selectedTemplateId.value
    if (draftId) request.draftId = draftId
    const response = await sendNotification(props.scopeType, props.scopeId, request)
    acceptedCount.value = response.data.estimatedRecipientCount
    localStorage.setItem('confirmable.lastUnconfirmedVisibility', unconfirmedVisibility.value)
    await afterSent()
    toast.add({ severity: 'success', summary: t('confirmable.accepted'), detail: t('confirmable.accepted_count', { count: response.data.estimatedRecipientCount }), life: 4000 })
    emit('sent')
  } catch (error) {
    handleApiError(error, 'confirmable notification send')
    resumeAfterFailedSend()
  } finally { sending.value = false }
}
watch([audienceMode, selectedGroupId, selectedTargets, () => props.scopeId], loadPreview, { deep: true })
watch(() => props.groupsVersion, loadOptions)
watch(() => [props.scopeType, props.scopeId], resetDraftScope)
onMounted(async () => {
  const stored = localStorage.getItem('confirmable.lastUnconfirmedVisibility')
  if (stored === 'HIDDEN' || stored === 'CREATOR_AND_ADMIN' || stored === 'ALL_MEMBERS') unconfirmedVisibility.value = stored
  void initDrafts()
  await loadOptions()
  await loadPreview()
})
</script>

<template>
  <SectionCard :title="$t('confirmable.send')">
    <template #header-actions><Button icon="pi pi-question-circle" text rounded :aria-label="$t('button.help')" @click="showHelp = true" /></template>
    <div class="flex flex-col gap-4">
      <section class="flex flex-col gap-2" :aria-label="$t('confirmable.draft.list_title')">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <h3 class="text-sm font-semibold">{{ $t('confirmable.draft.list_title') }}<span class="ml-2 font-normal text-surface-500" data-testid="confirmable-draft-count">{{ $t('confirmable.draft.count', { count: drafts.length, limit: CONFIRMABLE_DRAFT_LIMIT }) }}</span></h3>
          <Button :label="$t('confirmable.draft.new')" icon="pi pi-plus" size="small" severity="secondary" class="min-h-11" data-testid="confirmable-draft-new" @click="newDraft" />
        </div>
        <p v-if="drafts.length === 0" class="text-sm text-surface-500" data-testid="confirmable-draft-empty">{{ $t('confirmable.draft.empty') }}</p>
        <ul v-else class="flex flex-col gap-1" data-testid="confirmable-draft-list">
          <li v-for="draft in drafts" :key="draft.id" class="flex items-stretch gap-1 rounded-border border" :class="draft.id === currentDraftId ? 'border-primary' : 'border-surface-200'" data-testid="confirmable-draft-row" :data-draft-id="draft.id">
            <button type="button" class="flex min-h-11 min-w-0 flex-1 flex-col items-start gap-0.5 px-3 py-2 text-left" :aria-label="$t('confirmable.draft.open')" :aria-describedby="`confirmable-draft-title-${draft.id}`" :aria-current="draft.id === currentDraftId ? 'true' : undefined" data-testid="confirmable-draft-open" @click="openDraft(draft.id as string)">
              <span :id="`confirmable-draft-title-${draft.id}`" class="w-full truncate text-sm font-medium">{{ draft.title || $t('confirmable.draft.untitled') }}</span>
              <span class="w-full truncate text-xs text-surface-500">{{ draft.bodyExcerpt }}</span>
              <time class="text-xs text-surface-400" :datetime="draft.updatedAt" data-testid="confirmable-draft-updated-at">{{ formatUpdatedAt(draft.updatedAt) }}</time>
            </button>
            <Button icon="pi pi-trash" text rounded severity="danger" class="min-h-11 min-w-11 self-center" :aria-label="$t('confirmable.draft.delete')" :aria-describedby="`confirmable-draft-title-${draft.id}`" data-testid="confirmable-draft-delete" @click="requestDeleteDraft(draft.id as string)" />
          </li>
        </ul>
      </section>
      <Message v-if="acceptedCount !== null" severity="success" :closable="false">{{ $t('confirmable.accepted_count', { count: acceptedCount }) }}</Message>
      <div class="flex flex-col gap-1"><label>{{ $t('confirmable.template') }}</label><Select v-model="selectedTemplateId" :options="templateOptions" option-label="label" option-value="value" @change="applyTemplate(selectedTemplateId)" /></div>
      <div class="flex flex-col gap-1">
        <label>{{ $t('confirmable.audience') }}</label>
        <Select v-model="audienceMode" data-testid="sender-audience-mode-select" :options="audienceModeOptions" option-label="label" option-value="value" />
        <small v-if="audienceMode === 'DEFAULT'">{{ $t('confirmable.current_scope_help') }}</small>
        <ConfirmableTargetPicker v-if="audienceMode === 'TARGETS'" v-model="selectedTargets" :scope-type="scopeType" :scope-id="scopeId" :scope-slug="scopeSlug" />
        <Select v-if="audienceMode === 'GROUP'" v-model="selectedGroupId" data-testid="sender-group-select" :options="groupOptions.filter(option => option.value !== null)" option-label="label" option-value="value" :placeholder="$t('confirmable.select_group')" />
      </div>
      <Message v-if="previewing" severity="info" :closable="false">{{ $t('confirmable.preview_loading') }}</Message>
      <Message v-else-if="estimatedCount !== null" :severity="estimatedCount ? 'info' : 'warn'" :closable="false">{{ $t('confirmable.preview_count', { count: estimatedCount }) }}</Message>
      <!-- 保存できない・競合の案内は入力欄の直前に置く（下書き一覧が長いと、一覧の上では入力中に見えない） -->
      <div v-if="draftLimitReached || draftConflict" class="flex flex-col gap-2">
        <Message v-if="draftLimitReached" severity="warn" :closable="false" data-testid="confirmable-draft-limit">{{ $t('confirmable.draft.limit_reached', { limit: CONFIRMABLE_DRAFT_LIMIT }) }}</Message>
        <div v-if="draftConflict" class="flex flex-col gap-2" data-testid="confirmable-draft-conflict">
          <Message severity="warn" :closable="false">{{ $t('confirmable.draft.conflict') }}</Message>
          <div class="flex flex-wrap gap-2">
            <Button :label="$t('confirmable.draft.reload_latest')" severity="secondary" class="min-h-11" data-testid="confirmable-draft-reload-latest" @click="reloadLatestDraft" />
            <Button :label="$t('confirmable.draft.overwrite')" class="min-h-11" data-testid="confirmable-draft-overwrite" @click="overwriteDraft" />
          </div>
        </div>
      </div>
      <div ref="draftFormAnchor" class="flex scroll-mt-20 flex-col gap-1"><label>{{ $t('confirmable.message_title') }}</label><InputText v-model="title" /></div>
      <div class="flex flex-col gap-1"><label>{{ $t('confirmable.message_body') }}</label><Textarea v-model="body" rows="4" /></div>
      <div class="grid gap-4 md:grid-cols-2"><div class="flex flex-col gap-1"><label>{{ $t('confirmable.priority_label') }}</label><Select v-model="priority" :options="priorityOptions" option-label="label" option-value="value" /></div><div class="flex flex-col gap-1"><label>{{ $t('confirmable.deadline') }}</label><DatePicker v-model="deadlineAt" show-time hour-format="24" /></div></div>
      <div class="flex flex-col gap-1"><label>{{ $t('confirmable.unconfirmed_visibility.label') }}</label><Select v-model="unconfirmedVisibility" data-testid="sender-visibility-select" :options="visibilityOptions" option-label="label" option-value="value" /></div>
      <div><Button :label="$t('confirmable.send')" icon="pi pi-send" :loading="sending" :disabled="previewing || estimatedCount === null || estimatedCount === 0" @click="onSend" /></div>
    </div>
  </SectionCard>
  <Dialog v-model:visible="showHelp" modal :header="$t('confirmable.help.title')" :style="{ width: 'min(34rem, 95vw)' }"><p>{{ $t('confirmable.help.body') }}</p></Dialog>
</template>
