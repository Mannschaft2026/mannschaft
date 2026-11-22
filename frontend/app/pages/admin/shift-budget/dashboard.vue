<script setup lang="ts">
import { useShiftBudgetAdminAccess } from '~/composables/shift/useShiftBudgetAdminAccess'
import dayjs from 'dayjs'
import type {
  AllocationResponse,
  ClosePeriodResponse,
  ConsumptionStatus,
  MonthlyCloseRequest,
} from '~/types/shiftBudget'

/**
 * F08.7 Phase 10-γ: 予算ダッシュボード (`/admin/shift-budget/dashboard`)。
 *
 * <p>設計書 §7.4 に準拠。allocation 一覧から消化率の集計を表示。
 * 月次締め実行ボタン (BUDGET_ADMIN) と関連画面リンクを提供する。</p>
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { userTimezone } = useDatetime()
const scopeStore = useScopeStore()
const notification = useNotification()
const api = useShiftBudgetApi()

const organizationId = computed(() => {
  if (scopeStore.current.type !== 'organization') return null
  return scopeStore.current.id
})

/**
 * 予算の管理操作（BUDGET_ADMIN）を出してよいかの判定。
 * BE は権限が無ければ 403 を返す（その防御は外していない）。ここでは弾かれる操作を
 * そもそも画面に出さないために使う。CMP-260913-1251。
 */
const { canManageBudget, ensureLoaded: ensureBudgetAdminAccess } = useShiftBudgetAdminAccess()

const allocations = ref<AllocationResponse[]>([])
const loading = ref(false)
/** 取得の失敗。空配列のまま空状態・KPI 0 件に化けさせないために保持する。 */
const loadError = ref<unknown>(null)
const loadFailed = ref(false)

const monthlyCloseVisible = ref(false)
const monthlyCloseForm = ref<{ periodKey: string | null }>({ periodKey: null })
const monthlyCloseExecuting = ref(false)
const closePeriods = ref<ClosePeriodResponse[]>([])
/**
 * ダイアログが表示している期間一覧の取得元組織。締め実行時に現在の組織と一致することを確かめる
 * （CMP-260909-2152 α Codex 検分 P2）。組織ごとに締め日が違うので、旧組織の期間を確認した操作で新組織を締めない。
 */
const closePeriodsOrganizationId = ref<string | null>(null)

/**
 * 取得の世代。組織切替や再取得のたびに進め、応答の反映時に「取得時の組織が現在の組織で、かつ最新の取得」かを確かめる
 * （settings.vue と同じ世代ガード。CMP-260909-2152 α Codex 検分 P2）。旧組織の応答が後着して新組織の画面を上書きしないようにする。
 */
let loadGeneration = 0
let closePeriodsGeneration = 0
/** 組織の世代。組織を切り替えるたびに進め、締め実行の応答を送信時の組織の画面にだけ反映する。 */
let scopeGeneration = 0

/** 期間一覧の取得範囲（期間キー。今日を含む期間の 12 か月前〜翌月。BE の上限 36 か月以内） */
const CLOSE_PERIOD_LOOKBACK_MONTHS = 12

/**
 * 終了済み（終了日当日を含む）の期間だけが締められる。判定はサーバーの業務日（Asia/Tokyo）で BE が返す ended を正とし、
 * 利用者端末のタイムゾーンの「今日」では判定しない（締め API の 409 判定とずらさない。CMP-260909-2152 再検分 P2-4）。
 */
function isEnded(period: ClosePeriodResponse): boolean {
  return period.ended
}

function periodLabel(period: ClosePeriodResponse): string {
  return t('shiftBudget.closePeriod.label', { start: period.period_start, end: period.period_end })
}

const closePeriodOptions = computed(() =>
  closePeriods.value.map(p => ({
    value: p.period_key,
    label: isEnded(p) ? periodLabel(p) : `${periodLabel(p)}（${t('shiftBudget.closePeriod.notEnded')}）`,
    disabled: !isEnded(p),
  })),
)

const selectedClosePeriod = computed(() =>
  closePeriods.value.find(p => p.period_key === monthlyCloseForm.value.periodKey) ?? null,
)

/** 選択中の期間が終了済みのときだけ実行できる（未終了は 409 を待たずに止める） */
const canExecuteMonthlyClose = computed(() =>
  selectedClosePeriod.value !== null && isEnded(selectedClosePeriod.value),
)

async function load() {
  const generation = ++loadGeneration
  const orgId = organizationId.value
  if (!orgId) {
    allocations.value = []
    loading.value = false
    return
  }
  const isCurrent = () => generation === loadGeneration && orgId === organizationId.value
  loading.value = true
  loadFailed.value = false
  loadError.value = null
  try {
    // ページサイズ大きめで一括取得（ダッシュボード用途）
    const res = await api.listAllocations(orgId, 0, 100)
    if (!isCurrent()) return
    allocations.value = res.items
  }
  catch (error) {
    if (!isCurrent()) return
    allocations.value = []
    loadError.value = error
    loadFailed.value = true
    notification.error(t('shiftBudget.dashboard.loadError'))
  }
  finally {
    if (isCurrent()) loading.value = false
  }
}

function consumptionRate(a: AllocationResponse): number {
  const allocated = a.allocated_amount ?? 0
  const consumed = a.consumed_amount ?? 0
  if (allocated === 0) return 0
  return consumed / allocated
}

function statusOf(a: AllocationResponse): ConsumptionStatus {
  const r = consumptionRate(a)
  if (r >= 1.2) return 'SEVERE_EXCEEDED'
  if (r >= 1.0) return 'EXCEEDED'
  if (r >= 0.8) return 'WARN'
  return 'OK'
}

const summary = computed(() => {
  const counts: Record<ConsumptionStatus, number> = {
    OK: 0,
    WARN: 0,
    EXCEEDED: 0,
    SEVERE_EXCEEDED: 0,
  }
  for (const a of allocations.value) {
    counts[statusOf(a)] += 1
  }
  return counts
})

/** 締めダイアログと期間一覧・選択を破棄する（組織切替時）。進行中の期間取得の応答も反映させない。 */
function resetMonthlyClose() {
  closePeriodsGeneration++
  monthlyCloseVisible.value = false
  monthlyCloseForm.value = { periodKey: null }
  closePeriods.value = []
  closePeriodsOrganizationId.value = null
  monthlyCloseExecuting.value = false
}

async function openMonthlyClose() {
  const orgId = organizationId.value
  if (!orgId) return
  const generation = ++closePeriodsGeneration
  const isCurrent = () => generation === closePeriodsGeneration && orgId === organizationId.value
  monthlyCloseForm.value = { periodKey: null }
  closePeriods.value = []
  closePeriodsOrganizationId.value = null
  monthlyCloseVisible.value = true
  const today = dayjs().tz(userTimezone.value)
  try {
    const res = await api.listClosePeriods(
      orgId,
      today.subtract(CLOSE_PERIOD_LOOKBACK_MONTHS, 'month').format('YYYY-MM'),
      today.add(1, 'month').format('YYYY-MM'),
    )
    if (!isCurrent()) return
    closePeriods.value = [...res.items].sort((a, b) => a.period_key.localeCompare(b.period_key))
    closePeriodsOrganizationId.value = orgId
    // 既定は終了済みの最新期間
    const latestEnded = [...closePeriods.value].reverse().find(isEnded)
    monthlyCloseForm.value = { periodKey: latestEnded?.period_key ?? null }
  }
  catch {
    if (!isCurrent()) return
    notification.error(t('shiftBudget.monthlyClose.periodLoadError'))
  }
}

async function executeMonthlyClose() {
  const orgId = organizationId.value
  if (!orgId) return
  const period = selectedClosePeriod.value
  if (!period || !isEnded(period)) return
  // ダイアログが表示している期間の取得元組織と現在の組織が違えば締めない（旧組織の期間で新組織を締める事故を防ぐ）
  if (closePeriodsOrganizationId.value !== orgId) {
    notification.error(t('shiftBudget.monthlyClose.organizationMismatch'))
    resetMonthlyClose()
    return
  }
  const yearMonth = period.period_key
  const generation = scopeGeneration
  const isCurrentScope = () => generation === scopeGeneration && orgId === organizationId.value
  monthlyCloseExecuting.value = true
  try {
    const request: MonthlyCloseRequest = {
      organization_id: Number(orgId),
      year_month: yearMonth,
    }
    const res = await api.executeMonthlyClose(request)
    // 締めの結果は送信時の組織について知らせる（組織を切り替えていても結果は通知する）。
    // ダイアログの開閉と再読込は送信時の組織の画面のときだけ行う。
    // 段取り役は Post 失敗・ABORTED を HTTP 200 の failed_allocations に集計する（CMP-260909-2152 Codex 検分3 P2-1）。
    // 失敗が1件でもあれば未締めの割当が残るので、成功・締め済みとは区別して通知する。
    if (res.failed_allocations > 0 && res.closed_allocations === 0) {
      // 全件失敗: 何も締まっていない。ダイアログを閉じずに再実行できるようにする
      notification.error(t('shiftBudget.monthlyClose.allFailed', {
        period: periodLabel(period),
        failed: res.failed_allocations,
      }))
      return
    }
    if (res.failed_allocations > 0) {
      notification.warn(t('shiftBudget.monthlyClose.partiallyFailed', {
        period: periodLabel(period),
        closed: res.closed_allocations,
        failed: res.failed_allocations,
      }))
    }
    else if (res.already_closed_allocations > 0 && res.closed_allocations === 0) {
      notification.info(t('shiftBudget.monthlyClose.alreadyClosed'))
    }
    else {
      notification.success(
        t('shiftBudget.monthlyClose.success', {
          period: periodLabel(period),
          count: res.closed_consumptions,
        }),
      )
    }
    if (!isCurrentScope()) return
    monthlyCloseVisible.value = false
    await load()
  }
  catch {
    notification.error(t('shiftBudget.monthlyClose.error'))
  }
  finally {
    // 組織を切り替えた後は切替時に解除済み。新組織の実行中フラグを旧組織の応答で外さない
    if (isCurrentScope()) monthlyCloseExecuting.value = false
  }
}

watch(organizationId, () => {
  // 旧組織の締めダイアログ（期間一覧・選択・期間表示）を破棄してから新組織を取得する
  scopeGeneration++
  resetMonthlyClose()
  load()
  ensureBudgetAdminAccess()
})
onMounted(() => {
  load()
  ensureBudgetAdminAccess()
})
</script>

<template>
  <div class="mx-auto max-w-7xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="t('shiftBudget.dashboard.title')" />
      <div v-if="organizationId && canManageBudget" class="flex items-center gap-2">
        <NuxtLink to="/admin/shift-budget/settings" data-testid="closing-settings-link">
          <Button
            :label="t('shiftBudget.dashboard.viewClosingSettings')"
            icon="pi pi-cog"
            severity="secondary"
            text
            as="span"
          />
        </NuxtLink>
        <Button
          :label="t('shiftBudget.monthlyClose.button')"
          icon="pi pi-calendar-times"
          severity="secondary"
          @click="openMonthlyClose"
        />
      </div>
    </div>
    <p class="mb-6 text-sm text-surface-500">{{ t('shiftBudget.dashboard.subtitle') }}</p>

    <Message v-if="!organizationId" severity="warn" :closable="false" class="mb-4">
      {{ t('shiftBudget.scope.selectOrganization') }}
    </Message>

    <PageLoading v-else-if="loading" />

    <DashboardErrorState
      v-else-if="loadFailed"
      :error="loadError"
      testid="shift-budget-dashboard-error-state"
      @retry="load"
    />

    <template v-else>
      <!-- KPI 4 カラムグリッド (PC), 2 カラム (タブレット), 1 カラム (モバイル) -->
      <div class="mb-6 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
        <Card>
          <template #content>
            <div class="flex items-center justify-between">
              <div>
                <p class="text-sm text-surface-500">{{ t('shiftBudget.dashboard.kpi.ok') }}</p>
                <p class="text-3xl font-bold text-green-500">{{ summary.OK }}</p>
              </div>
              <i class="pi pi-check-circle text-3xl text-green-500" />
            </div>
          </template>
        </Card>
        <Card>
          <template #content>
            <div class="flex items-center justify-between">
              <div>
                <p class="text-sm text-surface-500">{{ t('shiftBudget.dashboard.kpi.warn') }}</p>
                <p class="text-3xl font-bold text-yellow-500">{{ summary.WARN }}</p>
              </div>
              <i class="pi pi-exclamation-triangle text-3xl text-yellow-500" />
            </div>
          </template>
        </Card>
        <Card>
          <template #content>
            <div class="flex items-center justify-between">
              <div>
                <p class="text-sm text-surface-500">{{ t('shiftBudget.dashboard.kpi.exceeded') }}</p>
                <p class="text-3xl font-bold text-orange-500">{{ summary.EXCEEDED }}</p>
              </div>
              <i class="pi pi-times-circle text-3xl text-orange-500" />
            </div>
          </template>
        </Card>
        <Card>
          <template #content>
            <div class="flex items-center justify-between">
              <div>
                <p class="text-sm text-surface-500">{{ t('shiftBudget.dashboard.kpi.severeExceeded') }}</p>
                <p class="text-3xl font-bold text-red-600">{{ summary.SEVERE_EXCEEDED }}</p>
              </div>
              <i class="pi pi-ban text-3xl text-red-600" />
            </div>
          </template>
        </Card>
      </div>

      <!-- 全 allocation 一覧 (簡易) -->
      <Card class="mb-6">
        <template #title>
          <span class="text-lg">{{ t('shiftBudget.allocation.list') }}</span>
        </template>
        <template #content>
          <DataTable :value="allocations" striped-rows data-key="id" :rows="10" paginator>
            <template #empty>
              <div class="py-4 text-center text-surface-500">{{ t('shiftBudget.allocation.empty') }}</div>
            </template>
            <Column field="id" :header="t('shiftBudget.allocation.id')" style="width: 80px" />
            <Column :header="t('shiftBudget.allocation.period')" style="width: 220px">
              <template #body="{ data }: { data: AllocationResponse }">
                <span class="text-sm">{{ data.period_start }} 〜 {{ data.period_end }}</span>
              </template>
            </Column>
            <Column :header="t('shiftBudget.allocation.amount')">
              <template #body="{ data }: { data: AllocationResponse }">
                <span>{{ data.allocated_amount?.toLocaleString() ?? '-' }} {{ data.currency }}</span>
              </template>
            </Column>
            <Column :header="t('shiftBudget.allocation.consumed')">
              <template #body="{ data }: { data: AllocationResponse }">
                <span>{{ data.consumed_amount?.toLocaleString() ?? '0' }} {{ data.currency }}</span>
              </template>
            </Column>
            <Column :header="t('shiftBudget.allocation.rate')" style="width: 200px">
              <template #body="{ data }: { data: AllocationResponse }">
                <ConsumptionRateBadge :rate="consumptionRate(data)" />
              </template>
            </Column>
          </DataTable>
        </template>
      </Card>

      <!-- 関連画面リンク -->
      <div class="grid grid-cols-1 gap-4 md:grid-cols-3">
        <NuxtLink to="/admin/shift-budget/allocations" class="block">
          <Card class="cursor-pointer transition-all hover:shadow-lg">
            <template #content>
              <div class="flex items-center gap-3">
                <i class="pi pi-list text-2xl text-blue-500" />
                <span class="font-medium">{{ t('shiftBudget.dashboard.viewAllocations') }}</span>
              </div>
            </template>
          </Card>
        </NuxtLink>
        <NuxtLink to="/admin/shift-budget/alerts" class="block">
          <Card class="cursor-pointer transition-all hover:shadow-lg">
            <template #content>
              <div class="flex items-center gap-3">
                <i class="pi pi-bell text-2xl text-yellow-500" />
                <span class="font-medium">{{ t('shiftBudget.dashboard.viewAlerts') }}</span>
              </div>
            </template>
          </Card>
        </NuxtLink>
        <NuxtLink to="/admin/shift-budget/failed-events" class="block">
          <Card class="cursor-pointer transition-all hover:shadow-lg">
            <template #content>
              <div class="flex items-center gap-3">
                <i class="pi pi-exclamation-circle text-2xl text-red-500" />
                <span class="font-medium">{{ t('shiftBudget.dashboard.viewFailedEvents') }}</span>
              </div>
            </template>
          </Card>
        </NuxtLink>
      </div>
    </template>

    <!-- 月次締め実行モーダル -->
    <Dialog
      v-model:visible="monthlyCloseVisible"
      :header="t('shiftBudget.monthlyClose.title')"
      :style="{ width: '500px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.monthlyClose.period') }}</label>
          <Select
            v-model="monthlyCloseForm.periodKey"
            data-testid="monthly-close-period-select"
            :options="closePeriodOptions"
            option-label="label"
            option-value="value"
            option-disabled="disabled"
            :placeholder="t('shiftBudget.closePeriod.select')"
            class="w-full"
          />
          <p class="mt-1 text-xs text-surface-500">{{ t('shiftBudget.monthlyClose.periodHint') }}</p>
        </div>
        <Message v-if="selectedClosePeriod" severity="info" :closable="false" data-testid="monthly-close-confirm">
          {{ t('shiftBudget.monthlyClose.confirm', { period: periodLabel(selectedClosePeriod) }) }}
        </Message>
        <Message v-else severity="warn" :closable="false">
          {{ t('shiftBudget.closePeriod.empty') }}
        </Message>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button
            :label="t('shiftBudget.allocation.form.cancel')"
            severity="secondary"
            :disabled="monthlyCloseExecuting"
            @click="monthlyCloseVisible = false"
          />
          <Button
            :label="t('shiftBudget.monthlyClose.execute')"
            :loading="monthlyCloseExecuting"
            :disabled="!canExecuteMonthlyClose"
            @click="executeMonthlyClose"
          />
        </div>
      </template>
    </Dialog>
  </div>
</template>
