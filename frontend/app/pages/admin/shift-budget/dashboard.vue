<script setup lang="ts">
import { useShiftBudgetAdminAccess } from '~/composables/shift/useShiftBudgetAdminAccess'
import dayjs from 'dayjs'
import { parseApiError } from '~/utils/apiError'
import type {
  AllocationResponse,
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
const monthlyCloseForm = ref<{ yearMonth: string }>({ yearMonth: '' })
const monthlyCloseExecuting = ref(false)

/**
 * 月次締めの「当月」の基準タイムゾーン。BE の判定（JST 壁時計）と揃える。
 * テナント TZ 導入時（BE 側 wallClock の差し替え）にここも追随させる。
 */
const MONTH_CLOSE_TZ = 'Asia/Tokyo'

function currentMonthJst(): string {
  return dayjs().tz(MONTH_CLOSE_TZ).format('YYYY-MM')
}

function defaultPreviousMonth(): string {
  return dayjs().tz(MONTH_CLOSE_TZ).subtract(1, 'month').format('YYYY-MM')
}

const YEAR_MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/

/** 前後空白を除いた対象月。判定・送信はこの値で行う。 */
const trimmedYearMonth = computed(() => monthlyCloseForm.value.yearMonth.trim())
/** 対象月が YYYY-MM 形式として正しいか。 */
const yearMonthValid = computed(() => YEAR_MONTH_PATTERN.test(trimmedYearMonth.value))

/** 対象月が終了していない（JST の当月以降）か。形式不正は false（形式エラー側で扱う）。 */
const monthNotEnded = computed(() => {
  const ym = trimmedYearMonth.value
  // YYYY-MM は辞書順 = 時系列順
  return YEAR_MONTH_PATTERN.test(ym) && ym >= currentMonthJst()
})
/** BE が 409 SHIFT_BUDGET_032 を返した対象月。ダイアログ内に理由を出す。 */
const notEndedRejectedMonth = ref<string | null>(null)
/** 未終了の警告を出すか（クライアント判定 or BE 409 で拒否された月）。 */
const showNotEnded = computed(
  () => monthNotEnded.value || (yearMonthValid.value && notEndedRejectedMonth.value === trimmedYearMonth.value),
)
/** 実行できるか。形式不正・未終了では無効。 */
const canExecuteMonthlyClose = computed(() => yearMonthValid.value && !showNotEnded.value)

async function load() {
  if (!organizationId.value) {
    allocations.value = []
    return
  }
  loading.value = true
  loadFailed.value = false
  loadError.value = null
  try {
    // ページサイズ大きめで一括取得（ダッシュボード用途）
    const res = await api.listAllocations(organizationId.value, 0, 100)
    allocations.value = res.items
  }
  catch (error) {
    allocations.value = []
    loadError.value = error
    loadFailed.value = true
    notification.error(t('shiftBudget.dashboard.loadError'))
  }
  finally {
    loading.value = false
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

function openMonthlyClose() {
  monthlyCloseForm.value = { yearMonth: defaultPreviousMonth() }
  notEndedRejectedMonth.value = null
  monthlyCloseVisible.value = true
}

async function executeMonthlyClose() {
  if (!organizationId.value) return
  const yearMonth = trimmedYearMonth.value
  // 形式不正・未終了月・BE 409 で拒否済みの月は送らない（ボタン無効化をすり抜けた場合の防御）
  if (!canExecuteMonthlyClose.value) return
  notEndedRejectedMonth.value = null
  monthlyCloseExecuting.value = true
  try {
    const request: MonthlyCloseRequest = {
      organization_id: Number(organizationId.value),
      year_month: yearMonth,
    }
    const res = await api.executeMonthlyClose(request)
    if (res.already_closed_allocations > 0 && res.closed_allocations === 0) {
      notification.info(t('shiftBudget.monthlyClose.alreadyClosed'))
    }
    else {
      notification.success(
        t('shiftBudget.monthlyClose.success', {
          month: yearMonth,
          count: res.closed_consumptions,
        }),
      )
    }
    monthlyCloseVisible.value = false
    await load()
  }
  catch (e) {
    if (parseApiError(e).code === 'SHIFT_BUDGET_032') {
      // 未終了月の拒否。ダイアログを閉じず、理由をその場に出す
      notEndedRejectedMonth.value = yearMonth
      notification.error(t('shiftBudget.monthlyClose.notEnded', { month: yearMonth }))
    }
    else {
      notification.error(t('shiftBudget.monthlyClose.error'))
    }
  }
  finally {
    monthlyCloseExecuting.value = false
  }
}

watch(organizationId, () => {
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
      <Button
        v-if="organizationId && canManageBudget"
        :label="t('shiftBudget.monthlyClose.button')"
        icon="pi pi-calendar-times"
        severity="secondary"
        @click="openMonthlyClose"
      />
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
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.monthlyClose.yearMonth') }}</label>
          <InputText v-model="monthlyCloseForm.yearMonth" placeholder="YYYY-MM" class="w-full" />
          <p
            v-if="!yearMonthValid"
            class="mt-1 text-xs text-red-500"
            data-testid="monthly-close-invalid-format"
          >
            {{ t('shiftBudget.monthlyClose.invalidFormat') }}
          </p>
          <p v-else class="mt-1 text-xs text-surface-500">{{ t('shiftBudget.monthlyClose.yearMonthHint') }}</p>
        </div>
        <Message
          v-if="showNotEnded"
          severity="warn"
          :closable="false"
          data-testid="monthly-close-not-ended"
        >
          {{ t('shiftBudget.monthlyClose.notEnded', { month: trimmedYearMonth }) }}
        </Message>
        <Message v-if="canExecuteMonthlyClose" severity="info" :closable="false">
          {{ t('shiftBudget.monthlyClose.confirm', { month: trimmedYearMonth }) }}
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
