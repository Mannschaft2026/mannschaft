<script setup lang="ts">
import type { ChangeRequest, ChangeRequestStatus } from '~/types/shift'

/**
 * 変更依頼のステータス表示（タグ + 審査済みなら審査日時とコメント）。
 * 表（広幅）とカード（狭幅）で共用する。審査結果は申請者が却下理由を確認できるよう折り返して出す。
 */
const props = defineProps<{
  request: ChangeRequest
  /** 審査結果ブロックの data-testid 接頭辞（表とカードで重複させない） */
  testidPrefix: string
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()

const severity = computed((): string => {
  switch (props.request.reviewInfo.status as ChangeRequestStatus) {
    case 'OPEN':
      return 'info'
    case 'ACCEPTED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    default:
      return 'secondary'
  }
})

const label = computed(() => t(`shift.changeRequest.status.${props.request.reviewInfo.status}`))
const hasReviewResult = computed(() => !!(props.request.reviewInfo.reviewComment || props.request.reviewInfo.reviewedAt))
</script>

<template>
  <div>
    <Tag :value="label" :severity="severity" />
    <div
      v-if="hasReviewResult"
      class="mt-1 max-w-[16rem] whitespace-normal break-words text-xs text-surface-500 max-md:max-w-none"
      :data-testid="`${testidPrefix}-${request.id}`"
    >
      <p v-if="request.reviewInfo.reviewedAt">
        {{ $t('shift.changeRequest.reviewedAt') }}: {{ formatDateTime(request.reviewInfo.reviewedAt) }}
      </p>
      <p v-if="request.reviewInfo.reviewComment">
        {{ $t('shift.changeRequest.reviewComment') }}: {{ request.reviewInfo.reviewComment }}
      </p>
    </div>
  </div>
</template>
