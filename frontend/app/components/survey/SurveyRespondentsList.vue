<script setup lang="ts">
/**
 * F05.4 アンケート回答者・未回答者一覧
 *
 * 親コンポーネントから scopeType / scopeId / surveyId を受け取り、
 * GET /api/v1/{scope}/{id}/surveys/{surveyId}/respondents を取得して表示する。
 *
 * 督促 UI: canRemind=true の場合のみ未回答タブに「督促送信」ボタンを表示。
 *   - Backend API: POST /api/v1/surveys/{surveyId}/remind
 *   - 認可・回数制限・24時間クールダウン等の判定は Backend が行う。
 *   - 画面には「残り N/M 回」を常に出し、送れないときはボタンを無効にして理由を出す。
 *     上限回数・待機時刻は BE が返す distribution の値だけで判定し、FE は定数を持たない。
 *   - 送信が成功したら emit('reminded') で親へ詳細の取り直しを依頼する。
 *   - 4xx の業務エラー（待機中・上限・締切・権限）で失敗したときと、再読込ボタンを押したときも
 *     emit('refresh') で親へ詳細（distribution）の取り直しを依頼し、表示を BE に追従させる。
 *     5xx・ネットワーク断では取り直さない（5xx の表示は useApi が一元化している）。
 *   - 次に送れる時刻は分単位に切り上げて表示する（切り捨てると、その時刻に見てもまだ押せないため）。
 */
import dayjs from 'dayjs'
import type { RespondentItem, SurveyDistributionDto, SurveyStatus } from '~/types/survey'

const props = withDefaults(
  defineProps<{
    scopeType: 'TEAM' | 'ORGANIZATION'
    scopeId: string
    surveyId: number
    /** 督促の権限だけを表す（公開中かどうか・回数・待機は surveyStatus / distribution で判定） */
    canRemind?: boolean
    surveyStatus?: SurveyStatus
    distribution?: SurveyDistributionDto | null
  }>(),
  { canRemind: false, surveyStatus: undefined, distribution: null },
)

const emit = defineEmits<{
  /** 督促の送信に成功した（親が詳細を取り直す） */
  reminded: []
  /** 失敗時・再読込時に、親へ詳細（distribution）の取り直しを依頼する */
  refresh: []
}>()

const { t } = useI18n()
const { getRespondents, remindRespondents } = useSurveyApi()
const { error: showError, success: showSuccess } = useNotification()
const { handleApiError } = useErrorHandler()
const { relativeTime } = useRelativeTime()
const { formatDateTime } = useDatetime()

const respondents = ref<RespondentItem[]>([])
const loading = ref(false)
const loadError = ref(false)
const reminding = ref(false)

// タブ: 'responded' | 'unresponded'
const activeTab = ref<'responded' | 'unresponded'>('responded')

const responded = computed(() => respondents.value.filter((r) => r.hasResponded))
const unresponded = computed(() => respondents.value.filter((r) => !r.hasResponded))
const totalCount = computed(() => respondents.value.length)
const respondedCount = computed(() => responded.value.length)

const visibleList = computed<RespondentItem[]>(() =>
  activeTab.value === 'responded' ? responded.value : unresponded.value,
)

const tabOptions = computed(() => [
  { label: t('surveys.respondents.tabResponded', { count: respondedCount.value }), value: 'responded' },
  { label: t('surveys.respondents.tabUnresponded', { count: totalCount.value - respondedCount.value }), value: 'unresponded' },
])

async function loadRespondents() {
  loading.value = true
  loadError.value = false
  try {
    const res = await getRespondents(props.scopeType, props.scopeId, props.surveyId)
    respondents.value = res.data
  } catch {
    loadError.value = true
    showError(t('surveys.respondents.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function sendReminder() {
  if (reminding.value) return
  if (unresponded.value.length === 0) return
  reminding.value = true
  try {
    const res = await remindRespondents(props.surveyId)
    const remindedCount = res.data?.remindedCount ?? unresponded.value.length
    const remaining = res.data?.remainingRemindQuota
    const detail = remaining !== undefined ? t('surveys.respondents.remindRemaining', { count: remaining }) : undefined
    showSuccess(t('surveys.respondents.remindSuccess', { count: remindedCount }), detail)
    // 督促送信後は最新状態（lastRemindedAt 等）を反映するため一覧を再取得する。
    // 失敗時は再取得しない（一覧は変化しない想定 + エラートーストのみ表示）。
    await loadRespondents()
    emit('reminded')
  } catch (e) {
    const apiError = e as { statusCode?: number; data?: { error?: { code?: string } } }
    const status = apiError.statusCode
    if (apiError.data?.error?.code && status !== undefined && status >= 400 && status < 500) {
      // 別タブで先に送られた等で BE が業務エラー（待機中・上限・締切・権限）を返した。
      // 業務コードに応じた文言で知らせ、古い表示のまま押し続けられないよう詳細を取り直す。
      handleApiError(e, 'survey-remind')
      emit('refresh')
    } else if (status === undefined || status < 500) {
      // ネットワーク断など。表示は変わっていない想定なので取り直さない。
      showError(t('surveys.respondents.remindFailed'), t('surveys.respondents.remindFailedDetail'))
    }
    // 5xx の表示は useApi が一元化している
  } finally {
    reminding.value = false
  }
}

/** 残り回数。BE が上限を返していない間は null（表示しない・上限扱いもしない）。 */
const remindRemaining = computed<number | null>(() => {
  const max = props.distribution?.remindMaxCount
  if (max == null) return null
  return Math.max(0, max - (props.distribution?.manualRemindCount ?? 0))
})

/** 督促ヒント。上限回数を BE から受け取れていないときは回数を含まない文言にする。 */
const remindHintText = computed<string>(() => {
  const max = props.distribution?.remindMaxCount
  return max == null
    ? t('surveys.respondents.remindHintNoMax', { count: unresponded.value.length })
    : t('surveys.respondents.remindHint', { count: unresponded.value.length, max })
})

/**
 * 現在時刻。`remindBlockedReason` の待機判定が時間の経過で再評価されるよう ref で持ち、
 * `nextRemindableAt` に達した時点でタイマーが更新する（computed 内で Date.now() を直読みしない）。
 */
const nowMs = ref(Date.now())
let cooldownTimer: ReturnType<typeof setTimeout> | null = null
/** setTimeout が受け付ける最大遅延（約24.8日）。超える待機は到達のたびに張り直す。 */
const MAX_TIMEOUT_MS = 2 ** 31 - 1

function clearCooldownTimer() {
  if (cooldownTimer !== null) {
    clearTimeout(cooldownTimer)
    cooldownTimer = null
  }
}

/** nextRemindableAt までの待機が終わる時刻に現在時刻を更新する（SSR では動かさない）。 */
function scheduleCooldownRefresh() {
  clearCooldownTimer()
  nowMs.value = Date.now()
  const next = props.distribution?.nextRemindableAt
  if (!next) return
  const wait = new Date(next).getTime() - nowMs.value
  if (wait <= 0) return
  cooldownTimer = setTimeout(() => {
    cooldownTimer = null
    scheduleCooldownRefresh()
  }, Math.min(wait, MAX_TIMEOUT_MS))
}

watch(() => props.distribution?.nextRemindableAt, () => {
  if (import.meta.client) scheduleCooldownRefresh()
})
onBeforeUnmount(clearCooldownTimer)

/**
 * 秒以下が残る時刻を次の分へ切り上げる（23:43:40 → 23:44）。
 * 分単位で表示するため、切り捨てると表示時刻に見ても押せない。ボタンの有効判定は元の時刻（秒単位）のまま。
 */
function ceilToMinute(iso: string): string {
  const d = dayjs(iso)
  if (!d.isValid() || (d.second() === 0 && d.millisecond() === 0)) return iso
  return d.add(1, 'minute').startOf('minute').toISOString()
}

/** 督促を送れない理由（送れるときは null）。公開中でない > 上限 > 待機 の順で優先する。 */
const remindBlockedReason = computed<string | null>(() => {
  if (props.surveyStatus !== 'PUBLISHED') return t('surveys.respondents.remindBlockedNotPublished')
  if (remindRemaining.value === 0) return t('surveys.respondents.remindBlockedQuota')
  const next = props.distribution?.nextRemindableAt
  if (next && new Date(next).getTime() > nowMs.value) {
    return t('surveys.respondents.remindBlockedCooldown', { at: formatDateTime(ceilToMinute(next)) })
  }
  return null
})

function getInitial(name: string): string {
  const trimmed = name.trim()
  if (trimmed.length === 0) return '?'
  return trimmed.charAt(0).toUpperCase()
}

onMounted(() => {
  scheduleCooldownRefresh()
  loadRespondents()
})

/** 再読込ボタン: 回答者一覧に加え、残り回数・次回時刻（distribution）も親に取り直させる */
async function reload() {
  emit('refresh')
  await loadRespondents()
}

defineExpose({ refresh: loadRespondents })
</script>

<template>
  <div class="flex flex-col gap-3" data-testid="survey-respondents-list">
    <!-- ヘッダー: サマリー + 再読込 -->
    <div class="flex items-center justify-between gap-2">
      <div class="text-sm text-surface-700" data-testid="respondents-summary">
        {{ t('surveys.respondents.summary', { responded: respondedCount, total: totalCount }) }}
      </div>
      <Button
        icon="pi pi-refresh"
        severity="secondary"
        text
        rounded
        size="small"
        :disabled="loading"
        :aria-label="t('surveys.respondents.reload')"
        data-testid="respondents-refresh"
        class="min-h-11 min-w-11 shrink-0"
        @click="reload"
      />
    </div>

    <!-- タブ切替（PrimeVue SelectButton） -->
    <SelectButton
      v-model="activeTab"
      :options="tabOptions"
      option-label="label"
      option-value="value"
      :allow-empty="false"
      class="self-start"
      :pt="{ pcToggleButton: { root: { class: 'min-h-11 min-w-11' } } }"
      data-testid="respondents-filter"
    />

    <!-- 督促ヒント / 督促ボタン（未回答タブのみ） -->
    <div v-if="activeTab === 'unresponded'" class="flex flex-col gap-2">
      <div
        v-if="canRemind"
        class="flex items-center justify-between gap-2 rounded-lg border border-surface-200 bg-surface-50 px-3 py-2 dark:border-surface-700 dark:bg-surface-800"
      >
        <div class="flex min-w-0 flex-col gap-0.5">
          <span
            v-if="!remindBlockedReason"
            class="text-xs text-surface-600"
            data-testid="respondents-remind-hint"
          >
            {{ remindHintText }}
          </span>
          <span
            v-if="remindRemaining !== null"
            class="text-xs font-medium text-surface-700"
            data-testid="respondents-remind-quota"
          >
            {{ t('surveys.respondents.remindQuota', { remaining: remindRemaining, max: distribution?.remindMaxCount }) }}
          </span>
          <span
            v-if="remindBlockedReason"
            class="text-xs text-orange-600"
            data-testid="respondents-remind-blocked-reason"
          >
            {{ remindBlockedReason }}
          </span>
        </div>
        <Button
          :label="t('surveys.respondents.remindButton')"
          icon="pi pi-send"
          size="small"
          :loading="reminding"
          :disabled="unresponded.length === 0 || loading || remindBlockedReason !== null"
          data-testid="respondents-remind-button"
          class="min-h-11 min-w-11 shrink-0 whitespace-nowrap"
          @click="sendReminder"
        />
      </div>
      <div
        v-else
        class="rounded-lg border border-dashed border-surface-200 bg-surface-50 px-3 py-2 text-xs text-surface-500 dark:border-surface-700 dark:bg-surface-800"
      >
        <i class="pi pi-info-circle mr-1" />
        {{ t('surveys.respondents.remindAutoNotice') }}
      </div>
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-8">
      <LoadingBounce />
    </div>

    <!-- エラー -->
    <div
      v-else-if="loadError"
      class="flex flex-col items-center gap-2 py-8 text-center"
    >
      <i class="pi pi-exclamation-triangle text-3xl text-red-500" />
      <p class="text-sm text-surface-500">{{ t('surveys.respondents.loadErrorTitle') }}</p>
      <Button :label="t('surveys.respondents.retry')" icon="pi pi-refresh" size="small" class="min-h-11 min-w-11" @click="loadRespondents" />
    </div>

    <!-- リスト本体 -->
    <ul v-else-if="visibleList.length > 0" class="flex flex-col gap-1">
      <li
        v-for="item in visibleList"
        :key="item.userId"
        class="flex items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 px-3 py-2 dark:border-surface-700 dark:bg-surface-800"
        :data-testid="`respondent-item-${item.userId}`"
      >
        <!-- アバター -->
        <Avatar
          v-if="item.avatarUrl"
          :image="item.avatarUrl"
          shape="circle"
          size="normal"
        />
        <Avatar
          v-else
          :label="getInitial(item.fullName)"
          shape="circle"
          size="normal"
          class="bg-primary-100 text-primary-700"
        />

        <!-- 名前 -->
        <span class="flex-1 truncate text-sm text-surface-800">
          {{ item.fullName }}
        </span>

        <!-- 回答済み: 相対時刻 / 未回答: バッジ -->
        <span
          v-if="item.hasResponded && item.respondedAt"
          class="text-xs text-surface-500"
        >
          {{ relativeTime(item.respondedAt) }}
        </span>
        <Badge v-else-if="item.hasResponded" :value="t('surveys.respondents.respondedBadge')" severity="success" />
        <Badge v-else :value="t('surveys.respondents.unrespondedBadge')" severity="warn" />
      </li>
    </ul>

    <!-- 空状態 -->
    <div v-else class="flex flex-col items-center gap-2 py-10 text-center">
      <i class="pi pi-users text-3xl text-surface-300" />
      <p class="text-sm text-surface-400">
        {{ activeTab === 'responded' ? t('surveys.respondents.emptyResponded') : t('surveys.respondents.emptyUnresponded') }}
      </p>
    </div>
  </div>
</template>
