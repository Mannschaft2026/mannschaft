<script setup lang="ts">
import type { ChangeRequest, ChangeRequestStatus } from '~/types/shift'

const props = defineProps<{
  /** 審査を実行する。成功なら true、失敗（通知は呼び出し側で済み）なら false */
  submitReview: (
    id: number,
    decision: 'ACCEPTED' | 'REJECTED',
    comment: string | undefined,
    version: number,
  ) => Promise<boolean>
  requests: ChangeRequest[]
  currentUserId: number
  isAdmin?: boolean
  isLoading?: boolean
  /** userId → 表示名。引けない依頼者は「不明なメンバー」を出す */
  memberNames?: Map<number, string>
}>()

const emit = defineEmits<{
  withdraw: [id: number]
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()

const reviewDialogVisible = ref(false)
const reviewTarget = ref<{ id: number; decision: 'ACCEPTED' | 'REJECTED'; version: number } | null>(null)
const reviewComment = ref('')
const isReviewing = ref(false)

function memberName(userId: number): string {
  return props.memberNames?.get(userId) ?? t('shift.requests.unknownMember')
}

function statusSeverity(status: ChangeRequestStatus): string {
  switch (status) {
    case 'OPEN':
      return 'info'
    case 'ACCEPTED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'WITHDRAWN':
    case 'EXPIRED':
      return 'secondary'
    default:
      return 'secondary'
  }
}

function statusLabel(status: ChangeRequestStatus): string {
  return t(`shift.changeRequest.status.${status}`)
}

function typeLabel(type: string): string {
  return t(`shift.changeRequest.type.${type}`)
}

function openReviewDialog(data: ChangeRequest, decision: 'ACCEPTED' | 'REJECTED'): void {
  reviewTarget.value = { id: data.id, decision, version: data.version }
  reviewComment.value = ''
  reviewDialogVisible.value = true
}

async function confirmReview(): Promise<void> {
  const target = reviewTarget.value
  if (!target || isReviewing.value) return
  isReviewing.value = true
  try {
    const ok = await props.submitReview(
      target.id,
      target.decision,
      reviewComment.value.trim() || undefined,
      target.version,
    )
    if (ok) {
      reviewDialogVisible.value = false
      reviewComment.value = ''
      return
    }
    // 失敗: コメントを残してダイアログも開いたままにする。
    // ただし 409 の再取得で対象がすでに審査済み・消滅していれば、審査できないので閉じる。
    const latest = props.requests.find((r) => r.id === target.id)
    if (!latest || latest.reviewInfo.status !== 'OPEN') {
      reviewDialogVisible.value = false
      reviewComment.value = ''
    }
  } finally {
    isReviewing.value = false
  }
}

/** 審査結果（コメント・日時）を申請者にも見せるか */
function hasReviewResult(data: ChangeRequest): boolean {
  return !!(data.reviewInfo.reviewComment || data.reviewInfo.reviewedAt)
}
</script>

<template>
  <div>
    <PageLoading v-if="isLoading" size="32px" />

    <div v-else-if="requests.length === 0" class="py-8 text-center text-surface-400">
      <i class="pi pi-inbox mb-2 block text-3xl" />
      <p class="text-sm">{{ $t('shift.changeRequest.title') }}</p>
    </div>

    <DataTable
      v-else
      :value="requests"
      :rows="10"
      paginator
      scrollable
      striped-rows
      class="text-sm"
    >
      <!-- 依頼者 -->
      <Column :header="$t('shift.hourlyRate.column.member')" field="requestInfo.requestedBy" style="min-width: 100px">
        <template #body="{ data }">
          <span class="font-medium">{{ memberName(data.requestInfo.requestedBy) }}</span>
        </template>
      </Column>

      <!-- 種別 -->
      <Column :header="$t('shift.changeRequest.title')" field="requestInfo.requestType" style="min-width: 120px">
        <template #body="{ data }">
          <span class="text-xs">{{ typeLabel(data.requestInfo.requestType) }}</span>
        </template>
      </Column>

      <!-- 理由 -->
      <Column :header="$t('shift.changeRequest.reason')" field="requestInfo.reason" style="min-width: 140px">
        <template #body="{ data }">
          <span class="line-clamp-1 text-xs text-surface-500">{{ data.requestInfo.reason ?? '—' }}</span>
        </template>
      </Column>

      <!-- ステータス -->
      <Column :header="$t('label.status')" field="reviewInfo.status" style="min-width: 100px">
        <template #body="{ data }">
          <Tag :value="statusLabel(data.reviewInfo.status)" :severity="statusSeverity(data.reviewInfo.status)" />
          <!-- 審査結果（申請者が却下理由などを確認できる）。狭い画面でも折り返す -->
          <div
            v-if="hasReviewResult(data)"
            class="mt-1 max-w-[16rem] whitespace-normal break-words text-xs text-surface-500"
            :data-testid="`change-request-review-${data.id}`"
          >
            <p v-if="data.reviewInfo.reviewedAt">
              {{ $t('shift.changeRequest.reviewedAt') }}: {{ formatDateTime(data.reviewInfo.reviewedAt) }}
            </p>
            <p v-if="data.reviewInfo.reviewComment">
              {{ $t('shift.changeRequest.reviewComment') }}: {{ data.reviewInfo.reviewComment }}
            </p>
          </div>
        </template>
      </Column>

      <!-- 操作 -->
      <Column :header="$t('label.actions')" style="min-width: 180px">
        <template #body="{ data }">
          <div class="flex gap-2">
            <!-- ADMIN: 承認・却下 -->
            <template v-if="isAdmin && data.reviewInfo.status === 'OPEN'">
              <Button
                :label="$t('shift.changeRequest.approve')"
                severity="success"
                size="small"
                @click="openReviewDialog(data, 'ACCEPTED')"
              />
              <Button
                :label="$t('shift.changeRequest.reject')"
                severity="danger"
                size="small"
                @click="openReviewDialog(data, 'REJECTED')"
              />
            </template>

            <!-- 依頼者本人: 取下 -->
            <Button
              v-if="data.requestInfo.requestedBy === currentUserId && data.reviewInfo.status === 'OPEN'"
              :label="$t('shift.changeRequest.withdraw')"
              severity="secondary"
              size="small"
              outlined
              @click="emit('withdraw', data.id)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- 審査コメントダイアログ -->
    <Dialog
      v-model:visible="reviewDialogVisible"
      :header="$t('shift.changeRequest.review')"
      modal
      :style="{ width: '400px' }"
    >
      <div class="space-y-3">
        <label class="block text-sm text-surface-600">
          {{ $t('shift.changeRequest.reviewComment') }}
        </label>
        <Textarea
          v-model="reviewComment"
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
          :label="reviewTarget?.decision === 'ACCEPTED' ? $t('shift.changeRequest.approve') : $t('shift.changeRequest.reject')"
          :severity="reviewTarget?.decision === 'ACCEPTED' ? 'success' : 'danger'"
          :loading="isReviewing"
          @click="confirmReview"
        />
      </template>
    </Dialog>
  </div>
</template>
