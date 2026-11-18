<script setup lang="ts">
import { useShiftBudgetAdminAccess } from '~/composables/shift/useShiftBudgetAdminAccess'
import dayjs from 'dayjs'
import type { ClosingSettingsResponse } from '~/types/shiftBudget'

/**
 * 締め日設定画面 (`/admin/shift-budget/settings`)。CMP-260909-2152 PR-α。
 *
 * <p>組織の月次締め期間の締め日（1〜28 と末日）を参照・変更する。変更は BUDGET_ADMIN のみ。
 * 適用開始に選べる最も早い期間はサーバーが返す min_effective_period_key を使い、画面では計算しない。</p>
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { userTimezone } = useDatetime()
const scopeStore = useScopeStore()
const notification = useNotification()
const api = useShiftBudgetApi()
const { canManageBudget, ensureLoaded: ensureBudgetAdminAccess } = useShiftBudgetAdminAccess()

/** 適用開始に選べる期間数（最も早い期間から数える） */
const EFFECTIVE_PERIOD_COUNT = 24
/** 締め日の値。0 は末日 */
const END_OF_MONTH = 0
const MAX_CLOSING_DAY = 28

const organizationId = computed(() => {
  if (scopeStore.current.type !== 'organization') return null
  return scopeStore.current.id
})

const settings = ref<ClosingSettingsResponse | null>(null)
const loading = ref(false)
const loadFailed = ref(false)
const submitting = ref(false)
const submitError = ref('')

const form = ref<{ day: number | null, effective: string | null, reason: string }>({
  day: null,
  effective: null,
  reason: '',
})

function dayLabel(day: number): string {
  return day === END_OF_MONTH
    ? t('shiftBudget.closingDay.endOfMonth')
    : t('shiftBudget.closingDay.dayOption', { day })
}

const dayOptions = computed(() => [
  ...Array.from({ length: MAX_CLOSING_DAY }, (_, i) => i + 1),
  END_OF_MONTH,
].map(value => ({ value, label: dayLabel(value) })))

const effectiveOptions = computed(() => {
  const min = settings.value?.min_effective_period_key
  if (!min) return []
  const first = dayjs(`${min}-01`)
  return Array.from({ length: EFFECTIVE_PERIOD_COUNT }, (_, i) => {
    const key = first.add(i, 'month').format('YYYY-MM')
    return { value: key, label: key }
  })
})

/** 期間キー M の締め日（締め日 0 は末日）。 */
function closingDate(monthKey: string, day: number): dayjs.Dayjs {
  const month = dayjs(`${monthKey}-01`)
  return day === END_OF_MONTH ? month.endOf('month') : month.date(day)
}

/** 期間キー M の期間（M-1 の締め日の翌日〜M の締め日）。 */
function periodText(monthKey: string, previousDay: number, day: number): string {
  const previousMonth = dayjs(`${monthKey}-01`).subtract(1, 'month').format('YYYY-MM')
  const start = closingDate(previousMonth, previousDay).add(1, 'day')
  const end = closingDate(monthKey, day)
  return `${start.format('YYYY-MM-DD')} – ${end.format('YYYY-MM-DD')}`
}

/**
 * 期間キー M に有効な締め日。変更履歴（effective_period_key <= M のうち revision 最大）から解決し、無ければ末日。
 * 予約済みの将来変更も履歴に含まれるので、今日の締め日ではなく期間ごとに引く（BE の期間算出と同じ規則）。
 */
function closingDayOf(monthKey: string): number {
  let best: { revision: number, day: number } | null = null
  for (const c of settings.value?.changes ?? []) {
    if (c.effective_period_key <= monthKey && (best === null || c.revision > best.revision)) {
      best = { revision: c.revision, day: c.closing_day }
    }
  }
  return best === null ? END_OF_MONTH : best.day
}

const preview = computed(() => {
  const { day, effective } = form.value
  if (!settings.value || day === null || effective === null) return null
  const previousMonth = dayjs(`${effective}-01`).subtract(1, 'month').format('YYYY-MM')
  const previousDay = closingDayOf(previousMonth)
  return {
    before: periodText(effective, previousDay, closingDayOf(effective)),
    after: periodText(effective, previousDay, day),
  }
})

const canSubmit = computed(() =>
  canManageBudget.value
  && !submitting.value
  && form.value.day !== null
  && form.value.effective !== null
  && form.value.reason.trim() !== '',
)

async function load() {
  if (!organizationId.value) {
    settings.value = null
    return
  }
  loading.value = true
  loadFailed.value = false
  try {
    settings.value = await api.getClosingSettings(organizationId.value)
  }
  catch {
    settings.value = null
    loadFailed.value = true
  }
  finally {
    loading.value = false
  }
}

function resetForm() {
  form.value = { day: null, effective: null, reason: '' }
}

async function submit() {
  if (!organizationId.value || !settings.value || !canSubmit.value) return
  const { day, effective, reason } = form.value
  if (day === null || effective === null) return
  submitting.value = true
  submitError.value = ''
  try {
    await api.changeClosingSetting(organizationId.value, {
      expected_revision: settings.value.revision,
      closing_day: day,
      effective_period_key: effective,
      reason: reason.trim(),
    })
    notification.success(t('shiftBudget.closingDay.success'))
    resetForm()
    await load()
  }
  catch (e) {
    const status = (e as { statusCode?: number, status?: number }).statusCode
      ?? (e as { status?: number }).status
    submitError.value = status === 409
      ? t('shiftBudget.closingDay.conflict')
      : t('shiftBudget.closingDay.error')
  }
  finally {
    submitting.value = false
  }
}

function formatDateTime(value: string): string {
  return dayjs(value).tz(userTimezone.value).format('YYYY-MM-DD HH:mm')
}

watch(organizationId, () => {
  resetForm()
  load()
  ensureBudgetAdminAccess()
})
onMounted(() => {
  load()
  ensureBudgetAdminAccess()
})
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <PageHeader
      :title="t('shiftBudget.closingDay.title')"
      :subtitle="t('shiftBudget.closingDay.description')"
      back-to="/admin/shift-budget/dashboard"
    />

    <Message v-if="!organizationId" severity="warn" :closable="false" class="my-4">
      {{ t('shiftBudget.closingDay.selectOrganization') }}
    </Message>

    <PageLoading v-else-if="loading && !settings" />

    <Message
      v-else-if="loadFailed"
      severity="error"
      :closable="false"
      class="my-4"
      data-testid="closing-settings-load-error"
    >
      {{ t('shiftBudget.closingDay.loadError') }}
    </Message>

    <div v-else-if="settings" class="mt-4 flex flex-col gap-4">
      <SectionCard>
        <p class="text-sm text-surface-500">{{ t('shiftBudget.closingDay.current') }}</p>
        <p class="text-2xl font-bold" data-testid="closing-current">{{ dayLabel(settings.closing_day) }}</p>
      </SectionCard>

      <SectionCard :title="t('shiftBudget.closingDay.submit')">
        <Message
          v-if="!canManageBudget"
          severity="info"
          :closable="false"
          class="mb-3"
          data-testid="closing-readonly-notice"
        >
          {{ t('shiftBudget.closingDay.readOnly') }}
        </Message>
        <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.closingDay.dayLabel') }}</label>
            <Select
              v-model="form.day"
              data-testid="closing-day-select"
              :options="dayOptions"
              option-label="label"
              option-value="value"
              :disabled="!canManageBudget"
              class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.closingDay.effectivePeriod') }}</label>
            <Select
              v-model="form.effective"
              data-testid="closing-effective-select"
              :options="effectiveOptions"
              option-label="label"
              option-value="value"
              :disabled="!canManageBudget"
              class="w-full"
            />
          </div>
        </div>
        <div class="mt-4">
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.closingDay.reason') }}</label>
          <Textarea
            v-model="form.reason"
            data-testid="closing-reason"
            rows="3"
            :disabled="!canManageBudget"
            class="w-full"
          />
          <p v-if="canManageBudget && form.reason.trim() === ''" class="mt-1 text-xs text-surface-500">
            {{ t('shiftBudget.closingDay.reasonRequired') }}
          </p>
        </div>

        <div v-if="preview" class="mt-4 rounded-lg bg-surface-50 p-3 text-sm dark:bg-surface-900">
          <p class="mb-2 font-medium">{{ t('shiftBudget.closingDay.previewTitle') }}</p>
          <p>
            <span class="text-surface-500">{{ t('shiftBudget.closingDay.previewBefore') }}</span>
            <span class="ml-2" data-testid="closing-preview-before">{{ preview.before }}</span>
          </p>
          <p>
            <span class="text-surface-500">{{ t('shiftBudget.closingDay.previewAfter') }}</span>
            <span class="ml-2" data-testid="closing-preview-after">{{ preview.after }}</span>
          </p>
        </div>

        <Message
          v-if="submitError"
          severity="error"
          :closable="false"
          class="mt-4"
          data-testid="closing-settings-error"
        >
          {{ submitError }}
        </Message>

        <div class="mt-4 flex justify-end">
          <Button
            data-testid="closing-submit"
            :label="t('shiftBudget.closingDay.submit')"
            :loading="submitting"
            :disabled="!canSubmit"
            @click="submit"
          />
        </div>
      </SectionCard>

      <SectionCard :title="t('shiftBudget.closingDay.historyTitle')">
        <p
          v-if="settings.changes.length === 0"
          class="py-2 text-center text-surface-500"
          data-testid="closing-history-empty"
        >
          {{ t('shiftBudget.closingDay.historyEmpty') }}
        </p>
        <div v-else class="overflow-x-auto">
          <table class="w-full text-left text-sm">
            <thead>
              <tr class="border-b border-surface-200 dark:border-surface-700">
                <th class="py-2 pr-3">{{ t('shiftBudget.closingDay.revision') }}</th>
                <th class="py-2 pr-3">{{ t('shiftBudget.closingDay.appliedFrom') }}</th>
                <th class="py-2 pr-3">{{ t('shiftBudget.closingDay.dayLabel') }}</th>
                <th class="py-2 pr-3">{{ t('shiftBudget.closingDay.previousDay') }}</th>
                <th class="py-2 pr-3">{{ t('shiftBudget.closingDay.reason') }}</th>
                <th class="py-2">{{ t('shiftBudget.closingDay.changedAt') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="change in settings.changes"
                :key="change.revision"
                class="border-b border-surface-100 dark:border-surface-800"
                :data-testid="`closing-history-row-${change.revision}`"
              >
                <td class="py-2 pr-3">{{ change.revision }}</td>
                <td class="py-2 pr-3">{{ change.effective_period_key }}</td>
                <td class="py-2 pr-3">{{ dayLabel(change.closing_day) }}</td>
                <td class="py-2 pr-3">{{ dayLabel(change.previous_closing_day) }}</td>
                <td class="py-2 pr-3">{{ change.reason }}</td>
                <td class="py-2 whitespace-nowrap">{{ formatDateTime(change.created_at) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </SectionCard>
    </div>
  </div>
</template>
