<template>
  <div class="flex flex-col h-screen overflow-hidden">
    <!-- ヘッダー。見出し・ステータス・ボタン群は PageHeader に寄せ、狭い幅では折り返す -->
    <div class="flex-shrink-0 border-b border-surface-200 bg-white px-4 pt-2 pb-3" data-testid="shift-board-header">
      <PageHeader
        size="sm"
        :title="$t('shift.board.title')"
        :subtitle="schedule?.content.title"
        :back-to="`/teams/${teamSlug}/shifts`"
        :back-label="$t('shift.board.backToList')"
        class="!mb-0"
      >
        <ShiftStatusBadge v-if="schedule" :status="schedule.status.status" />
      </PageHeader>

      <!-- 操作群はシフト表が取得できたときだけ出す（取得失敗時に死んだ導線を残さない）。
           PageHeader の actions 欄に入れると md 以上で見出しが押し潰されて縦書き状に折れるため、
           見出しの下の折り返す行に置く。 -->
      <div v-if="schedule" class="mt-2 flex flex-wrap items-center gap-2" data-testid="shift-board-actions">
        <!-- PDF エクスポートボタン（SUPPORTER ロールは非表示） -->
        <ShiftPdfExportButton
          v-if="!isSupporter"
          :schedule-id="scheduleId"
        />

        <!-- 変更依頼ページへのリンク -->
        <NuxtLink :to="`/teams/${teamSlug}/shifts/${scheduleId}/change-requests`">
          <Button
            :label="$t('shift.changeRequest.title')"
            icon="pi pi-list"
            severity="secondary"
            outlined
            size="small"
          />
        </NuxtLink>

        <!-- md 未満では未割当プールを常時表示せず、このボタンで開く（DnD は md 以上のみ） -->
        <Button
          v-if="canAssign"
          :label="$t('shift.board.memberPool')"
          icon="pi pi-users"
          severity="secondary"
          outlined
          size="small"
          class="md:hidden"
          data-testid="member-pool-open"
          @click="memberDrawerVisible = true"
        />

        <!-- 自動割当は割当権限のある人専用（BE が per-scope 認可で 403 を返す）。
             フラグ FEATURE_SHIFT_AUTO_ASSIGN_ENABLED が OFF のときは
             DOM から消さず disabled ＋ 理由ツールチップで「停止中」と示す
             （docs/features/F03.5_shift/06_manual_authoring.md §11.1.2-4）。 -->
        <span
          v-if="canAssign"
          v-tooltip.bottom="autoAssignEnabled ? '' : t('shift.autoAssign.disabledReason')"
          :title="autoAssignEnabled ? '' : t('shift.autoAssign.disabledReason')"
          :data-testid="autoAssignEnabled ? undefined : 'auto-assign-disabled-reason'"
          class="flex flex-wrap items-center gap-2"
        >
          <Tag
            v-if="!autoAssignEnabled"
            :value="t('shift.autoAssign.disabledBadge')"
            severity="secondary"
            class="text-xs"
            data-testid="auto-assign-disabled-badge"
          />
          <Button
            :label="$t('shift.autoAssign.history')"
            icon="pi pi-history"
            severity="secondary"
            outlined
            size="small"
            :disabled="!autoAssignEnabled"
            data-testid="auto-assign-history-button"
            @click="historyVisible = true"
          />
          <Button
            :label="$t('shift.autoAssign.button')"
            icon="pi pi-bolt"
            size="small"
            :disabled="!autoAssignEnabled"
            data-testid="auto-assign-button"
            @click="autoAssignVisible = true"
          />
        </span>
      </div>
    </div>

    <!-- シフト表の取得中 -->
    <PageLoading v-if="scheduleLoading" />

    <!-- シフト表の取得失敗。403/404 は所属外へ実在を示唆しないよう同じ表示に畳む -->
    <div
      v-else-if="scheduleError !== undefined"
      class="flex flex-col items-center gap-2 px-4"
      data-testid="shift-board-load-error"
    >
      <DashboardErrorState
        :error="scheduleError"
        :kind="scheduleErrorKind"
        @retry="initialize"
      />
    </div>

    <!-- メインコンテンツ -->
    <div v-else class="flex min-h-0 flex-1 overflow-hidden">
      <!-- シフトボード -->
      <div class="min-w-0 flex-1 overflow-hidden">
        <ShiftBoardMatrix
          :slots="slots"
          :positions="positions"
          :local-assignments="localAssignments"
          :member-map="memberMap"
          :pending-run="pendingRun"
          :schedule-version="scheduleVersion"
          :can-assign="canAssign"
          :busy-slot-ids="busySlotIds"
          @drop-user="onDropUser"
          @remove-user="onRemoveUser"
          @add-user="onOpenAddDialog"
          @confirm-auto-assign="onConfirmAutoAssignWithNote"
          @revoke-auto-assign="onRevokeAutoAssign"
        />
      </div>

      <!-- 未割当メンバープール（md 以上のみ常時表示。割当権限が無ければ出さない） -->
      <div v-if="canAssign" class="hidden w-60 flex-shrink-0 md:block" data-testid="member-pool-aside">
        <ShiftMemberPool
          :members="unassignedMembers"
          :loading="membersLoading"
          :error="membersError"
          @retry="loadMembers"
          @drag-start="draggingUserId = $event"
        />
      </div>
    </div>

    <!-- モーダル群 -->
    <ShiftAutoAssignModal
      v-model:visible="autoAssignVisible"
      :is-running="isRunning"
      @run="onRunAutoAssign"
    />

    <ShiftAutoAssignRunHistory
      v-if="historyVisible"
      :runs="runs"
    />

    <!-- md 未満の未割当メンバー一覧（引き出し）。DnD はできないので一覧のみ -->
    <Dialog
      v-if="canAssign"
      v-model:visible="memberDrawerVisible"
      :header="$t('shift.board.memberPool')"
      modal
      :style="{ width: 'min(90vw, 360px)' }"
      data-testid="member-pool-drawer"
    >
      <div class="h-[60vh]">
        <ShiftMemberPool
          :members="unassignedMembers"
          :loading="membersLoading"
          :error="membersError"
          :draggable="false"
          @retry="loadMembers"
        />
      </div>
    </Dialog>

    <!-- メンバー追加ダイアログ（枠ごとの追加） -->
    <ShiftAddMemberDialog
      v-if="canAssign"
      v-model:visible="addDialogVisible"
      :target-slot="addTargetSlot"
      :slots="slots"
      :members="allMembers"
      :assignments="localAssignments"
      :busy="addTargetSlot !== null && busySlotIds.includes(addTargetSlot.id)"
      @select="onAddUserFromDialog"
    />
  </div>
</template>

<script setup lang="ts">
import type {
  ShiftSlotResponse,
  ShiftPositionResponse,
  ShiftScheduleResponse,
  AssignmentRun,
  AssignmentParameters,
  AssignmentStrategyType,
} from '~/types/shift'
import type { LoadErrorKind } from '~/utils/loadError'
import { getLoadErrorHttpStatus } from '~/utils/loadError'

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const scheduleId = computed(() => Number(route.params.scheduleId))

// F00.5 Phase 5: チームレベルの SUPPORTER 判定を useRoleAccess 経由に切替
// 旧実装: authStore.currentUser?.systemRole === 'SUPPORTER'
//   → systemRole はプラットフォームロール（SYSTEM_ADMIN など）であり、
//     チームの SUPPORTER を判定するのは誤りだった。
// 新実装: /api/v1/teams/{id}/me/permissions から取得した roleName で判定する。
const { roleName, can, loadPermissions } = useRoleAccess('team', teamSlug)
const isSupporter = computed(() => roleName.value === 'SUPPORTER')
// 割当操作（枠への追加・外す・未割当プール・自動割当）を出すのは、当該チームの ADMIN と、
// MANAGE_SHIFTS を持つ DEPUTY_ADMIN だけ（CMP-261125-1336）。それ以外は閲覧のみ。
// 出すと必ず 403 になる死んだ導線になるため、DOM に出さない。BE 側の MANAGE_SHIFTS 化は別戦役で、本件は FE の出し分けのみ。
//
// 【意図的に SYSTEM_ADMIN を含めない等値比較】(CMP-260909-1509)
// 実効ロール解決は最強ロールの 1 値を返すため、SYSTEM_ADMIN 兼任者の roleName は 'SYSTEM_ADMIN' になり、
// 以下の等値比較には当たらない。「運営専用アカウントには一般のチーム運営導線を出さない」という
// マスター確定の設計判断（docs/security/03_role_authority_model.md §3.5）で、shifts.vue の入口判定とも一致させる。
// BE は SYSTEM_ADMIN を別途常に許可しており、この UI 非表示は認可の代替ではない。
// roleName の包含判定（hasRoleOrAbove 的）に書き換えないこと。
const canAssign = computed(
  () => roleName.value === 'ADMIN' || (roleName.value === 'DEPUTY_ADMIN' && can('MANAGE_SHIFTS')),
)

// 自動割当は 2026-09-09 の方針転換で既定 OFF（BE も ShiftAutoAssignController の
// クラスレベル @RequireFeature で 6 経路すべてを FEATURE_GATE_001 で拒否する）。
// FE は導線を消さず「停止中」として残す。GATE_ROUTE_MAP には足さない
// （足すと /teams/*/shifts 全体が巻き添えで遮断されるため。設計書 §11.1.2-5）。
const { enabled: autoAssignEnabled } = useFeatureFlag('FEATURE_SHIFT_AUTO_ASSIGN_ENABLED')

const shiftApi = useShiftApi()
const teamApi = useTeamApi()
const { t } = useI18n()
const { handleApiError } = useErrorHandler()
const { showError } = useNotification()
const { captureQuiet } = useErrorReport()
const { runs, isRunning, runAutoAssign, confirmAutoAssign, revokeAutoAssign, fetchRuns } =
  useAutoAssign(scheduleId)

// データ
const schedule = ref<ShiftScheduleResponse | null>(null)
const slots = ref<ShiftSlotResponse[]>([])
const { localAssignments, loadSlots, moveUser, addUser, removeUser } = useShiftBoard(scheduleId, slots)
const positions = ref<ShiftPositionResponse[]>([])
const allMembers = ref<Array<{ userId: number; displayName: string; avatarUrl: string | null }>>([])
const membersLoading = ref(true)
const membersError = shallowRef<unknown>()
const scheduleVersion = ref(0)
// シフト表の取得状態。ボード本体（枠・メンバー欄）はシフト表が取れてから初めて描画する。
const scheduleLoading = ref(true)
const scheduleError = shallowRef<unknown>()
// 403 と 404 を同じ「見つからない／表示できない」に畳み、所属外にシフト表の実在を漏らさない。
// それ以外（通信断・5xx）は自動判定に任せ、再試行を出す。
const scheduleErrorKind = computed((): LoadErrorKind | undefined => {
  const status = getLoadErrorHttpStatus(scheduleError.value)
  return status === 403 || status === 404 ? 'notFoundOrForbidden' : undefined
})

// UI 状態
const autoAssignVisible = ref(false)
const historyVisible = ref(false)
const addDialogVisible = ref(false)
const memberDrawerVisible = ref(false)
const addUserSlotId = ref<number | null>(null)
const draggingUserId = ref<number | null>(null)
// 割当操作の処理中の枠。同じ枠への二重送信（二度押し）を防ぐ。
const busySlotIds = ref<number[]>([])

const addTargetSlot = computed(
  (): ShiftSlotResponse | null => slots.value.find((slot) => slot.id === addUserSlotId.value) ?? null,
)

// 未確認の自動割当結果
const pendingRun = computed((): AssignmentRun | null => {
  const succeeded = runs.value.find((r) => r.status === 'SUCCEEDED')
  return succeeded ?? null
})

// メンバーマップ（userId -> メンバー情報）
const memberMap = computed(() => {
  const map: Record<number, { userId: number; displayName: string; avatarUrl: string | null }> = {}
  allMembers.value.forEach((m) => {
    map[m.userId] = m
  })
  return map
})

// 未割当メンバー（全スロットに割り当て済みでないメンバー）
const assignedUserIds = computed(() => {
  const ids = new Set<number>()
  Object.values(localAssignments.value).forEach((userIds) => {
    userIds.forEach((id) => ids.add(id))
  })
  slots.value.forEach((slot) => {
    const local = localAssignments.value[slot.id]
    if (local === undefined) {
      // assignedUserIds は number[] 型
      ;(slot.assignedUserIds ?? []).forEach((userId) => ids.add(userId))
    }
  })
  return ids
})

const unassignedMembers = computed(() =>
  allMembers.value.filter((m) => !assignedUserIds.value.has(m.userId)),
)

// 初期データ取得
onMounted(initialize)

async function initialize(): Promise<void> {
  // ⑤ ポジション取得は BE の @RequestParam teamId が必須で、schedule.teamId（数値）が
  // 判明してから叩く必要があるため、loadSchedule だけ先行させてから残りを並行取得する。
  scheduleLoading.value = true
  scheduleError.value = undefined
  try {
    await loadSchedule()
    // URL の slug のチームとシフト表のチームが食い違う画面は存在しないものとして扱う
    // （slug 違いで他チームのシフト表を開かせると、slug 側チームのメンバーが「未割当メンバー」に並び、
    // 他チームのユーザーを割り当てる導線になる。存在オラクルを作らないため 404 相当。change-requests.vue と同じ）。
    await assertScheduleBelongsToUrlTeam()
  } catch (error) {
    // 所属外の直打ち（404/403）や通信断で取得できないとき、例外を未処理のまま捨てると
    // 後続の取得に到達せずメンバー欄が読み込み中のまま残る。画面の状態として持ち、
    // 取得失敗表示に切り替える。BE の文言はトーストに出さず（実在の示唆を避ける）記録だけ残す。
    scheduleError.value = error
    captureQuiet(error, { context: 'ShiftBoard: シフト表取得' })
    return
  } finally {
    scheduleLoading.value = false
  }
  // 枠・ポジション・権限のどれかが取れないボードは、枠が無いボードと見分けがつかず、
  // 権限不明のまま操作できてしまう。例外を伝播させず、取得失敗の状態として持つ。
  scheduleLoading.value = true
  try {
    const [, , , permissionResult] = await Promise.all([loadSlots(), loadPositions(), loadMembers(), loadPermissions()])
    // loadPermissions は失敗しても例外を投げず ok:false を返す。権限不明のままボードを出さない。
    if (!permissionResult.ok) throw permissionResult.error
    // 認可根治 Wave7: 実行履歴 API は管理者専用になったため、権限解決後に管理者のときだけ取得する。
    // 従来どおり Promise.all に混ぜたままだと、一般メンバーでは 403 で Promise.all ごと失敗し
    // ボード画面全体が描画されなくなる（エラーを握りつぶさず、そもそも呼ばない形で解消する）。
    // フラグ OFF のときは実行履歴 API もゲートで 403（FEATURE_GATE_001）になるため呼ばない。
    // 例外を握りつぶすのではなく、停止中の機能には最初から到達しない形で解消する。
    if (canAssign.value && autoAssignEnabled.value) {
      await fetchRuns()
    }
  } catch (error) {
    scheduleError.value = error
    captureQuiet(error, { context: 'ShiftBoard: ボード構成データ取得' })
  } finally {
    scheduleLoading.value = false
  }
}

async function assertScheduleBelongsToUrlTeam(): Promise<void> {
  const teamRes = await teamApi.getTeam(teamSlug.value)
  const urlTeamId = teamRes.data.numericId
  if (urlTeamId === undefined) throw new Error('team_numeric_id_missing')
  if (schedule.value?.teamId !== urlTeamId) {
    // 他チームのシフト表の題名・操作導線を残さない。
    schedule.value = null
    throw Object.assign(new Error('schedule_team_mismatch'), { statusCode: 404 })
  }
}

async function loadSchedule(): Promise<void> {
  const res = await shiftApi.getShiftSchedule(scheduleId.value)
  schedule.value = (res as { data: ShiftScheduleResponse }).data
}

async function loadPositions(): Promise<void> {
  // BE の ShiftPositionController#listPositions は teamId (Long) が必須。
  // teamSlug はスラッグであり teamId ではないため、schedule.teamId（数値）を使う
  // （isSupporter の判定 [schedule.value!.teamId] と同じ既存パターンに倣う）。
  if (!schedule.value) return
  const res = await shiftApi.getPositions(String(schedule.value.teamId))
  positions.value = (res as { data: ShiftPositionResponse[] }).data
}

async function loadMembers(): Promise<void> {
  membersLoading.value = true
  membersError.value = undefined
  try {
    const res = await teamApi.getMembers(teamSlug.value, { size: 200 })
    // SUPPORTER は割当対象外（BE も SHIFT_049 で拒否する）ため、未割当メンバーに並べない。
    allMembers.value = res.data.filter((m) => m.roleName !== 'SUPPORTER').map((m) => ({
      userId: m.userId,
      displayName: m.displayName || String(m.userId),
      avatarUrl: m.avatarUrl,
    }))
  } catch (error) {
    membersError.value = error
    allMembers.value = []
  } finally {
    membersLoading.value = false
  }
}

/**
 * 割当 API の失敗を利用者に伝える。
 *
 * BE のエラーコード（例: SHIFT_017「シフト枠の必要人数を超過しています」）があれば
 * その文言を優先表示し、無ければシフト共通の汎用文言にフォールバックする
 * （金型: components/survey/SurveyCreateDialog.vue）。
 *
 * catch せず放置すると unhandledrejection になり、localAssignments が静かに
 * ロールバックされるだけで画面には何も出ない（errorReport には載るが UI 表示は無い）。
 */
function notifyAssignmentError(e: unknown, context: string): void {
  const apiError = e as { data?: { error?: { code?: string } } }
  if (apiError?.data?.error?.code) {
    handleApiError(e, context)
  } else {
    showError(t('shift.notification.errorUpdate'))
  }
}

/**
 * 枠への割当操作を、処理中の枠を押さえて実行する。
 * 対象の枠のどれかが処理中なら何もしない（false を返す）＝二度押しでも PATCH は 1 回。
 */
async function runExclusive(slotIds: number[], operation: () => Promise<void>): Promise<boolean> {
  if (slotIds.some((id) => busySlotIds.value.includes(id))) return false
  busySlotIds.value = [...busySlotIds.value, ...slotIds]
  try {
    await operation()
    return true
  } finally {
    busySlotIds.value = busySlotIds.value.filter((id) => !slotIds.includes(id))
  }
}

// D&D でドロップ
async function onDropUser(payload: {
  fromSlotId: number | null
  toSlotId: number
  userId: number
}): Promise<void> {
  if (!canAssign.value) return
  const { fromSlotId, toSlotId, userId } = payload
  const involved = fromSlotId !== null ? [fromSlotId, toSlotId] : [toSlotId]

  try {
    await runExclusive(involved, async () => {
      if (fromSlotId !== null) {
        await moveUser(fromSlotId, toSlotId, userId)
      } else {
        await addUser(toSlotId, userId)
      }
    })
  } catch (e) {
    notifyAssignmentError(e, 'shift-board:drop')
  }
}

// メンバー削除
async function onRemoveUser(payload: { slotId: number; userId: number }): Promise<void> {
  if (!canAssign.value) return
  try {
    await runExclusive([payload.slotId], () => removeUser(payload.slotId, payload.userId))
  } catch (e) {
    notifyAssignmentError(e, 'shift-board:remove')
  }
}

// 「+ 追加」から追加ダイアログを開く
function onOpenAddDialog(slotId: number): void {
  if (!canAssign.value) return
  addUserSlotId.value = slotId
  addDialogVisible.value = true
}

// ダイアログからメンバー追加
async function onAddUserFromDialog(userId: number): Promise<void> {
  const slotId = addUserSlotId.value
  if (!canAssign.value || slotId === null) return
  try {
    const done = await runExclusive([slotId], () => addUser(slotId, userId))
    if (done) addDialogVisible.value = false
  } catch (e) {
    // ダイアログは開いたまま。失敗後は useShiftBoard が枠を再取得するので、続けて選び直せる。
    notifyAssignmentError(e, 'shift-board:add')
  }
}

// 自動割当実行
async function onRunAutoAssign(
  strategy: AssignmentStrategyType,
  params: AssignmentParameters,
): Promise<void> {
  await runAutoAssign(strategy, params)
  autoAssignVisible.value = false
}

// 自動割当確定（目視確認後）
async function onConfirmAutoAssignWithNote(_note: string | undefined): Promise<void> {
  const run = pendingRun.value
  if (!run) return
  const assignmentIds = (run.assignments ?? []).map((a) => a.id)
  await confirmAutoAssign(run.id, assignmentIds, scheduleVersion.value)
  // スロット情報を再取得
  await loadSlots()
}

// 自動割当破棄
async function onRevokeAutoAssign(): Promise<void> {
  const run = pendingRun.value
  if (!run) return
  try {
    await revokeAutoAssign(run.id)
  } catch (e) {
    // 403・404・通信エラーを握りつぶさず、割当操作と同じ作法で利用者に通知する
    notifyAssignmentError(e, 'shift-board:revoke-auto-assign')
  }
}
</script>
