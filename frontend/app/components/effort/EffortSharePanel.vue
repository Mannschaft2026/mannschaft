<script setup lang="ts">
import type {
  EffortOwnerRecord,
  EffortSharePreview,
  EffortDefinitionToken,
} from '~/composables/useEffortShareApi'
import type { EffortSnapshotField } from '~/utils/effortFields'
import type { ActivityTemplate } from '~/types/activity'
import {
  buildDefaultMappingRows,
  canRetryEffortShare,
  canRevokeEffortShare,
  compatibleSourceFields,
  effortShareErrorKey,
  effortShareStatus,
  toMappingBody,
  type EffortMappingRow,
} from '~/utils/effortShare'
import { extractApiFieldErrors } from '~/utils/apiFieldErrors'
import { getLoadErrorHttpStatus } from '~/utils/loadError'

/**
 * F06.4.1 チーム共有ステップ（親設計 §2.1）。共有先チーム → テンプレート → 共有項目 → プレビュー → 明示共有を
 * 1 画面内で段階表示する。共有 API は preview 後の「この内容を共有」まで呼ばない。
 *
 * 原本の保存は呼び出し側（saveBeforePreview）の責務。保存済みなら保存せずに最新の原本を返す。
 */
interface MyTeam { id: number; slug: string; name?: string }

const props = defineProps<{
  /** 保存済みの原本（未保存なら null）。共有状態の表示・再試行・取消に使う。 */
  record: EffortOwnerRecord | null
  /** プレビュー前に原本を保存する。成功したら最新の原本、失敗したら null（失敗の表示は呼び出し側）。 */
  saveBeforePreview: () => Promise<EffortOwnerRecord | null>
  dirty?: boolean
}>()

const emit = defineEmits<{ updated: [record: EffortOwnerRecord] }>()

const { t, te } = useI18n()
const api = useApi()
const shareApi = useEffortShareApi()
const recordApi = useEffortRecordApi()
const { getTemplates } = useActivityApi()
const { handleApiError } = useErrorHandler()
const { success } = useNotification()
const confirm = useConfirm()

function statusOf(e: unknown): number | undefined { return getLoadErrorHttpStatus(e) }

const teams = ref<MyTeam[]>([])
const teamsLoading = ref(true)
const teamsError = ref(false)
const templates = ref<ActivityTemplate[]>([])
const templatesLoading = ref(false)
const teamId = ref<number | null>(null)
const templateId = ref<number | null>(null)
const snapshot = ref<EffortDefinitionToken | null>(null)
const rows = ref<EffortMappingRow[]>([])
const selected = ref<string[]>([])
const preview = ref<EffortSharePreview | null>(null)
const busy = ref(false)
const errorMsg = ref('')
const reshare = ref(false)
let shareKey: { key: string; signature: string } | null = null
let actionKey: { name: string; key: string } | null = null

const share = computed(() => props.record?.share ?? null)
const status = computed(() => effortShareStatus(share.value))
/** 共有設定を編集できるのは、共有が無い・取消後に「共有し直す」を押したときだけ。 */
const showConfig = computed(() => !share.value || (share.value.state === 'REVOKED' && reshare.value))
const targetFields = computed<EffortSnapshotField[]>(() => snapshot.value?.definition?.fields ?? [])
const sourceFields = computed<EffortSnapshotField[]>(() => props.record?.definition?.fields ?? [])
const teamName = computed(() => {
  const tm = teams.value.find((x) => x.id === teamId.value)
  return tm?.name ?? tm?.slug ?? ''
})

function fieldLabel(f: EffortSnapshotField): string {
  if (f.labelKey && te(f.labelKey)) return t(f.labelKey)
  return f.label ?? f.fieldKey ?? ''
}

function rowOf(key: string | undefined): EffortMappingRow | undefined {
  return rows.value.find((r) => r.targetFieldKey === key)
}

function sourceOptions(target: EffortSnapshotField) {
  return [
    { value: null as string | null, label: t('effort.share.sourceNone') },
    ...compatibleSourceFields(target, sourceFields.value).map((s) => ({ value: s.fieldKey ?? null, label: fieldLabel(s) })),
  ]
}

function hasSharedState(): boolean {
  return selected.value.length > 0 || preview.value !== null
}

function resetFromTeam() {
  templates.value = []
  templateId.value = null
  resetFromTemplate()
}

function resetFromTemplate() {
  snapshot.value = null
  rows.value = []
  selected.value = []
  preview.value = null
  errorMsg.value = ''
  shareKey = null
}

async function loadTeams() {
  teamsLoading.value = true
  teamsError.value = false
  try {
    teams.value = (await api<{ data: MyTeam[] }>('/api/v1/me/teams')).data ?? []
    if (teams.value.length === 1 && teamId.value === null) {
      teamId.value = teams.value[0]!.id
      await loadTemplates()
    }
  } catch {
    teamsError.value = true
  } finally {
    teamsLoading.value = false
  }
}

async function loadTemplates() {
  if (teamId.value === null) return
  templatesLoading.value = true
  try {
    templates.value = (await getTemplates('TEAM', String(teamId.value))).data ?? []
  } catch (e) {
    handleApiError(e)
  } finally {
    templatesLoading.value = false
  }
}

let previousTeamId: number | null = null
/** チーム切替でテンプレートと共有項目をクリアする。入力済みなら破棄してよいか ConfirmDialog で確認する。 */
function onTeamChange(next: number | null) {
  const apply = () => {
    teamId.value = next
    previousTeamId = next
    resetFromTeam()
    void loadTemplates()
  }
  if (!hasSharedState()) { apply(); return }
  teamId.value = previousTeamId
  confirm.require({
    header: t('effort.discard.header'),
    message: t('effort.discard.message'),
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: t('effort.discard.accept'),
    rejectLabel: t('effort.discard.reject'),
    accept: apply,
  })
}

async function onTemplateChange(next: number | null) {
  templateId.value = next
  resetFromTemplate()
  if (next === null || teamId.value === null) return
  busy.value = true
  try {
    snapshot.value = (await shareApi.createDefinitionSnapshot(teamId.value, next)).data
    rows.value = buildDefaultMappingRows(snapshot.value.definition?.fields ?? [], sourceFields.value)
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}

function showError(e: unknown) {
  const key = effortShareErrorKey(e)
  if (key) {
    errorMsg.value = t(key)
    return
  }
  if (statusOf(e) === 400) {
    const errs = extractApiFieldErrors(e)
    if (errs.length > 0) {
      errorMsg.value = errs.map((x) => x.message).join(' / ')
      return
    }
  }
  handleApiError(e)
}

function validateSelection(): boolean {
  if (selected.value.length === 0) {
    errorMsg.value = t('effort.share.needFields')
    return false
  }
  return true
}

async function runPreview() {
  errorMsg.value = ''
  if (teamId.value === null || !snapshot.value?.definitionToken) return
  if (!validateSelection()) return
  busy.value = true
  try {
    const saved = await props.saveBeforePreview()
    if (!saved?.id) return
    preview.value = (await shareApi.preview(saved.id, {
      version: saved.version ?? 0,
      teamId: teamId.value,
      definitionToken: snapshot.value.definitionToken,
      mappings: toMappingBody(rows.value),
      selectedFieldKeys: selected.value,
    })).data
  } catch (e) {
    preview.value = null
    showError(e)
  } finally {
    busy.value = false
  }
}

function backToInput() {
  preview.value = null
  errorMsg.value = ''
}

async function confirmShare() {
  const p = preview.value
  const rec = props.record
  if (!p?.previewToken || !rec?.id) return
  const body = {
    version: p.recordVersion ?? rec.version ?? 0,
    previewToken: p.previewToken,
    mappings: toMappingBody(rows.value),
    selectedFieldKeys: selected.value,
  }
  const signature = JSON.stringify(body)
  if (!shareKey || shareKey.signature !== signature) shareKey = { key: crypto.randomUUID(), signature }
  busy.value = true
  errorMsg.value = ''
  try {
    const res = await shareApi.share(rec.id, body, shareKey.key)
    shareKey = null
    preview.value = null
    reshare.value = false
    emit('updated', res.data)
    success(t('effort.share.done'))
    startPolling()
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}

function keyFor(name: string): string {
  if (!actionKey || actionKey.name !== name) actionKey = { name, key: crypto.randomUUID() }
  return actionKey.key
}

async function retryShare() {
  const rec = props.record
  const sh = share.value
  if (!rec?.id || !sh) return
  busy.value = true
  errorMsg.value = ''
  try {
    const res = await shareApi.retry(rec.id, rec.version ?? 0, sh.version ?? 0, keyFor(`retry:${sh.version}`))
    actionKey = null
    emit('updated', res.data)
    startPolling()
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}

function askRevoke() {
  confirm.require({
    header: t('effort.share.revokeConfirm.header'),
    message: t('effort.share.revokeConfirm.message'),
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: t('effort.share.revokeConfirm.accept'),
    rejectLabel: t('effort.share.revokeConfirm.reject'),
    acceptClass: 'p-button-danger',
    accept: revokeShare,
  })
}

async function revokeShare() {
  const rec = props.record
  const sh = share.value
  if (!rec?.id || !sh) return
  busy.value = true
  errorMsg.value = ''
  try {
    const res = await shareApi.revoke(rec.id, rec.version ?? 0, sh.version ?? 0, keyFor(`revoke:${sh.version}`))
    actionKey = null
    stopPolling()
    emit('updated', res.data)
    success(t('effort.share.revoked'))
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}

// ---- 状態の確認（共有 202 後は最大 5 秒間隔・画面が見えている間だけ・30 秒で自動取得を止める。API 契約 §4.1）
const polling = ref(false)
let timer: ReturnType<typeof setInterval> | null = null
let pollStarted = 0

async function refreshStatus() {
  const rec = props.record
  if (!rec?.id) return
  try {
    const res = await recordApi.get(rec.id)
    emit('updated', res.data)
    if (res.data.share?.state !== 'PENDING') stopPolling()
  } catch (e) {
    stopPolling()
    showError(e)
  }
}

function stopPolling() {
  if (timer) clearInterval(timer)
  timer = null
  polling.value = false
}

function startPolling() {
  stopPolling()
  pollStarted = Date.now()
  polling.value = true
  timer = setInterval(() => {
    if (Date.now() - pollStarted > 30_000) { stopPolling(); return }
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
    void refreshStatus()
  }, 5000)
}

function startReshare() {
  reshare.value = true
  resetFromTeam()
  if (teams.value.length === 1) { teamId.value = teams.value[0]!.id; void loadTemplates() }
}

watch(() => props.record?.id, () => { reshare.value = false; stopPolling() })
onMounted(loadTeams)
onBeforeUnmount(stopPolling)
</script>

<template>
  <SectionCard :title="t('effort.shareSave')" data-testid="effort-share-panel">
    <p class="mb-3 text-sm text-surface-500">{{ t('effort.share.saveFirst') }}</p>

    <!-- 共有状態（保存と共有は別結果として表示する） -->
    <div v-if="status" class="mb-3 flex flex-col gap-2" data-testid="effort-share-status">
      <div class="flex flex-wrap items-center gap-2">
        <span class="rounded-full bg-surface-100 px-3 py-1 text-sm font-medium dark:bg-surface-700" role="status" :data-testid="`effort-share-state-${share?.state}`">
          {{ t(status.key) }}
        </span>
        <span v-if="status.noteKey" class="text-sm text-surface-500" data-testid="effort-share-status-note">{{ t(status.noteKey) }}</span>
      </div>
      <p v-if="share?.state === 'PENDING'" class="text-sm text-surface-500">{{ t('effort.share.pendingHint') }}</p>
      <p v-if="share?.state === 'FAILED'" class="text-sm text-red-600" role="alert">{{ t('effort.error.shareFailed') }}</p>
      <div class="flex flex-wrap gap-2">
        <Button v-if="canRetryEffortShare(share)" :label="t('effort.share.retry')" icon="pi pi-refresh" class="min-h-11" :loading="busy" data-testid="effort-share-retry" @click="retryShare" />
        <Button v-if="share?.state === 'PENDING' && !polling" :label="t('effort.share.checking')" severity="secondary" outlined class="min-h-11" data-testid="effort-share-check" @click="refreshStatus" />
        <Button v-if="canRevokeEffortShare(share)" :label="t('effort.share.revoke')" severity="danger" outlined class="min-h-11" :loading="busy" data-testid="effort-share-revoke" @click="askRevoke" />
        <Button v-if="share?.state === 'REVOKED' && !reshare" :label="t('effort.shareAgain')" class="min-h-11" data-testid="effort-share-again" @click="startReshare" />
      </div>
    </div>

    <template v-if="showConfig">
      <PageLoading v-if="teamsLoading" size="32px" />
      <div v-else-if="teamsError" class="flex flex-col items-start gap-2" role="alert" data-testid="effort-share-teams-error">
        <p class="text-sm text-red-600">{{ t('effort.share.loadError') }}</p>
        <Button :label="t('effort.templateSettings.retry')" icon="pi pi-refresh" outlined class="min-h-11" @click="loadTeams" />
      </div>
      <p v-else-if="teams.length === 0" class="text-sm text-surface-500" data-testid="effort-share-no-team">{{ t('effort.share.noTeam') }}</p>

      <div v-else class="flex flex-col gap-4">
        <div>
          <label for="effort-share-team" class="mb-1 inline-flex min-h-11 items-center text-sm font-medium">{{ t('effort.team') }}</label>
          <Select
            :model-value="teamId"
            input-id="effort-share-team"
            :options="teams.map((x) => ({ value: x.id, label: x.name ?? x.slug }))"
            option-label="label"
            option-value="value"
            class="min-h-11 w-full"
            data-testid="effort-share-team"
            @update:model-value="onTeamChange"
          />
        </div>

        <div v-if="teamId !== null">
          <label for="effort-share-template" class="mb-1 inline-flex min-h-11 items-center text-sm font-medium">{{ t('effort.share.template') }}</label>
          <PageLoading v-if="templatesLoading" size="24px" />
          <p v-else-if="templates.length === 0" class="text-sm text-surface-500" data-testid="effort-share-no-template">{{ t('effort.share.noTemplate') }}</p>
          <Select
            v-else
            :model-value="templateId"
            input-id="effort-share-template"
            :options="templates.map((x) => ({ value: x.id, label: x.name }))"
            option-label="label"
            option-value="value"
            class="min-h-11 w-full"
            data-testid="effort-share-template"
            @update:model-value="onTemplateChange"
          />
        </div>

        <fieldset v-if="snapshot && !preview" class="flex flex-col gap-3" data-testid="effort-share-fields">
          <legend class="mb-1 text-sm font-medium">{{ t('effort.sharedFields') }}</legend>
          <div
            v-for="f in targetFields"
            :key="f.fieldKey"
            class="flex flex-col gap-2 rounded-lg border border-surface-200 p-3 dark:border-surface-700"
          >
            <label class="flex min-h-11 items-center gap-2 text-sm font-medium">
              <Checkbox v-model="selected" :value="f.fieldKey" :input-id="`effort-share-sel-${f.fieldKey}`" :data-testid="`effort-share-select-${f.fieldKey}`" />
              <span class="break-words">{{ fieldLabel(f) }}</span>
            </label>
            <div v-if="rowOf(f.fieldKey)" class="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <label class="flex min-w-0 flex-col gap-1 text-xs text-surface-500">
                {{ t('effort.share.source') }}
                <Select
                  v-model="rowOf(f.fieldKey)!.sourceFieldKey"
                  :options="sourceOptions(f)"
                  option-label="label"
                  option-value="value"
                  class="min-h-11 w-full text-base"
                  :data-testid="`effort-share-source-${f.fieldKey}`"
                />
              </label>
              <label v-if="rowOf(f.fieldKey)!.sourceFieldKey === null" class="flex min-w-0 flex-col gap-1 text-xs text-surface-500">
                {{ t('effort.share.override') }}
                <InputText v-model="rowOf(f.fieldKey)!.valueOverride" class="min-h-11 w-full text-base" :data-testid="`effort-share-override-${f.fieldKey}`" />
              </label>
            </div>
          </div>
        </fieldset>

        <!-- プレビュー（私的メモは含まない） -->
        <div v-if="preview" class="flex flex-col gap-3" data-testid="effort-share-preview">
          <h3 class="text-sm font-semibold">{{ t('effort.shareTarget', { team: teamName }) }}</h3>
          <dl class="grid grid-cols-1 gap-2 rounded-lg border border-surface-200 p-3 dark:border-surface-700 sm:grid-cols-2">
            <template v-for="f in preview.projection?.fields ?? []" :key="f.fieldKey">
              <dt class="text-xs text-surface-500">{{ f.label }}</dt>
              <dd class="break-words text-sm">{{ f.displayValue ?? f.value ?? '' }}</dd>
            </template>
          </dl>
          <p class="text-xs text-surface-500">{{ t('effort.share.previewNote') }}</p>
        </div>

        <p v-if="errorMsg" class="text-sm text-red-600" role="alert" data-testid="effort-share-error">{{ errorMsg }}</p>

        <div v-if="snapshot" class="flex flex-wrap gap-2">
          <template v-if="!preview">
            <Button
              :label="dirty || !record ? t('effort.sharePreviewSave') : t('effort.preview')"
              :loading="busy"
              class="min-h-11"
              data-testid="effort-share-preview-btn"
              @click="runPreview"
            />
          </template>
          <template v-else>
            <Button :label="t('effort.backToInput')" severity="secondary" outlined class="min-h-11" data-testid="effort-share-back" @click="backToInput" />
            <Button :label="t('effort.shareConfirm')" :loading="busy" class="min-h-11" data-testid="effort-share-confirm" @click="confirmShare" />
          </template>
        </div>
      </div>
    </template>
    <p v-else-if="errorMsg" class="text-sm text-red-600" role="alert" data-testid="effort-share-error">{{ errorMsg }}</p>
  </SectionCard>
</template>
