<script setup lang="ts">
/**
 * 組織詳細「招待」タブ（永続シェル配下の子ルート・管理者/副管理者のみ）。
 * ヘッダ・タブは親 `pages/organizations/[slug].vue`（ScopePageShell）が常駐描画する。
 * 直リンク防御として、権限確定後に非管理者はダッシュボードへ戻す（二重ガード）。
 */
import { useOrgShellContext } from '~/composables/useOrgShellContext'

definePageMeta({
  middleware: ['auth', 'admin-console'],
  layout: 'default',
})

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))

// 「再招待の依頼」欄は ADMIN、または INVITE_MEMBERS を持つ DEPUTY_ADMIN だけに出す（BE の InviteIssuerPolicy#canIssue に合わせる）。
// canManageInviteReissue の判定は useInviteReissueAccess が持つ。権限の取得失敗は欄の位置にエラーと再試行を出す。
const { canManage: canManageReissue, loadFailed: reissueAccessFailed, load: loadReissueAccess } = useInviteReissueAccess('organization', orgSlug)
onMounted(() => {
  void loadReissueAccess()
})

const { isAdminOrDeputy, roleName } = useOrgShellContext()

watch(
  [roleName, isAdminOrDeputy],
  () => {
    if (roleName.value && !isAdminOrDeputy.value) {
      navigateTo(`/organizations/${orgSlug.value}`)
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="mt-4">
    <div v-if="reissueAccessFailed" class="mb-4 space-y-2" data-testid="invite-reissue-access-error">
      <p class="text-sm text-red-600 dark:text-red-400">{{ $t('inviteReissue.load_error') }}</p>
      <Button
        :label="$t('inviteReissue.retry')"
        severity="secondary"
        size="small"
        class="min-h-11"
        data-testid="invite-reissue-access-retry"
        @click="loadReissueAccess"
      />
    </div>
    <InviteReissueRequestPanel
      v-if="canManageReissue"
      scope-type="organization"
      :slug="orgSlug"
    />
    <InviteTokenList
      v-if="isAdminOrDeputy"
      scope-type="organization"
      :scope-id="orgSlug"
    />
  </div>
</template>
