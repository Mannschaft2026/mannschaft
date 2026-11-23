<script setup lang="ts">
// CMP-261107-1833 PR2（AC13）: ログイン後の参加待ち招待ダイアログ（表示のみ。状態と操作は usePendingInvite）。
const { t } = useI18n()
const {
  status,
  visible,
  header,
  roleLabel,
  scopeName,
  invite,
  busy,
  isExpiredView,
  retryEnabled,
  accept,
  decline,
  later,
  reissue,
  dismiss,
  closeUnavailable,
  leaveForJoinRequest,
} = usePendingInvite()
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :closable="false"
    :close-on-escape="false"
    :header="header"
    class="w-full max-w-md mx-4"
  >
    <div v-if="status === 'PENDING'" class="space-y-4" data-testid="pending-invite-pending">
      <p>{{ t('auth.pending_invite.pending_desc', { name: scopeName, role: roleLabel }) }}</p>
      <div class="flex flex-wrap justify-end gap-2">
        <Button
          :label="t('auth.pending_invite.later')"
          severity="secondary"
          text
          :disabled="busy"
          data-testid="pending-invite-later"
          @click="later"
        />
        <Button
          :label="t('auth.pending_invite.decline')"
          severity="secondary"
          :disabled="busy"
          data-testid="pending-invite-decline"
          @click="decline"
        />
        <Button
          :label="t('auth.pending_invite.accept')"
          icon="pi pi-check"
          :loading="busy"
          data-testid="pending-invite-accept"
          @click="accept"
        />
      </div>
    </div>

    <div v-else-if="isExpiredView" class="space-y-4" data-testid="pending-invite-expired">
      <p>{{ t('auth.pending_invite.expired_desc', { name: scopeName }) }}</p>
      <p v-if="status === 'REISSUE_REQUESTED'" class="text-sm" data-testid="pending-invite-reissue-requested">
        {{ t('auth.pending_invite.reissue_requested') }}
      </p>
      <NuxtLink
        v-else-if="invite?.joinable && invite.publicPath"
        :to="invite.publicPath"
        class="underline"
        data-testid="pending-invite-join-request-link"
        @click="leaveForJoinRequest"
      >
        {{ t('auth.pending_invite.join_request_link') }}
      </NuxtLink>
      <p v-else class="text-sm">{{ t('auth.pending_invite.reissue_hint') }}</p>
      <div class="flex flex-wrap justify-end gap-2">
        <Button
          v-if="status === 'EXPIRED' && !(invite?.joinable && invite.publicPath)"
          :label="t('auth.pending_invite.reissue')"
          :loading="busy"
          data-testid="pending-invite-reissue"
          @click="reissue"
        />
        <Button
          :label="t('auth.pending_invite.dismiss')"
          severity="secondary"
          :disabled="busy"
          data-testid="pending-invite-dismiss"
          @click="dismiss"
        />
      </div>
    </div>

    <div v-else-if="status === 'ACCEPTING'" class="space-y-4" data-testid="pending-invite-accepting">
      <p>{{ t('auth.pending_invite.accepting_desc', { name: scopeName }) }}</p>
      <p v-if="!retryEnabled" class="text-sm">{{ t('auth.pending_invite.retry_wait') }}</p>
      <div class="flex justify-end">
        <Button
          :label="t('auth.pending_invite.retry')"
          :disabled="!retryEnabled || busy"
          :loading="busy"
          data-testid="pending-invite-retry"
          @click="accept"
        />
      </div>
    </div>

    <div v-else-if="status === 'UNAVAILABLE'" class="space-y-4" data-testid="pending-invite-unavailable">
      <p>{{ t('auth.pending_invite.unavailable_desc', { name: scopeName }) }}</p>
      <div class="flex justify-end">
        <Button
          :label="t('auth.pending_invite.close')"
          severity="secondary"
          data-testid="pending-invite-dismiss"
          @click="closeUnavailable"
        />
      </div>
    </div>
  </Dialog>
</template>
