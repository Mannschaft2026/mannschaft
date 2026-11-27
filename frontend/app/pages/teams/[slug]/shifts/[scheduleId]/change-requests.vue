<script setup lang="ts">
import type { ReviewOutcome, ShiftScheduleResponse } from '~/types/shift'
import { isAcceptingChangeRequests } from '~/utils/shiftStatus'
import { useTeamMembers } from '~/composables/team/useTeamMembers'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const scheduleId = computed(() => Number(route.params.scheduleId))

const authStore = useAuthStore()
const { getSchedule } = useShiftApi()
const { getTeam } = useTeamApi()

// 審査してよいか（チーム ADMIN / DEPUTY_ADMIN）。BE（ShiftChangeRequestFacade）の判定に揃える。
// プラットフォームの systemRole ではなく、このチームでの実効ロールで判定する。
// 権限取得に失敗したときは roleName が null のままなので審査ボタンは出ない（権限なしに倒れる側）。
// roleName は実ロールのみ（運営ロールは返らない。docs/security/03_role_authority_model.md §3.5・§3.6、#4164）。
const { roleName, loadPermissions } = useRoleAccess('team', teamSlug)
const canReview = computed(() => roleName.value === 'ADMIN' || roleName.value === 'DEPUTY_ADMIN')

const {
  requests,
  isLoading,
  processingIds,
  loadError: listError,
  fetchRequests,
  reviewWithOutcome,
  withdraw,
} = useChangeRequest(scheduleId)

const { handleApiError } = useErrorHandler()
const { getAllMembers } = useTeamMembers()
/** userId → 表示名（GET /teams/{slug}/members/all）。取得失敗は通知し、一覧は「不明なユーザー」で代替する */
const memberNames = ref<Map<number, string>>(new Map())
async function loadMemberNames(): Promise<void> {
  try {
    const res = await getAllMembers(teamSlug.value)
    memberNames.value = new Map(res.data.map(m => [m.userId, m.displayName]))
  } catch (error) {
    memberNames.value = new Map()
    handleApiError(error)
  }
}

const schedule = ref<ShiftScheduleResponse | null>(null)
const loading = ref(true)
/** 初回取得の失敗 */
const initialError = ref<unknown>(null)
/**
 * 取得失敗（空状態とは区別して DashboardErrorState で出す）。
 * 初回取得に加え、作成・審査・取り下げ後の一覧再取得の失敗もここに載る。再試行は初回と同じ load。
 */
const loadError = computed(() => initialError.value ?? listError.value)

const canCreate = computed(() =>
  schedule.value ? isAcceptingChangeRequests(schedule.value.status.status) : false,
)

async function load(): Promise<void> {
  loading.value = true
  initialError.value = null
  try {
    const [scheduleRes, teamRes, fetchFailure, permissions] = await Promise.all([
      getSchedule(scheduleId.value),
      getTeam(teamSlug.value),
      fetchRequests(),
      loadPermissions(),
      loadMemberNames(),
    ])
    if (fetchFailure) {
      initialError.value = fetchFailure
      return
    }
    // 権限取得の失敗を捨てない。審査ボタンは出さないまま、取得エラーと再試行を見せる。
    if (!permissions.ok) {
      initialError.value = permissions.error
      return
    }
    // URL の slug のチームとシフト表のチームが食い違う画面は存在しないものとして扱う
    // （他チームのシフト表 ID を slug 違いで開かせない。存在オラクルを作らないため 404 相当）。
    const urlTeamId = teamRes.data.numericId
    if (urlTeamId === undefined) {
      initialError.value = new Error('team_numeric_id_missing')
      return
    }
    if (scheduleRes.teamId !== urlTeamId) {
      initialError.value = { statusCode: 404 }
      return
    }
    schedule.value = scheduleRes
  } catch (error) {
    initialError.value = error
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function onSubmitted(): Promise<void> {
  await fetchRequests()
}

/** 通知（成功・競合専用文言・汎用エラー）と一覧再取得は composable 側で済んでいる。ここでは結果だけ返す */
function onReview(
  id: number,
  decision: 'ACCEPTED' | 'REJECTED',
  comment: string | undefined,
  version: number,
): Promise<ReviewOutcome> {
  return reviewWithOutcome(id, decision, comment, version)
}

async function onWithdraw(id: number): Promise<void> {
  await withdraw(id)
}
</script>

<template>
  <div class="mx-auto max-w-4xl space-y-6 px-4 py-6">
    <!-- ページヘッダー（戻る導線は PageHeader の back-to に一本化） -->
    <PageHeader :title="$t('shift.changeRequest.title')" :back-to="`/teams/${teamSlug}/shifts`" />

    <SectionCard v-if="loadError">
      <DashboardErrorState
        :error="loadError"
        testid="change-request-load-error"
        @retry="load"
      />
    </SectionCard>

    <PageLoading v-else-if="loading" />

    <template v-else>
      <!-- 変更依頼作成フォーム。受付外（PUBLISHED 以降）は出さず案内に替える -->
      <ShiftChangeRequestForm
        v-if="canCreate"
        :schedule-id="scheduleId"
        @submitted="onSubmitted"
      />
      <p
        v-else
        data-testid="change-request-closed-notice"
        class="rounded-lg border border-surface-200 bg-surface-50 px-3 py-2 text-sm text-surface-600"
      >
        {{ $t('shift.changeRequest.closedForNew') }}
      </p>

      <!-- 変更依頼一覧 -->
      <SectionCard>
        <ShiftChangeRequestList
          :requests="requests"
          :current-user-id="authStore.currentUser?.id ?? null"
          :can-review="canReview"
          :is-loading="isLoading"
          :processing-ids="processingIds"
          :member-names="memberNames"
          :submit-review="onReview"
          @withdraw="onWithdraw"
        />
      </SectionCard>
    </template>
  </div>
</template>
