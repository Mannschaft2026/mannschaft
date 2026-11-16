<script setup lang="ts">
/**
 * F06.4.1 個人の頑張り記録（PR-1: 個人の記録・集計）。`/my/effort-records`。
 * 導線（メニュー・ダッシュボード）は PR-4 まで出さない。URL 直打ちでのみ到達する。
 */
import type { ActivityFieldValue } from '~/utils/activityFields'
import { toYmd } from '~/utils/activityFields'
import {
  buildEffortFieldValues,
  extractEffortFieldErrors,
  hasEffortContent,
  parseTypedYmd,
  fromEffortWireValue,
  reconcileUpdateAfter409,
  restoreEffortInputs,
  toEffortFormFields,
  toEffortUnitLabel,
  toInputFields,
  type EffortSnapshotField,
} from '~/utils/effortFields'
import { createLatestGuard } from '~/utils/latestRequest'
import { mergeEffortRecords } from '~/utils/effortRecordList'
import {
  listEffortChartTargets,
  resolveEffortPeriod,
  type EffortChartTarget,
  type EffortPeriodKind,
} from '~/utils/effortPeriod'
import type {
  EffortDefinitionSnapshotResponse,
  EffortRecordResponse,
  EffortStatsResult,
} from '~/composables/useEffortRecordApi'

definePageMeta({ middleware: 'auth' })

const { t, te } = useI18n()
const api = useEffortRecordApi()
const { handleApiError } = useErrorHandler()
const { success, warn } = useNotification()
const confirm = useConfirm()
const { userTimezone } = useDatetime()

type Tab = 'record' | 'review'
const tab = ref<Tab>('record')

// ---------------------------------------------------------------- 共通
function statusOf(e: unknown): number | undefined {
  const x = e as { response?: { status?: number }; statusCode?: number; status?: number }
  return x?.response?.status ?? x?.statusCode ?? x?.status
}

function fieldLabel(f: EffortSnapshotField): string {
  if (f.labelKey && te(f.labelKey)) return t(f.labelKey)
  return f.label ?? f.fieldKey ?? ''
}

/** 単位コードの i18n 解決（未定義コードは null → 呼び出し側でコードのまま表示） */
function unitName(code: string): string | null {
  const key = `effort.unit.${code}`
  return te(key) ? t(key) : null
}

function presetName(d: EffortDefinitionSnapshotResponse): string {
  const key = d.presetCode ? `effort.preset.${d.presetCode.replace(/_V\d+$/, '').toLowerCase()}` : ''
  if (key && te(key)) return t(key)
  return d.templateName ?? ''
}

// ---------------------------------------------------------------- プリセット
const presets = ref<EffortDefinitionSnapshotResponse[]>([])
const presetsLoading = ref(true)
const presetsError = ref<unknown>(null)
const selectedDefinitionUuid = ref<string | null>(null)
const reviewDefinitionUuid = ref<string | null>(null)

async function loadPresets() {
  presetsLoading.value = true
  presetsError.value = null
  try {
    presets.value = (await api.listPresets()).data
    const first = presets.value[0]?.definitionUuid ?? null
    if (!selectedDefinitionUuid.value && first) {
      selectedDefinitionUuid.value = first
      resetInputs()
    }
    if (!reviewDefinitionUuid.value && first) reviewDefinitionUuid.value = first
  } catch (e) {
    presetsError.value = e
  } finally {
    presetsLoading.value = false
  }
}

// ---------------------------------------------------------------- 記録フォーム
/** 編集中の原本（null = 新規）。編集時は原本の snapshot を使う（廃止・改名後も維持）。 */
const editing = ref<EffortRecordResponse | null>(null)
const recordedOn = ref<Date>(new Date())
const inputs = ref<Record<string, ActivityFieldValue>>({})
const privateNote = ref('')
const saving = ref(false)
const formError = ref('')
const savedNotice = ref('')
const conflictNotice = ref(false)
/** 記録日の入力欄に利用者が打った文字列。DatePicker は maxDate 等で黙って別の日へ置換するため、打った値そのものを検証する。 */
const recordedOnTyped = ref<string | null>(null)

const activeDefinition = computed<EffortDefinitionSnapshotResponse | null>(() => {
  if (editing.value?.definition) return editing.value.definition
  return presets.value.find((p) => p.definitionUuid === selectedDefinitionUuid.value) ?? null
})
const inputFields = computed(() => toInputFields(activeDefinition.value?.fields))
const formFields = computed(() => toEffortFormFields(activeDefinition.value?.fields, fieldLabel, unitName))

function resetInputs() {
  inputs.value = restoreEffortInputs(inputFields.value, [])
  privateNote.value = ''
}

function clearNotices() {
  formError.value = ''
  savedNotice.value = ''
  conflictNotice.value = false
}

// 新規作成の Idempotency-Key: 同じ内容の再送は同じキー、内容が変われば新しいキー（_api.md §4.1）。
let pendingCreateKey: { key: string; signature: string } | null = null
function createKeyFor(signature: string): string {
  if (!pendingCreateKey || pendingCreateKey.signature !== signature) {
    pendingCreateKey = { key: crypto.randomUUID(), signature }
  }
  return pendingCreateKey.key
}

function onPresetChange() {
  editing.value = null
  recordedOn.value = new Date()
  resetInputs()
  clearNotices()
  pendingCreateKey = null
  recordedOnTyped.value = null
}

function onRecordedOnInput(ev: Event) {
  recordedOnTyped.value = (ev.target as HTMLInputElement | null)?.value ?? null
}

function currentBody() {
  return {
    recordedOn: toYmd(recordedOn.value as Date),
    values: buildEffortFieldValues(inputFields.value, inputs.value),
    privateNote: privateNote.value.trim() === '' ? null : privateNote.value,
  }
}

function validate(): boolean {
  const typed = recordedOnTyped.value
  if (typed !== null) {
    if (typed.trim() === '') {
      formError.value = t('effort.error.recordedOnRequired')
      return false
    }
    const parsed = parseTypedYmd(typed)
    const today = new Date()
    today.setHours(23, 59, 59, 999)
    if (!parsed || parsed > today || parsed.getFullYear() < 1900) {
      formError.value = t('effort.error.recordedOnInvalid')
      return false
    }
  }
  if (!(recordedOn.value instanceof Date) || Number.isNaN(recordedOn.value.getTime())) {
    formError.value = t('effort.error.recordedOnRequired')
    return false
  }
  const wire = buildEffortFieldValues(inputFields.value, inputs.value)
  for (const f of formFields.value) {
    if (f.isRequired && wire.find((v) => v.fieldKey === f.fieldKey)?.value === null) {
      formError.value = t('effort.error.required', { field: f.fieldLabel })
      return false
    }
  }
  if (!hasEffortContent(wire)) {
    formError.value = t('effort.error.noContent')
    return false
  }
  return true
}

/** BE の 400 fieldErrors をフォームへ表示する。表示できたら true（トーストは出さない）。 */
function showFieldErrors(e: unknown): boolean {
  if (statusOf(e) !== 400) return false
  const errs = extractEffortFieldErrors(e)
  if (errs.length === 0) return false
  const labels = new Map(formFields.value.map((f) => [`values.${f.fieldKey}`, f.fieldLabel]))
  formError.value = errs
    .map((fe) => {
      const label = labels.get(fe.field) ?? (fe.field === 'recordedOn' ? t('effort.recordedOn') : fe.field === 'privateNote' ? t('effort.privateNote') : '')
      return label ? `${label}: ${fe.message}` : fe.message
    })
    .join(' / ')
  return true
}

function adopt(record: EffortRecordResponse) {
  editing.value = record
  const d = fromEffortWireValue('DATE', record.recordedOn)
  recordedOn.value = d instanceof Date ? d : new Date()
  recordedOnTyped.value = null
  inputs.value = restoreEffortInputs(toInputFields(record.definition?.fields), record.values)
  privateNote.value = record.privateNote ?? ''
}

async function save() {
  clearNotices()
  const def = activeDefinition.value
  if (!validate() || !def?.definitionUuid) return
  saving.value = true
  try {
    const body = currentBody()
    if (!editing.value) {
      const payload = {
        ...body,
        recordTimezone: userTimezone.value,
        definitionUuid: def.definitionUuid,
        definitionRevision: def.revision ?? 1,
      }
      const res = await api.create(payload, createKeyFor(JSON.stringify(payload)))
      pendingCreateKey = null
      adopt(res.data)
      savedNotice.value = t('effort.status.private')
      success(t('effort.saved'))
    } else {
      await updateExisting(editing.value, body)
    }
    await loadRecords(true)
  } catch (e) {
    if (!showFieldErrors(e)) handleApiError(e)
  } finally {
    saving.value = false
  }
}

/** 原本 PUT。応答消失→再送の 409 は GET で照合する（_api.md §4.1 / AC-65）。 */
async function updateExisting(base: EffortRecordResponse, body: ReturnType<typeof currentBody>) {
  const id = base.id as string
  const sent = { version: base.version ?? 0, ...body }
  try {
    const res = await api.update(id, sent)
    adopt(res.data)
    savedNotice.value = t('effort.status.private')
    success(t('effort.saved'))
  } catch (e) {
    if (statusOf(e) !== 409) throw e
    const current = (await api.get(id)).data
    const verdict = reconcileUpdateAfter409(toInputFields(base.definition?.fields), sent, current)
    adopt(current)
    if (verdict === 'SAVED') {
      savedNotice.value = t('effort.status.private')
      success(t('effort.savedReconciled'))
    } else {
      // 別の編集が入っている。画面は最新の保存内容へ置き換え、入力が上書きされたことを明示する。
      conflictNotice.value = true
      warn(t('effort.error.conflict'))
    }
  }
}

function startEdit(r: EffortRecordResponse) {
  clearNotices()
  adopt(r)
  tab.value = 'record'
}

function newRecord() {
  editing.value = null
  clearNotices()
  recordedOn.value = new Date()
  recordedOnTyped.value = null
  resetInputs()
  pendingCreateKey = null
}

function askDelete(r: EffortRecordResponse) {
  confirm.require({
    header: t('effort.delete.header'),
    message: t('effort.delete.message'),
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: t('effort.delete.reject'),
    acceptLabel: t('effort.delete.accept'),
    acceptClass: 'p-button-danger',
    accept: async () => {
      try {
        await api.remove(r.id as string, r.version ?? 0, crypto.randomUUID())
        if (editing.value?.id === r.id) newRecord()
        success(t('effort.deleted'))
        await loadRecords(true)
      } catch (e) {
        handleApiError(e)
      }
    },
  })
}

// ---------------------------------------------------------------- 一覧
const records = ref<EffortRecordResponse[]>([])
const recordsLoading = ref(true)
const recordsError = ref<unknown>(null)
const nextCursor = ref<string | null>(null)

async function loadRecords(reset: boolean) {
  recordsLoading.value = true
  recordsError.value = null
  try {
    const page = await api.list({ limit: 20, cursor: reset ? undefined : (nextCursor.value ?? undefined) })
    records.value = reset ? mergeEffortRecords([], page.data ?? []) : mergeEffortRecords(records.value, page.data ?? [])
    nextCursor.value = page.meta?.hasNext ? (page.meta.nextCursor ?? null) : null
  } catch (e) {
    recordsError.value = e
  } finally {
    recordsLoading.value = false
  }
}

function summaryOf(r: EffortRecordResponse): string {
  const defs = new Map((r.definition?.fields ?? []).map((f) => [f.fieldKey, f]))
  return (r.values ?? [])
    .filter((v) => v.value !== null && v.value !== undefined)
    .map((v) => {
      const f = defs.get(v.fieldKey)
      const unitLabel = toEffortUnitLabel(f?.unit, unitName)
      const unit = unitLabel ? ` ${unitLabel}` : ''
      return `${f ? fieldLabel(f) : v.fieldKey}: ${v.value}${unit}`
    })
    .join(' / ')
}

// ---------------------------------------------------------------- 振り返り（集計）
const periodKind = ref<EffortPeriodKind>('week')
const periodOffset = ref(0)
const customFrom = ref<Date | null>(null)
const customTo = ref<Date | null>(null)
const chartTargetIndex = ref(0)
const stats = ref<EffortStatsResult | null>(null)
const statsLoading = ref(false)
const statsError = ref<unknown>(null)
const rangeErrorKey = ref<string | null>(null)

const reviewDefinition = computed(() => presets.value.find((p) => p.definitionUuid === reviewDefinitionUuid.value) ?? null)
const chartTargets = computed<EffortChartTarget[]>(() => (reviewDefinition.value ? listEffortChartTargets(reviewDefinition.value) : []))
const chartTarget = computed<EffortChartTarget | null>(() => chartTargets.value[chartTargetIndex.value] ?? chartTargets.value[0] ?? null)
const reviewRange = computed(() => resolveEffortPeriod(periodKind.value, new Date(), periodOffset.value, { from: customFrom.value, to: customTo.value }))

function chartTargetLabel(tg: EffortChartTarget): string {
  const def = reviewDefinition.value?.fields?.find((f) => f.fieldKey === tg.fieldKey)
  const kind = t(`effort.chart.${tg.chart.toLowerCase()}`)
  return def ? `${kind} (${fieldLabel(def)})` : kind
}

const statsGuard = createLatestGuard()

async function loadStats() {
  const isLatest = statsGuard.next()
  stats.value = null
  statsError.value = null
  rangeErrorKey.value = null
  statsLoading.value = false
  const range = reviewRange.value
  const target = chartTarget.value
  if (!range) {
    if (periodKind.value === 'custom' && (customFrom.value !== null || customTo.value !== null)) {
      if (!customFrom.value || !customTo.value) rangeErrorKey.value = 'effort.period.invalidMissing'
      else if (customTo.value < customFrom.value) rangeErrorKey.value = 'effort.period.invalidOrder'
      else rangeErrorKey.value = 'effort.period.invalid'
    }
    return
  }
  if (!target || !reviewDefinitionUuid.value) return
  statsLoading.value = true
  try {
    const res = (await api.stats({
      dateFrom: range.dateFrom,
      dateTo: range.dateTo,
      definitionUuid: reviewDefinitionUuid.value,
      fieldKey: target.fieldKey,
      chart: target.chart,
      grain: range.grain,
    })).data
    if (isLatest()) stats.value = res
  } catch (e) {
    if (isLatest()) statsError.value = e
  } finally {
    if (isLatest()) statsLoading.value = false
  }
}

function setPeriod(kind: EffortPeriodKind) {
  periodKind.value = kind
  periodOffset.value = 0
}

watch(reviewDefinitionUuid, () => { chartTargetIndex.value = 0 })
watch([periodKind, periodOffset, customFrom, customTo, reviewDefinitionUuid, chartTargetIndex, tab], () => {
  if (tab.value === 'review') void loadStats()
})

onMounted(async () => {
  await Promise.all([loadPresets(), loadRecords(true)])
})
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <PageHeader :title="t('effort.title')" :subtitle="t('effort.subtitle')" back-to="/my" />

    <div class="mb-4 flex gap-2" role="tablist">
      <Button
        v-for="k in (['record', 'review'] as const)"
        :key="k"
        :label="t(`effort.${k}`)"
        :severity="tab === k ? undefined : 'secondary'"
        :outlined="tab !== k"
        class="min-h-11"
        role="tab"
        :aria-selected="tab === k"
        :data-testid="`effort-tab-${k}`"
        @click="tab = k"
      />
    </div>

    <PageLoading v-if="presetsLoading" size="40px" />
    <DashboardErrorState v-else-if="presetsError" :error="presetsError" @retry="loadPresets" />

    <template v-else-if="tab === 'record'">
      <SectionCard :title="editing ? t('effort.editTitle') : t('effort.newTitle')" class="mb-4">
        <div class="flex flex-col gap-4">
          <div v-if="!editing">
            <label for="effort-preset" class="mb-1 inline-flex min-h-11 items-center text-sm font-medium">{{ t('effort.template') }}</label>
            <Select
              v-model="selectedDefinitionUuid"
              input-id="effort-preset"
              :options="presets.map((p) => ({ value: p.definitionUuid, label: presetName(p) }))"
              option-label="label"
              option-value="value"
              class="min-h-11 w-full"
              data-testid="effort-preset"
              @update:model-value="onPresetChange"
            />
          </div>
          <div v-else class="text-sm text-surface-500">{{ presetName(editing.definition ?? {}) }}</div>

          <div>
            <label for="effort-recorded-on" class="mb-1 inline-flex min-h-11 items-center text-sm font-medium">{{ t('effort.recordedOn') }}</label>
            <DatePicker
              v-model="recordedOn"
              input-id="effort-recorded-on"
              input-class="min-h-11 text-base"
              class="w-full"
              date-format="yy/mm/dd"
              :max-date="new Date()"
              show-icon
              @input="onRecordedOnInput"
              @date-select="recordedOnTyped = null"
            />
          </div>

          <ActivityFieldInputs v-model="inputs" :fields="formFields" />

          <div>
            <label for="effort-private-note" class="mb-1 inline-flex min-h-11 items-center text-sm font-medium">{{ t('effort.privateNote') }}</label>
            <Textarea id="effort-private-note" v-model="privateNote" class="min-h-11 w-full text-base" rows="2" auto-resize maxlength="2000" />
            <p class="mt-1 text-xs text-surface-500">{{ t('effort.privateNoteHint') }}</p>
          </div>

          <p v-if="formError" class="text-sm text-red-600" role="alert" data-testid="effort-form-error">{{ formError }}</p>
          <p v-if="savedNotice" class="text-sm text-green-700" role="status" data-testid="effort-saved-notice">{{ savedNotice }}</p>
          <p v-if="conflictNotice" class="text-sm text-amber-700" role="alert" data-testid="effort-conflict-notice">{{ t('effort.error.conflict') }}</p>

          <div class="flex flex-wrap gap-2">
            <Button :label="t('effort.privateSave')" :loading="saving" class="min-h-11" data-testid="effort-save" @click="save" />
            <Button v-if="editing" :label="t('effort.newRecord')" severity="secondary" outlined class="min-h-11" @click="newRecord" />
          </div>
        </div>
      </SectionCard>

      <SectionCard :title="t('effort.list.title')">
        <PageLoading v-if="recordsLoading && records.length === 0" size="32px" />
        <DashboardErrorState v-else-if="recordsError" :error="recordsError" @retry="loadRecords(true)" />
        <DashboardEmptyState v-else-if="records.length === 0" icon="pi-list" :message="t('effort.list.empty')" />
        <ul v-else class="flex flex-col gap-3" data-testid="effort-list">
          <li v-for="r in records" :key="r.id" class="flex flex-wrap items-center justify-between gap-2 border-b border-surface-200 pb-2 dark:border-surface-700">
            <div class="min-w-0">
              <div class="text-sm font-semibold">{{ r.recordedOn }} <span class="font-normal text-surface-500">{{ presetName(r.definition ?? {}) }}</span></div>
              <div class="break-words text-sm text-surface-600">{{ summaryOf(r) }}</div>
            </div>
            <div class="flex gap-2">
              <Button :label="t('effort.edit')" size="small" severity="secondary" outlined class="min-h-11" @click="startEdit(r)" />
              <Button :label="t('effort.delete.accept')" size="small" severity="danger" outlined class="min-h-11" @click="askDelete(r)" />
            </div>
          </li>
        </ul>
        <div v-if="nextCursor" class="mt-3">
          <Button :label="t('effort.list.more')" :loading="recordsLoading" severity="secondary" outlined class="min-h-11" @click="loadRecords(false)" />
        </div>
      </SectionCard>
    </template>

    <template v-else>
      <SectionCard :title="t('effort.review')">
        <div class="mb-3 flex flex-wrap gap-2">
          <Button
            v-for="k in (['week', 'month', 'year', 'custom'] as const)"
            :key="k"
            :label="t(`effort.period.${k}`)"
            :severity="periodKind === k ? undefined : 'secondary'"
            :outlined="periodKind !== k"
            class="min-h-11"
            :data-testid="`effort-period-${k}`"
            @click="setPeriod(k)"
          />
        </div>

        <div v-if="periodKind !== 'custom'" class="mb-3 flex items-center gap-2">
          <Button icon="pi pi-chevron-left" :aria-label="t('effort.period.prev')" severity="secondary" outlined class="min-h-11 min-w-11" @click="periodOffset--" />
          <span class="text-sm" data-testid="effort-range">{{ reviewRange?.dateFrom }} - {{ reviewRange?.dateTo }}</span>
          <Button icon="pi pi-chevron-right" :aria-label="t('effort.period.next')" severity="secondary" outlined class="min-h-11 min-w-11" @click="periodOffset++" />
        </div>
        <div v-else class="mb-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div>
            <label for="effort-from" class="mb-1 inline-flex min-h-11 items-center text-sm font-medium">{{ t('effort.period.from') }}</label>
            <DatePicker v-model="customFrom" input-id="effort-from" input-class="min-h-11 text-base" class="w-full" date-format="yy/mm/dd" show-icon />
          </div>
          <div>
            <label for="effort-to" class="mb-1 inline-flex min-h-11 items-center text-sm font-medium">{{ t('effort.period.to') }}</label>
            <DatePicker v-model="customTo" input-id="effort-to" input-class="min-h-11 text-base" class="w-full" date-format="yy/mm/dd" show-icon />
          </div>
          <p v-if="rangeErrorKey" class="text-sm text-red-600 sm:col-span-2" role="alert" data-testid="effort-range-error">{{ t(rangeErrorKey) }}</p>
        </div>

        <div class="mb-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div>
            <label for="effort-review-def" class="mb-1 inline-flex min-h-11 items-center text-sm font-medium">{{ t('effort.template') }}</label>
            <Select
              v-model="reviewDefinitionUuid"
              input-id="effort-review-def"
              :options="presets.map((p) => ({ value: p.definitionUuid, label: presetName(p) }))"
              option-label="label"
              option-value="value"
              class="min-h-11 w-full"
            />
          </div>
          <div>
            <label for="effort-review-chart" class="mb-1 inline-flex min-h-11 items-center text-sm font-medium">{{ t('effort.chart.kind') }}</label>
            <Select
              v-model="chartTargetIndex"
              input-id="effort-review-chart"
              :options="chartTargets.map((tg, i) => ({ value: i, label: chartTargetLabel(tg) }))"
              option-label="label"
              option-value="value"
              class="min-h-11 w-full"
            />
          </div>
        </div>

        <PageLoading v-if="statsLoading" size="32px" />
        <DashboardErrorState v-else-if="statsError" :error="statsError" @retry="loadStats" />
        <EffortStatsView v-else-if="stats" :stats="stats" />
      </SectionCard>
    </template>
  </div>
</template>
