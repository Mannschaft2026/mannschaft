// CMP-261107-1833 PR3: 招待管理画面で「再招待の依頼」欄を出してよいかの解決（ADMIN、または INVITE_MEMBERS を持つ DEPUTY）。
// 権限の取得失敗（通信障害など）を「権限なし」に倒さず、エラーを表示して再試行できるようにする。
import { computed, ref } from 'vue'
import type { Ref } from 'vue'
import { canManageInviteReissue } from '~/composables/useInviteReissueRequests'
import type { InviteReissueScopeType } from '~/composables/useInviteReissueRequests'

export function useInviteReissueAccess(scopeType: InviteReissueScopeType, slug: Ref<string>) {
  const { roleName, permissions, loadPermissions } = useRoleAccess(scopeType, slug)
  const { handleApiError } = useErrorHandler()
  const t = (key: string) => useNuxtApp().$i18n.t(key) as string

  const loadFailed = ref(false)
  const canManage = computed(() => canManageInviteReissue(roleName.value, permissions.value))

  async function load() {
    const result = await loadPermissions()
    loadFailed.value = !result.ok
    if (!result.ok) handleApiError(result.error, t('inviteReissue.load_error'))
  }

  return { canManage, loadFailed, load }
}
