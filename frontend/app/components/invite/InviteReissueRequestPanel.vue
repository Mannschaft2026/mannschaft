<script setup lang="ts">
// CMP-261107-1833 PR3: 招待管理画面の「再招待の依頼」欄（招待が失効した人が出した依頼を、招待権限者が処理する）。
// 通知・承認待ちの集約の遷移先 …/invites#reissue-requests（id=reissue-requests）。表示名は補間のみ（v-html を使わない）。
import type { InviteReissueScopeType } from '~/composables/useInviteReissueRequests'

const props = defineProps<{
  scopeType: InviteReissueScopeType
  slug: string
}>()

const { t, te } = useI18n()
const { formatDateTime } = useDatetime()
const confirm = useConfirm()

const {
  requests,
  loading,
  error,
  forbidden,
  hasMore,
  processingIds,
  init,
  loadMore,
  retry,
  reissue,
  decline,
} = useInviteReissueRequests(toRef(props, 'scopeType'), toRef(props, 'slug'))

const route = useRoute()

// 通知・承認待ちの内訳からの遷移先 …/invites#reissue-requests。欄は権限確定後に現れるため、アンカーのスクロールは欄の側で行う
onMounted(async () => {
  await init()
  if (route.hash === '#reissue-requests') {
    await nextTick()
    document.getElementById('reissue-requests')?.scrollIntoView({ block: 'start' })
  }
})

function roleLabel(role: string): string {
  return te(`role.${role}`) ? t(`role.${role}`) : role
}

function isProcessing(id: string): boolean {
  return processingIds.value.includes(id)
}

function confirmDecline(id: string, name: string) {
  confirm.require({
    header: t('inviteReissue.decline_confirm_title'),
    message: t('inviteReissue.decline_confirm_message', { name }),
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: t('inviteReissue.decline'),
    rejectLabel: t('button.cancel'),
    accept: () => decline(id),
  })
}
</script>

<template>
  <SectionCard v-if="!forbidden" id="reissue-requests" :title="t('inviteReissue.title')" class="mb-4 scroll-mt-4">
    <p class="mb-3 text-sm text-surface-600 dark:text-surface-400">{{ t('inviteReissue.description') }}</p>

    <div v-if="loading && requests.length === 0" class="py-4 text-center" data-testid="invite-reissue-loading">
      <LoadingBounce />
    </div>

    <div v-else-if="error && requests.length === 0" class="space-y-2" data-testid="invite-reissue-error">
      <p class="text-sm text-red-600 dark:text-red-400">{{ t('inviteReissue.load_error') }}</p>
      <Button
        :label="t('inviteReissue.retry')"
        severity="secondary"
        size="small"
        class="w-full sm:w-auto min-h-11"
        data-testid="invite-reissue-retry"
        @click="retry"
      />
    </div>

    <p v-else-if="requests.length === 0" class="text-sm text-surface-500" data-testid="invite-reissue-empty">
      {{ t('inviteReissue.empty') }}
    </p>

    <ul v-else class="space-y-3">
      <li
        v-for="r in requests"
        :key="r.id"
        class="flex flex-col gap-3 rounded-lg border border-surface-200 p-3 sm:flex-row sm:items-center sm:justify-between dark:border-surface-700"
        data-testid="invite-reissue-row"
      >
        <div class="min-w-0">
          <p class="truncate font-medium">{{ r.requesterDisplayName }}</p>
          <p class="text-xs text-surface-500">
            {{ t('inviteReissue.requested_at') }}: {{ formatDateTime(r.requestedAt) }}
            <span class="mx-1" aria-hidden="true">/</span>
            {{ t('inviteReissue.role') }}: {{ roleLabel(r.roleName) }}
          </p>
        </div>
        <div class="flex flex-col gap-2 sm:flex-row sm:shrink-0">
          <Button
            :label="t('inviteReissue.reissue')"
            :loading="isProcessing(r.id)"
            :disabled="isProcessing(r.id)"
            class="w-full sm:w-auto min-h-11"
            data-testid="invite-reissue-reissue"
            @click="reissue(r.id)"
          />
          <Button
            :label="t('inviteReissue.decline')"
            severity="secondary"
            :disabled="isProcessing(r.id)"
            class="w-full sm:w-auto min-h-11"
            data-testid="invite-reissue-decline"
            @click="confirmDecline(r.id, r.requesterDisplayName)"
          />
        </div>
      </li>
    </ul>

    <div v-if="hasMore && requests.length > 0" class="mt-3 text-center">
      <Button
        :label="t('inviteReissue.load_more')"
        severity="secondary"
        text
        :loading="loading"
        class="w-full sm:w-auto min-h-11"
        data-testid="invite-reissue-load-more"
        @click="loadMore"
      />
    </div>
  </SectionCard>
</template>
