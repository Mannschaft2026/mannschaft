<script setup lang="ts">
/**
 * 変更依頼の操作ボタン（承認・却下・取り下げ）。表（広幅）とカード（狭幅）で共用する。
 * 出す・出さないの判定（canReview / canWithdraw）は呼び出し側。
 */
defineProps<{
  requestId: number
  /** 管理者: 他人の OPEN 依頼を承認・却下できるか */
  canReview: boolean
  /** 依頼者本人: 自分の OPEN 依頼を取り下げられるか */
  canWithdraw: boolean
  processing: boolean
  /** data-testid の接頭辞（表: change-request / カード: change-request-card） */
  testidPrefix: string
}>()

const emit = defineEmits<{
  approve: []
  reject: []
  withdraw: []
}>()
</script>

<template>
  <div class="flex flex-wrap gap-2">
    <template v-if="canReview">
      <Button
        :data-testid="`${testidPrefix}-approve-${requestId}`"
        :label="$t('shift.changeRequest.approve')"
        class="min-h-[44px] min-w-[44px]"
        severity="success"
        size="small"
        :disabled="processing"
        :loading="processing"
        @click="emit('approve')"
      />
      <Button
        :data-testid="`${testidPrefix}-reject-${requestId}`"
        :label="$t('shift.changeRequest.reject')"
        class="min-h-[44px] min-w-[44px]"
        severity="danger"
        size="small"
        :disabled="processing"
        @click="emit('reject')"
      />
    </template>
    <Button
      v-if="canWithdraw"
      :data-testid="`${testidPrefix}-withdraw-${requestId}`"
      :label="$t('shift.changeRequest.withdraw')"
      class="min-h-[44px] min-w-[44px]"
      severity="secondary"
      size="small"
      outlined
      :disabled="processing"
      :loading="processing"
      @click="emit('withdraw')"
    />
  </div>
</template>
