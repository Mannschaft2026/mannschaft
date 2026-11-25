<script setup lang="ts">
/**
 * F06.4.1 個人ダッシュボードの「頑張り記録」ウィジェット（personal-effort）。
 * 既存の個人 API（GET /api/v1/effort-records/me）だけを使う。新しい BE API は持たない。
 * 今日の件数と最近の記録を出し、「記録する」「振り返る」で /my/effort-records へ導く。
 */
import dayjs from 'dayjs'
import type { EffortRecordResponse } from '~/composables/useEffortRecordApi'

const { t } = useI18n()
const api = useEffortRecordApi()
const { captureQuiet } = useErrorReport()
const { userTimezone } = useDatetime()

const RECENT_LIMIT = 5
/** 今日の件数を数える上限（API の取得上限の範囲内） */
const TODAY_LIMIT = 50

const todayCount = ref(0)
const recent = ref<EffortRecordResponse[]>([])
const loading = ref(true)
const failed = ref(false)

async function load() {
  loading.value = true
  failed.value = false
  try {
    const today = dayjs().tz(userTimezone.value).format('YYYY-MM-DD')
    const [todayRes, recentRes] = await Promise.all([
      api.list({ dateFrom: today, dateTo: today, limit: TODAY_LIMIT }),
      api.list({ limit: RECENT_LIMIT }),
    ])
    todayCount.value = todayRes.data?.length ?? 0
    recent.value = recentRes.data ?? []
  } catch (error) {
    captureQuiet(error, { context: 'WidgetPersonalEffort: 頑張り記録取得' })
    todayCount.value = 0
    recent.value = []
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    :title="t('dashboard.widgets.personalEffort.title')"
    icon="pi pi-chart-line"
    to="/my/effort-records"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <div v-if="failed" data-testid="effort-widget-error">
      <DashboardEmptyState icon="pi pi-exclamation-triangle" :message="t('dashboard.widgets.personalEffort.error')" />
    </div>
    <template v-else>
      <p class="mb-2 text-sm text-surface-600 dark:text-surface-300" data-testid="effort-widget-today">
        {{ t('dashboard.widgets.personalEffort.todayCount', { count: todayCount }) }}
      </p>
      <div v-if="recent.length > 0" data-testid="effort-widget-recent">
        <p class="mb-1 text-xs font-semibold text-surface-500">{{ t('dashboard.widgets.personalEffort.recent') }}</p>
        <ul class="divide-y divide-surface-300 dark:divide-surface-600">
          <li v-for="record in recent" :key="record.id" class="flex items-center justify-between gap-2 py-1.5 text-sm">
            <span class="truncate">{{ record.definition?.templateName }}</span>
            <span class="shrink-0 text-xs text-surface-400">{{ record.recordedOn }}</span>
          </li>
        </ul>
      </div>
      <DashboardEmptyState v-else icon="pi pi-chart-line" :message="t('dashboard.widgets.personalEffort.empty')" />
    </template>
    <div class="flex justify-end gap-3 pt-2 text-sm">
      <NuxtLink to="/my/effort-records" class="text-primary hover:underline" data-testid="effort-widget-record">
        {{ t('dashboard.widgets.personalEffort.record') }}
      </NuxtLink>
      <NuxtLink to="/my/effort-records" class="text-primary hover:underline" data-testid="effort-widget-review">
        {{ t('dashboard.widgets.personalEffort.review') }}
      </NuxtLink>
    </div>
  </DashboardWidgetCard>
</template>
