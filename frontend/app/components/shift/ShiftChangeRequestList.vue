<script setup lang="ts">
import type { ChangeRequest, ReviewOutcome } from '~/types/shift'

const props = defineProps<{
  /**
   * 審査を実行する（通知は呼び出し側で済み）。
   * conflict の latest は 409 後に再取得した最新の依頼（消えていれば null）。
   */
  submitReview: (
    id: number,
    decision: 'ACCEPTED' | 'REJECTED',
    comment: string | undefined,
    version: number,
  ) => Promise<ReviewOutcome>
  requests: ChangeRequest[]
  /** ログイン中の利用者 ID。未解決（null）の間は審査・取り下げのボタンを出さない。 */
  currentUserId: number | null
  /** 審査してよいか（チーム ADMIN / DEPUTY_ADMIN）。判定は呼び出し側の useRoleAccess。 */
  canReview?: boolean
  isLoading?: boolean
  /** 審査・取り下げの処理中の依頼 ID（二重送信防止のため disabled / loading にする） */
  processingIds?: number[]
  /** userId → 表示名。requesterName が無い依頼者の補完に使う（引けなければ「不明なユーザー」） */
  memberNames?: Map<number, string>
}>()

const emit = defineEmits<{
  withdraw: [id: number]
}>()

const { t } = useI18n()

const reviewDialogVisible = ref(false)
const reviewTarget = ref<{ id: number; decision: 'ACCEPTED' | 'REJECTED'; version: number } | null>(null)
const reviewComment = ref('')
const isReviewing = ref(false)

function typeLabel(type: string): string {
  return t(`shift.changeRequest.type.${type}`)
}

/** 依頼者の表示名。userId は表示しない（解決できなければ「不明なユーザー」）。 */
function requesterLabel(row: ChangeRequest): string {
  return row.requesterName ?? props.memberNames?.get(row.requestInfo.requestedBy) ?? t('shift.changeRequest.unknownRequester')
}

/** 表（DataTable rows=10）と同じ件数でカードもページングする */
const PAGE_SIZE = 10
const cardFirst = ref(0)
const pagedRequests = computed(() => {
  // 件数が減って現在ページが範囲外になったら最終ページに寄せる
  const maxFirst = Math.max(0, Math.ceil(props.requests.length / PAGE_SIZE) - 1) * PAGE_SIZE
  const first = Math.min(cardFirst.value, maxFirst)
  return props.requests.slice(first, first + PAGE_SIZE)
})

function isOwn(row: ChangeRequest): boolean {
  return props.currentUserId !== null && row.requestInfo.requestedBy === props.currentUserId
}

function isProcessing(id: number): boolean {
  return props.processingIds?.includes(id) ?? false
}

/** 自分の依頼は審査できない（BE も 403 SHIFT_045）。本人の確定は取り下げで代替する。 */
function canReviewRow(row: ChangeRequest): boolean {
  return !!props.canReview && props.currentUserId !== null && !isOwn(row) && row.reviewInfo.status === 'OPEN'
}

/** 本人の OPEN 依頼だけ取り下げられる */
function canWithdrawRow(row: ChangeRequest): boolean {
  return isOwn(row) && row.reviewInfo.status === 'OPEN'
}

function openReviewDialog(row: ChangeRequest, decision: 'ACCEPTED' | 'REJECTED'): void {
  reviewTarget.value = { id: row.id, decision, version: row.version }
  reviewComment.value = ''
  reviewDialogVisible.value = true
}

async function confirmReview(): Promise<void> {
  // 確定ボタンの連打で同じ審査を重ねて送らない
  const target = reviewTarget.value
  if (!target || isReviewing.value) return
  isReviewing.value = true
  try {
    const outcome = await props.submitReview(
      target.id,
      target.decision,
      reviewComment.value.trim() || undefined,
      target.version,
    )
    if (outcome.status === 'ok') {
      reviewDialogVisible.value = false
      reviewComment.value = ''
    } else if (outcome.status === 'conflict') {
      if (outcome.latest && outcome.latest.reviewInfo.status === 'OPEN') {
        // まだ OPEN: コメントは残し、最新の version に差し替えて再送できるようにする
        reviewTarget.value = { ...target, version: outcome.latest.version }
      } else {
        // 審査済み・消滅: もう審査できないので閉じる
        reviewDialogVisible.value = false
        reviewComment.value = ''
      }
    }
    // error: ダイアログとコメントをそのまま残す
  } finally {
    isReviewing.value = false
  }
}
</script>

<template>
  <div data-testid="change-request-list">
    <PageLoading v-if="isLoading" size="32px" />

    <div v-else-if="requests.length === 0" class="py-8 text-center text-surface-400">
      <i class="pi pi-inbox mb-2 block text-3xl" />
      <p class="text-sm" data-testid="change-request-empty">{{ $t('shift.changeRequest.empty') }}</p>
    </div>

    <template v-else>
      <!-- md 未満: カード縦積み（表だと横スクロールで操作列が他の列を覆うため）。
           表と同じ情報・同じ操作を出す。前例: EmergencyClosureHistory -->
      <ul class="space-y-3 md:hidden" data-testid="change-request-cards">
        <li
          v-for="row in pagedRequests"
          :key="row.id"
          :data-testid="`change-request-card-${row.id}`"
          class="rounded-md border border-surface-200 p-3 text-sm dark:border-surface-700"
        >
          <dl class="min-w-0 space-y-2">
            <div>
              <dt class="text-xs font-medium text-surface-500">{{ $t('shift.changeRequest.requester') }}</dt>
              <dd class="break-words font-medium [overflow-wrap:anywhere]">{{ requesterLabel(row) }}</dd>
            </div>
            <div>
              <dt class="text-xs font-medium text-surface-500">{{ $t('shift.changeRequest.typeColumn') }}</dt>
              <dd class="break-words [overflow-wrap:anywhere]">{{ typeLabel(row.requestInfo.requestType) }}</dd>
            </div>
            <div>
              <dt class="text-xs font-medium text-surface-500">{{ $t('shift.changeRequest.reason') }}</dt>
              <dd class="break-words text-surface-600 [overflow-wrap:anywhere] dark:text-surface-300">{{ row.requestInfo.reason ?? '—' }}</dd>
            </div>
            <div>
              <dt class="text-xs font-medium text-surface-500">{{ $t('label.status') }}</dt>
              <dd>
                <ShiftChangeRequestStatus :request="row" testid-prefix="change-request-card-review" />
              </dd>
            </div>
          </dl>
          <ShiftChangeRequestActions
            v-if="canReviewRow(row) || canWithdrawRow(row)"
            class="mt-3"
            :request-id="row.id"
            :can-review="canReviewRow(row)"
            :can-withdraw="canWithdrawRow(row)"
            :processing="isProcessing(row.id)"
            testid-prefix="change-request-card"
            @approve="openReviewDialog(row, 'ACCEPTED')"
            @reject="openReviewDialog(row, 'REJECTED')"
            @withdraw="emit('withdraw', row.id)"
          />
        </li>
      </ul>
      <Paginator
        v-if="requests.length > PAGE_SIZE"
        v-model:first="cardFirst"
        class="md:hidden"
        data-testid="change-request-cards-paginator"
        :rows="PAGE_SIZE"
        :total-records="requests.length"
      />

      <!-- md 以上: 表 -->
      <DataTable
        :value="requests"
        :rows="10"
        paginator
        scrollable
        striped-rows
        class="text-sm max-md:hidden"
        data-testid="change-request-table"
      >
        <Column :header="$t('shift.changeRequest.requester')" style="min-width: 100px">
          <template #body="{ data }">
            <span class="font-medium">{{ requesterLabel(data) }}</span>
          </template>
        </Column>

        <Column :header="$t('shift.changeRequest.typeColumn')" field="requestInfo.requestType" style="min-width: 120px">
          <template #body="{ data }">
            <span class="text-xs">{{ typeLabel(data.requestInfo.requestType) }}</span>
          </template>
        </Column>

        <Column :header="$t('label.status')" field="reviewInfo.status" style="min-width: 100px">
          <template #body="{ data }">
            <ShiftChangeRequestStatus :request="data" testid-prefix="change-request-review" />
          </template>
        </Column>

        <Column :header="$t('shift.changeRequest.reason')" field="requestInfo.reason" style="min-width: 120px">
          <template #body="{ data }">
            <span
              :data-testid="`change-request-reason-${data.id}`"
              class="line-clamp-2 max-w-[12rem] break-words text-xs text-surface-500 [overflow-wrap:anywhere]"
            >{{ data.requestInfo.reason ?? '—' }}</span>
          </template>
        </Column>

        <!-- 列順: 依頼者・種別・ステータス・理由・操作。主要情報を枠内に収め、理由は幅を制限して折り返す。操作列は右端に固定 -->
        <Column :header="$t('label.actions')" frozen align-frozen="right" style="min-width: 140px">
          <template #body="{ data }">
            <ShiftChangeRequestActions
              :request-id="data.id"
              :can-review="canReviewRow(data)"
              :can-withdraw="canWithdrawRow(data)"
              :processing="isProcessing(data.id)"
              testid-prefix="change-request"
              @approve="openReviewDialog(data, 'ACCEPTED')"
              @reject="openReviewDialog(data, 'REJECTED')"
              @withdraw="emit('withdraw', data.id)"
            />
          </template>
        </Column>
      </DataTable>
    </template>

    <!-- 審査コメントダイアログ -->
    <Dialog
      v-model:visible="reviewDialogVisible"
      :header="$t('shift.changeRequest.review')"
      modal
      :style="{ width: '400px', maxWidth: '95vw' }"
    >
      <div class="space-y-3">
        <label class="block text-sm text-surface-600">
          {{ $t('shift.changeRequest.reviewComment') }}
        </label>
        <Textarea
          v-model="reviewComment"
          data-testid="change-request-review-comment"
          :placeholder="$t('shift.changeRequest.reviewComment')"
          rows="3"
          class="w-full"
        />
      </div>
      <template #footer>
        <Button
          :label="$t('common.cancel')"
          severity="secondary"
          outlined
          @click="reviewDialogVisible = false"
        />
        <Button
          data-testid="change-request-review-confirm"
          :label="reviewTarget?.decision === 'ACCEPTED' ? $t('shift.changeRequest.approve') : $t('shift.changeRequest.reject')"
          :severity="reviewTarget?.decision === 'ACCEPTED' ? 'success' : 'danger'"
          :loading="isReviewing"
          @click="confirmReview"
        />
      </template>
    </Dialog>
  </div>
</template>
