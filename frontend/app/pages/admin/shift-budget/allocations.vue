<script setup lang="ts">
import { useShiftBudgetAdminAccess } from '~/composables/shift/useShiftBudgetAdminAccess'
import type {
  AllocationCreateRequest,
  AllocationResponse,
  AllocationUpdateRequest,
  UncountedShift,
} from '~/types/shiftBudget'

/**
 * F08.7 Phase 10-γ: 予算割当 CRUD 画面 (`/admin/shift-budget/allocations`)。
 *
 * <p>設計書 §7.1 に準拠。一覧 + 新規作成モーダル + 編集モーダル + 論理削除確認。</p>
 *
 * <p>権限:</p>
 * <ul>
 *   <li>BUDGET_VIEW: 一覧・詳細閲覧（誰でも閲覧可、Service 層で 403 を返却）</li>
 *   <li>BUDGET_ADMIN: 作成・編集・削除（バックエンドが権限不足時 403 を返す）</li>
 * </ul>
 *
 * <p>組織スコープが選択されていない場合は誘導メッセージを表示する。</p>
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
const total = ref(0)
const page = ref(0)
const pageSize = ref(20)
const loading = ref(false)

const formVisible = ref(false)
const editingAllocation = ref<AllocationResponse | null>(null)

const deleteConfirmVisible = ref(false)
const deleteTarget = ref<AllocationResponse | null>(null)

async function load() {
  if (!organizationId.value) {
    allocations.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await api.listAllocations(organizationId.value, page.value, pageSize.value)
    allocations.value = res.items
    total.value = res.total
  }
  catch {
    notification.error(t('shiftBudget.allocation.loadError'))
  }
  finally {
    loading.value = false
  }
}

function openCreate() {
  editingAllocation.value = null
  formVisible.value = true
}

function openEdit(allocation: AllocationResponse) {
  editingAllocation.value = allocation
  formVisible.value = true
}

async function handleCreate(payload: AllocationCreateRequest) {
  if (!organizationId.value) return
  try {
    await api.createAllocation(organizationId.value, payload)
    notification.success(t('shiftBudget.allocation.createSuccess'))
    formVisible.value = false
    await load()
  }
  catch {
    notification.error(t('shiftBudget.allocation.createError'))
  }
}

async function handleUpdate(args: { id: number; request: AllocationUpdateRequest }) {
  if (!organizationId.value) return
  try {
    await api.updateAllocation(organizationId.value, args.id, args.request)
    notification.success(t('shiftBudget.allocation.updateSuccess'))
    formVisible.value = false
    await load()
  }
  catch {
    notification.error(t('shiftBudget.allocation.updateError'))
  }
}

function openDelete(allocation: AllocationResponse) {
  deleteTarget.value = allocation
  deleteConfirmVisible.value = true
}

async function confirmDelete() {
  if (!deleteTarget.value || !organizationId.value) return
  try {
    await api.deleteAllocation(organizationId.value, deleteTarget.value.id)
    notification.success(t('shiftBudget.allocation.deleteSuccess'))
    deleteConfirmVisible.value = false
    deleteTarget.value = null
    await load()
  }
  catch {
    notification.error(t('shiftBudget.allocation.deleteError'))
  }
}

// ===== 締め後に未計上の勤務（CMP-260909-2152 / ワンクリック追加） =====

/**
 * 月次締め済みの月で、割当の勤務なのに確定明細にも追加明細にも入っていない勤務（再公開でスキップされた分を含む）。
 * 行ごとの「締め後追加」から、slot・人・勤務日・時間・その日に有効な時給を初期値にした確認ダイアログを開き、
 * 理由を入れて1操作で追加できる。時給はリクエストに明示して送る（サーバは再計算しない）。
 */
const uncountedVisible = ref(false)
const uncountedTarget = ref<AllocationResponse | null>(null)
const uncountedItems = ref<UncountedShift[]>([])
const uncountedRevision = ref(0)
const uncountedLoading = ref(false)

const addVisible = ref(false)
const addTarget = ref<UncountedShift | null>(null)
const addRate = ref('')
const addReason = ref('')
const addSubmitting = ref(false)

/** 割当期間に含まれる月（YYYY-MM）の一覧。複数月の割当は月ごとに締められるため、対象月を選べるようにする。 */
function monthsOf(allocation: AllocationResponse): string[] {
  const months: string[] = []
  let year = Number(allocation.period_start.slice(0, 4))
  let month = Number(allocation.period_start.slice(5, 7))
  const endKey = allocation.period_end.slice(0, 7)
  for (let i = 0; i < 600; i++) {
    const key = `${year}-${String(month).padStart(2, '0')}`
    if (key > endKey) break
    months.push(key)
    month += 1
    if (month > 12) {
      month = 1
      year += 1
    }
  }
  return months
}

/** 選択中の締め対象月（既定は割当の開始月）。 */
const uncountedMonth = ref('')
const uncountedMonthOptions = computed(() =>
  uncountedTarget.value ? monthsOf(uncountedTarget.value).map(m => ({ label: m, value: m })) : [])

function closeMonthOf(allocation: AllocationResponse): string {
  return uncountedMonth.value || allocation.period_start.slice(0, 7)
}

async function onUncountedMonthChange(month: string) {
  uncountedMonth.value = month
  await loadUncounted()
}

async function loadUncounted() {
  if (!organizationId.value || !uncountedTarget.value) return
  uncountedLoading.value = true
  try {
    const res = await api.listUncountedShifts(
      organizationId.value, uncountedTarget.value.id, closeMonthOf(uncountedTarget.value))
    uncountedItems.value = res.items
    uncountedRevision.value = res.revision ?? 0
  }
  catch {
    uncountedItems.value = []
    notification.error(t('shiftBudget.uncounted.loadError'))
  }
  finally {
    uncountedLoading.value = false
  }
}

async function openUncounted(allocation: AllocationResponse) {
  uncountedTarget.value = allocation
  uncountedMonth.value = allocation.period_start.slice(0, 7)
  uncountedVisible.value = true
  await loadUncounted()
}

function openAdd(item: UncountedShift) {
  addTarget.value = item
  addRate.value = item.hourly_rate == null ? '' : String(item.hourly_rate)
  addReason.value = ''
  addVisible.value = true
}

const addRateValue = computed(() => Number(addRate.value))
const addReady = computed(() =>
  addReason.value.trim().length > 0
  && Number.isFinite(addRateValue.value)
  && addRateValue.value > 0)

async function confirmAdd() {
  if (!organizationId.value || !uncountedTarget.value || !addTarget.value || !addReady.value) return
  addSubmitting.value = true
  try {
    await api.createMonthlyCloseCorrection(
      organizationId.value,
      uncountedTarget.value.id,
      closeMonthOf(uncountedTarget.value),
      {
        command_id: crypto.randomUUID(),
        expected_revision: uncountedRevision.value,
        kind: 'ADD',
        user_id: addTarget.value.user_id,
        work_date: addTarget.value.work_date,
        slot_id: addTarget.value.slot_id,
        shift_id: addTarget.value.shift_id,
        hours: addTarget.value.hours,
        hourly_rate: addRateValue.value,
        content: t('shiftBudget.uncounted.content'),
        reason: addReason.value.trim(),
      },
    )
    notification.success(t('shiftBudget.uncounted.addSuccess'))
    addVisible.value = false
    addTarget.value = null
    // 追加した勤務は一覧から消え、割当の金額（訂正後）も更新される
    await Promise.all([loadUncounted(), load()])
  }
  catch {
    notification.error(t('shiftBudget.uncounted.addError'))
  }
  finally {
    addSubmitting.value = false
  }
}

function consumptionRate(allocation: AllocationResponse): number {
  const allocated = allocation.allocated_amount ?? 0
  const consumed = allocation.consumed_amount ?? 0
  if (allocated === 0) return 0
  return consumed / allocated
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  pageSize.value = event.rows
  load()
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
      <PageHeader :title="t('shiftBudget.allocation.list')" />
      <Button
        v-if="organizationId && canManageBudget"
        :label="t('shiftBudget.allocation.create')"
        icon="pi pi-plus"
        @click="openCreate"
      />
    </div>

    <Message v-if="!organizationId" severity="warn" :closable="false" class="mb-4">
      {{ t('shiftBudget.scope.selectOrganization') }}
    </Message>

    <PageLoading v-else-if="loading" />

    <DataTable
      v-else
      :value="allocations"
      striped-rows
      data-key="id"
      :paginator="total > pageSize"
      :rows="pageSize"
      :total-records="total"
      :first="page * pageSize"
      lazy
      @page="onPage"
    >
      <template #empty>
        <div class="py-8 text-center text-surface-500">{{ t('shiftBudget.allocation.empty') }}</div>
      </template>
      <Column field="id" :header="t('shiftBudget.allocation.id')" style="width: 80px" />
      <Column field="team_id" :header="t('shiftBudget.allocation.team')" style="width: 100px">
        <template #body="{ data }: { data: AllocationResponse }">
          <span>{{ data.team_id ?? '-' }}</span>
        </template>
      </Column>
      <Column field="project_id" :header="t('shiftBudget.allocation.project')" style="width: 100px">
        <template #body="{ data }: { data: AllocationResponse }">
          <span>{{ data.project_id ?? '-' }}</span>
        </template>
      </Column>
      <Column :header="t('shiftBudget.allocation.period')" style="width: 200px">
        <template #body="{ data }: { data: AllocationResponse }">
          <span class="text-sm">{{ data.period_start }} 〜 {{ data.period_end }}</span>
        </template>
      </Column>
      <Column :header="t('shiftBudget.allocation.amount')" style="width: 140px">
        <template #body="{ data }: { data: AllocationResponse }">
          <span class="font-medium">
            {{ data.allocated_amount?.toLocaleString() ?? '-' }} {{ data.currency }}
          </span>
        </template>
      </Column>
      <Column :header="t('shiftBudget.allocation.consumed')" style="width: 140px">
        <template #body="{ data }: { data: AllocationResponse }">
          <span>{{ data.consumed_amount?.toLocaleString() ?? '0' }}</span>
        </template>
      </Column>
      <Column :header="t('shiftBudget.allocation.rate')" style="width: 180px">
        <template #body="{ data }: { data: AllocationResponse }">
          <ConsumptionRateBadge :rate="consumptionRate(data)" />
        </template>
      </Column>
      <Column v-if="canManageBudget" :header="t('shiftBudget.allocation.actions')" style="width: 180px">
        <template #body="{ data }: { data: AllocationResponse }">
          <div class="flex gap-1">
            <Button
              :data-testid="`uncounted-shifts-open-${data.id}`"
              icon="pi pi-clock"
              size="small"
              severity="secondary"
              outlined
              :aria-label="t('shiftBudget.uncounted.open')"
              :title="t('shiftBudget.uncounted.open')"
              @click="openUncounted(data)"
            />
            <Button
              icon="pi pi-pencil"
              size="small"
              severity="secondary"
              @click="openEdit(data)"
            />
            <Button
              icon="pi pi-trash"
              size="small"
              severity="danger"
              outlined
              @click="openDelete(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <AllocationFormModal
      v-model:visible="formVisible"
      :allocation="editingAllocation"
      @submit-create="handleCreate"
      @submit-update="handleUpdate"
    />

    <!-- 締め後に未計上の勤務の一覧（CMP-260909-2152） -->
    <Dialog
      v-model:visible="uncountedVisible"
      :header="t('shiftBudget.uncounted.title')"
      :style="{ width: '760px' }"
      modal
    >
      <p class="mb-3 text-sm text-surface-600">{{ t('shiftBudget.uncounted.description') }}</p>
      <label v-if="uncountedMonthOptions.length > 1" class="mb-3 flex flex-col gap-1 text-sm">
        <span>{{ t('shiftBudget.uncounted.monthLabel') }}</span>
        <Select
          :model-value="uncountedMonth"
          data-testid="uncounted-month"
          :options="uncountedMonthOptions"
          option-label="label"
          option-value="value"
          class="w-48"
          @update:model-value="onUncountedMonthChange"
        />
        <span class="text-xs text-surface-500">{{ t('shiftBudget.uncounted.monthHint') }}</span>
      </label>
      <PageLoading v-if="uncountedLoading" />
      <p v-else-if="uncountedItems.length === 0" class="py-6 text-center text-surface-500">
        {{ t('shiftBudget.uncounted.empty') }}
      </p>
      <table v-else class="w-full text-sm">
        <thead>
          <tr class="text-left text-surface-500">
            <th class="py-1 pr-2">{{ t('shiftBudget.uncounted.user') }}</th>
            <th class="py-1 pr-2">{{ t('shiftBudget.uncounted.workDate') }}</th>
            <th class="py-1 pr-2 text-right">{{ t('shiftBudget.uncounted.hours') }}</th>
            <th class="py-1 pr-2 text-right">{{ t('shiftBudget.uncounted.rate') }}</th>
            <th class="py-1" />
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in uncountedItems"
            :key="`${item.slot_id}-${item.user_id}`"
            :data-testid="`uncounted-shift-row-${item.slot_id}-${item.user_id}`"
            class="border-t border-surface-200"
          >
            <td class="py-2 pr-2">{{ t('shiftBudget.uncounted.userLabel', { id: item.user_id }) }}</td>
            <td class="py-2 pr-2">{{ item.work_date }}</td>
            <td class="py-2 pr-2 text-right">{{ item.hours }}</td>
            <td class="py-2 pr-2 text-right">{{ item.hourly_rate == null ? '-' : item.hourly_rate.toLocaleString() }}</td>
            <td class="py-2 text-right">
              <Button
                :data-testid="`uncounted-shift-add-${item.slot_id}-${item.user_id}`"
                :label="t('shiftBudget.uncounted.add')"
                size="small"
                :disabled="!item.add_available"
                @click="openAdd(item)"
              />
              <p
                v-if="!item.add_available"
                :data-testid="`uncounted-shift-unavailable-${item.slot_id}-${item.user_id}`"
                class="mt-1 text-xs text-orange-600"
              >
                {{ t(`shiftBudget.uncounted.reason.${item.unavailable_reason ?? 'UNKNOWN'}`) }}
              </p>
            </td>
          </tr>
        </tbody>
      </table>
      <template #footer>
        <Button
          :label="t('shiftBudget.uncounted.close')"
          severity="secondary"
          @click="uncountedVisible = false"
        />
      </template>
    </Dialog>

    <!-- 追加の確認（初期値を埋めて、理由を入れて1操作） -->
    <Dialog
      v-model:visible="addVisible"
      :header="t('shiftBudget.uncounted.addTitle')"
      :style="{ width: '440px' }"
      modal
    >
      <div v-if="addTarget" class="flex flex-col gap-3">
        <p class="text-sm text-surface-600">
          {{ t('shiftBudget.uncounted.addSummary', {
            id: addTarget.user_id, date: addTarget.work_date, hours: addTarget.hours,
          }) }}
        </p>
        <label class="flex flex-col gap-1 text-sm">
          <span>{{ t('shiftBudget.uncounted.rateLabel') }}</span>
          <InputText v-model="addRate" data-testid="uncounted-shift-add-rate" type="number" min="1" step="1" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span>{{ t('shiftBudget.uncounted.reasonLabel') }}</span>
          <InputText
            v-model="addReason"
            data-testid="uncounted-shift-add-reason"
            maxlength="500"
            :placeholder="t('shiftBudget.uncounted.reasonPlaceholder')"
          />
        </label>
        <p class="text-xs text-surface-500">{{ t('shiftBudget.uncounted.addNote') }}</p>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button
            :label="t('shiftBudget.allocation.form.cancel')"
            severity="secondary"
            @click="addVisible = false"
          />
          <Button
            data-testid="uncounted-shift-add-confirm"
            :label="t('shiftBudget.uncounted.addConfirm')"
            :loading="addSubmitting"
            :disabled="!addReady"
            @click="confirmAdd"
          />
        </div>
      </template>
    </Dialog>

    <Dialog
      v-model:visible="deleteConfirmVisible"
      :header="t('shiftBudget.allocation.delete')"
      :style="{ width: '400px' }"
      modal
    >
      <p v-if="deleteTarget" class="py-2">
        {{ t('shiftBudget.allocation.deleteConfirm', { id: deleteTarget.id }) }}
      </p>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button
            :label="t('shiftBudget.allocation.form.cancel')"
            severity="secondary"
            @click="deleteConfirmVisible = false"
          />
          <Button
            :label="t('shiftBudget.allocation.delete')"
            severity="danger"
            @click="confirmDelete"
          />
        </div>
      </template>
    </Dialog>
  </div>
</template>
