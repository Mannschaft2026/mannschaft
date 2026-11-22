<script setup lang="ts">
import { useTeamMembers } from '~/composables/team/useTeamMembers'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const scheduleId = computed(() => Number(route.params.scheduleId))

const authStore = useAuthStore()
const { roleName, loadPermissions } = useRoleAccess('team', teamSlug)
// 運営専用のSYSTEM_ADMINは業務導線に含めない（docs/security/03_role_authority_model.md §3.5）。
const isAdmin = computed(() => roleName.value === 'ADMIN' || roleName.value === 'DEPUTY_ADMIN')

const { requests, isLoading, fetchRequests, review, withdraw } =
  useChangeRequest(scheduleId)

const { handleApiError } = useErrorHandler()
const { getAllMembers } = useTeamMembers()
/** userId → 表示名（GET /teams/{slug}/members/all）。取得失敗は通知し、一覧は「不明なメンバー」で代替する */
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

onMounted(() => Promise.all([fetchRequests(), loadPermissions(), loadMemberNames()]))

async function onSubmitted(): Promise<void> {
  await fetchRequests()
}

async function onReview(
  id: number,
  decision: 'ACCEPTED' | 'REJECTED',
  comment: string | undefined,
  version: number,
): Promise<void> {
  await review(id, decision, comment, version)
}

async function onWithdraw(id: number): Promise<void> {
  await withdraw(id)
}
</script>

<template>
  <div class="mx-auto max-w-4xl space-y-6 px-4 py-6">
    <!-- ページヘッダー（戻る導線は PageHeader の back-to に一本化） -->
    <PageHeader :title="$t('shift.changeRequest.title')" :back-to="`/teams/${teamSlug}/shifts`" />

    <!-- 変更依頼作成フォーム -->
    <ShiftChangeRequestForm
      :schedule-id="scheduleId"
      @submitted="onSubmitted"
    />

    <!-- 変更依頼一覧 -->
    <SectionCard>
      <ShiftChangeRequestList
        :requests="requests"
        :current-user-id="authStore.currentUser?.id ?? 0"
        :is-admin="isAdmin"
        :is-loading="isLoading"
        :member-names="memberNames"
        @review="onReview"
        @withdraw="onWithdraw"
      />
    </SectionCard>
  </div>
</template>
