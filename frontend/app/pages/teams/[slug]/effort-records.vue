<script setup lang="ts">
/**
 * F06.4.1 チームの頑張りの共有履歴（PR-4）。`/teams/{slug}/effort-records`。
 * BE は数値 teamId しか受け取らないので、slug から所属一覧で解決する（親設計 §2.3。取得失敗は 404 にせずエラー表示）。
 */
import type { EffortSharedRecordResponse, EffortTeamDefinitionCandidate } from '~/composables/useEffortShareApi'
import { toYmd } from '~/utils/activityFields'
import { classifyLoadError } from '~/utils/loadError'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const { t } = useI18n()
const shareApi = useEffortShareApi()
const { resolveTeamId } = useEffortTeamId()
const { handleApiError } = useErrorHandler()
const { success } = useNotification()
const confirm = useConfirm()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const teamId = ref<number | null>(null)
const resolving = ref(true)
const resolveError = ref<unknown>(null)

const records = ref<EffortSharedRecordResponse[]>([])
const loading = ref(false)
const loadError = ref<unknown>(null)
const forbidden = ref(false)
const nextCursor = ref<string | null>(null)
const definitions = ref<EffortTeamDefinitionCandidate[]>([])

const personId = ref<number | null>(null)
const definitionUuid = ref<string | null>(null)
const from = ref<Date | null>(null)
const to = ref<Date | null>(null)
const rangeError = ref('')
/** 取得済みの投稿者（人の絞り込み候補。メンバー一覧 API には依存しない）。 */
const authors = ref<Map<number, string>>(new Map())

const hasFilter = computed(() => personId.value !== null || definitionUuid.value !== null || (from.value !== null && to.value !== null))
const personOptions = computed(() => [
  { value: null as number | null, label: t('effort.filter.allPeople') },
  ...[...authors.value].map(([id, name]) => ({ value: id as number | null, label: name })),
])
const definitionOptions = computed(() => [
  { value: null as string | null, label: t('effort.filter.allTemplates') },
  ...definitions.value.map((d) => ({
    value: (d.definitionUuid ?? null) as string | null,
    label: (d.templateName ?? '') + (d.isRetired ? t('effort.filter.retired') : ''),
  })),
])

async function resolve() {
  resolving.value = true
  resolveError.value = null
  try {
    const id = await resolveTeamId(teamSlug.value)
    if (id === null) {
      // 取得に成功し、所属一覧に該当チームが無いときだけ 404（存在を漏らさない）。
      showError404()
      return
    }
    teamId.value = id
    await Promise.all([loadRecords(true), loadDefinitions()])
  } catch (e) {
    resolveError.value = e
  } finally {
    resolving.value = false
  }
}

function showError404() {
  showError(createError({ statusCode: 404, fatal: true }))
}

async function loadDefinitions() {
  if (teamId.value === null) return
  try {
    definitions.value = (await shareApi.listTeamDefinitions(teamId.value)).data ?? []
  } catch (e) {
    handleApiError(e)
  }
}

async function loadRecords(reset: boolean) {
  if (teamId.value === null) return
  rangeError.value = ''
  if ((from.value === null) !== (to.value === null)) {
    rangeError.value = t('effort.period.invalidMissing')
    return
  }
  if (from.value && to.value && to.value < from.value) {
    rangeError.value = t('effort.period.invalidOrder')
    return
  }
  loading.value = true
  loadError.value = null
  forbidden.value = false
  try {
    const page = await shareApi.listTeamRecords(teamId.value, {
      userId: personId.value ?? undefined,
      dateFrom: from.value && to.value ? toYmd(from.value) : undefined,
      dateTo: from.value && to.value ? toYmd(to.value) : undefined,
      definitionUuid: definitionUuid.value ?? undefined,
      cursor: reset ? undefined : (nextCursor.value ?? undefined),
      limit: 20,
    })
    const incoming = page.data ?? []
    const merged = reset ? [] : [...records.value]
    for (const r of incoming) {
      const i = merged.findIndex((x) => x.id === r.id)
      if (i >= 0) merged.splice(i, 1, r)
      else merged.push(r)
      if (r.author?.userId !== undefined && !authors.value.has(r.author.userId)) {
        authors.value.set(r.author.userId, r.author.displayName ?? '')
      }
    }
    records.value = merged
    nextCursor.value = page.meta?.hasNext ? (page.meta.nextCursor ?? null) : null
  } catch (e) {
    if (classifyLoadError(e) === 'forbidden') forbidden.value = true
    else loadError.value = e
  } finally {
    loading.value = false
  }
}

function applyFilter() { void loadRecords(true) }

// ---- 詳細
const detailOpen = ref(false)
const detail = ref<EffortSharedRecordResponse | null>(null)
const detailLoading = ref(false)
const detailNotFound = ref(false)
const detailError = ref<unknown>(null)

async function openDetail(r: EffortSharedRecordResponse) {
  if (teamId.value === null || !r.id) return
  detailOpen.value = true
  detail.value = null
  detailNotFound.value = false
  detailError.value = null
  detailLoading.value = true
  try {
    detail.value = (await shareApi.getTeamRecord(teamId.value, r.id)).data
  } catch (e) {
    // 取消・非表示・脱退で見えなくなった共有は、404 を画面内の「見つかりません」で示す。
    if (classifyLoadError(e) === 'notFoundOrForbidden') detailNotFound.value = true
    else detailError.value = e
  } finally {
    detailLoading.value = false
  }
}

// ---- 管理者による非表示
function askModerate(r: EffortSharedRecordResponse) {
  confirm.require({
    header: t('effort.moderate.confirm.header'),
    message: t('effort.moderate.confirm.message'),
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: t('effort.moderate.confirm.accept'),
    rejectLabel: t('effort.moderate.confirm.reject'),
    acceptClass: 'p-button-danger',
    accept: () => moderate(r),
  })
}

async function moderate(r: EffortSharedRecordResponse) {
  if (teamId.value === null || !r.id) return
  try {
    await shareApi.moderate(teamId.value, r.id, r.version ?? 0, crypto.randomUUID())
    records.value = records.value.filter((x) => x.id !== r.id)
    if (detail.value?.id === r.id) detailOpen.value = false
    success(t('effort.moderate.done'))
  } catch (e) {
    handleApiError(e)
  }
}

onMounted(async () => {
  void loadPermissions()
  await resolve()
})
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <PageHeader :title="t('effort.teamHistory.title')" :subtitle="t('effort.teamHistory.subtitle')" :back-to="`/teams/${teamSlug}/activities`" />

    <PageLoading v-if="resolving" size="40px" />
    <DashboardErrorState v-else-if="resolveError" :error="resolveError" :message="t('effort.teamHistory.error')" testid="effort-team-resolve-error" @retry="resolve" />

    <template v-else-if="teamId !== null">
      <SectionCard class="mb-4">
        <div class="grid grid-cols-1 gap-3 sm:grid-cols-2" data-testid="effort-team-filters">
          <div>
            <label for="effort-filter-person" class="mb-1 inline-flex min-h-11 items-center text-sm font-medium">{{ t('effort.filter.person') }}</label>
            <Select v-model="personId" input-id="effort-filter-person" :options="personOptions" option-label="label" option-value="value" class="min-h-11 w-full" data-testid="effort-filter-person" @update:model-value="applyFilter" />
          </div>
          <div>
            <label for="effort-filter-template" class="mb-1 inline-flex min-h-11 items-center text-sm font-medium">{{ t('effort.filter.template') }}</label>
            <Select v-model="definitionUuid" input-id="effort-filter-template" :options="definitionOptions" option-label="label" option-value="value" class="min-h-11 w-full" data-testid="effort-filter-template" @update:model-value="applyFilter" />
          </div>
          <div>
            <label for="effort-filter-from" class="mb-1 inline-flex min-h-11 items-center text-sm font-medium">{{ t('effort.filter.from') }}</label>
            <DatePicker v-model="from" input-id="effort-filter-from" input-class="min-h-11 text-base" class="w-full" date-format="yy/mm/dd" show-icon show-button-bar @update:model-value="applyFilter" />
          </div>
          <div>
            <label for="effort-filter-to" class="mb-1 inline-flex min-h-11 items-center text-sm font-medium">{{ t('effort.filter.to') }}</label>
            <DatePicker v-model="to" input-id="effort-filter-to" input-class="min-h-11 text-base" class="w-full" date-format="yy/mm/dd" show-icon show-button-bar @update:model-value="applyFilter" />
          </div>
          <p v-if="rangeError" class="text-sm text-red-600 sm:col-span-2" role="alert" data-testid="effort-team-range-error">{{ rangeError }}</p>
        </div>
      </SectionCard>

      <SectionCard>
        <p v-if="forbidden" class="text-sm text-surface-500" role="alert" data-testid="effort-team-forbidden">{{ t('effort.teamHistory.forbidden') }}</p>
        <PageLoading v-else-if="loading && records.length === 0" size="32px" />
        <DashboardErrorState v-else-if="loadError" :error="loadError" :message="t('effort.teamHistory.error')" testid="effort-team-load-error" @retry="loadRecords(true)" />
        <DashboardEmptyState v-else-if="records.length === 0" icon="pi-chart-line" :message="hasFilter ? t('effort.teamHistory.emptyFiltered') : t('effort.teamHistory.empty')" />
        <ul v-else class="flex flex-col gap-3" data-testid="effort-team-list">
          <li v-for="r in records" :key="r.id" class="rounded-lg border border-surface-200 p-3 dark:border-surface-700" :data-testid="`effort-team-card-${r.id}`">
            <div class="mb-1 flex flex-wrap items-baseline justify-between gap-2">
              <span class="text-sm font-semibold">{{ r.author?.displayName }}</span>
              <span class="text-xs text-surface-500">{{ r.recordedOn }} / {{ r.templateName }}</span>
            </div>
            <dl class="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-2">
              <template v-for="f in r.fields ?? []" :key="f.fieldKey">
                <dt class="text-xs text-surface-500">{{ f.label }}</dt>
                <dd class="break-words text-sm">{{ f.displayValue ?? f.value ?? '' }}</dd>
              </template>
            </dl>
            <!-- 反応・コメント・返信・引用・再投稿の操作列は置かない（努力由来は interactions がすべて false） -->
            <div class="mt-2 flex flex-wrap gap-2">
              <Button :label="t('effort.teamHistory.detail')" size="small" severity="secondary" outlined class="min-h-11" :data-testid="`effort-team-detail-${r.id}`" @click="openDetail(r)" />
              <Button v-if="isAdminOrDeputy" :label="t('effort.moderate.action')" size="small" severity="danger" outlined class="min-h-11" :data-testid="`effort-team-moderate-${r.id}`" @click="askModerate(r)" />
            </div>
          </li>
        </ul>
        <div v-if="nextCursor" class="mt-3">
          <Button :label="t('effort.teamHistory.more')" :loading="loading" severity="secondary" outlined class="min-h-11" data-testid="effort-team-more" @click="loadRecords(false)" />
        </div>
      </SectionCard>

      <Dialog v-model:visible="detailOpen" modal :header="t('effort.teamHistory.detail')" style="width: 600px; max-width: 95vw">
        <PageLoading v-if="detailLoading" size="32px" />
        <p v-else-if="detailNotFound" class="text-sm" role="alert" data-testid="effort-team-detail-notfound">{{ t('effort.teamHistory.notFound') }}</p>
        <p v-else-if="detailError" class="text-sm text-red-600" role="alert">{{ t('effort.teamHistory.error') }}</p>
        <div v-else-if="detail" data-testid="effort-team-detail">
          <div class="mb-2 text-sm font-semibold">{{ detail.author?.displayName }} <span class="font-normal text-surface-500">{{ detail.recordedOn }} / {{ detail.templateName }}</span></div>
          <dl class="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-2">
            <template v-for="f in detail.fields ?? []" :key="f.fieldKey">
              <dt class="text-xs text-surface-500">{{ f.label }}</dt>
              <dd class="break-words text-sm">{{ f.displayValue ?? f.value ?? '' }}</dd>
            </template>
          </dl>
        </div>
      </Dialog>
    </template>
  </div>
</template>
