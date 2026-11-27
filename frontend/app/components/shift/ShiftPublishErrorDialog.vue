<script setup lang="ts">
import { groupMissingByUser, type ShiftPublishErrorInfo } from '~/utils/shiftPublishError'

/**
 * シフト公開が拒否されたときの案内ダイアログ（CMP-261115-1821）。
 *
 * - 時給未設定（400）: 対象者と勤務日を一覧表示し、時給設定画面への導線を出す。
 *   導線は時給を登録できる権限（ADMIN または MANAGE_SHIFTS 付き DEPUTY_ADMIN）を持つ人にだけ出す。
 * - 競合（409）: 内容が変わったので読み込み直してから再操作するよう案内する。
 */
const props = defineProps<{
  info: ShiftPublishErrorInfo | null
  /** 時給設定画面の URL とメンバー名簿・権限の取得に使うチーム slug。 */
  teamSlug: string | null
}>()
const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()
const { handleApiError } = useErrorHandler()
const { getAllMembers } = useTeamApi()

const visible = computed({
  get: () => props.info !== null,
  set: (v: boolean) => {
    if (!v) emit('close')
  },
})

const names = ref<Record<number, string>>({})
const slugRef = computed(() => props.teamSlug ?? '')
// 導線は時給の登録に必要な権限（ADMIN または MANAGE_SHIFTS 付き DEPUTY_ADMIN）を持つ人にだけ出す（CMP-261125-2114）。
const { canManageShift: canOpenSettings, loadPermissions } = useShiftPermission(slugRef, { autoLoad: false })

const groups = computed(() =>
  props.info?.kind === 'hourlyRateMissing' ? groupMissingByUser(props.info.missing) : [],
)

function nameOf(userId: number): string {
  return names.value[userId] ?? t('shift.publishError.hourlyRateMissing.unknownUser', { id: userId })
}

watch(
  () => [props.info, props.teamSlug] as const,
  async ([info, slug]) => {
    if (info?.kind !== 'hourlyRateMissing' || !slug) return
    try {
      const res = await getAllMembers(slug)
      names.value = Object.fromEntries(res.data.map(m => [m.userId, m.displayName]))
    } catch (error) {
      // 名簿が取れなくても対象者は ID で表示できる。失敗は利用者へ通知する。
      handleApiError(error)
    }
    const result = await loadPermissions()
    // 権限を取得できないときは導線を出さない（失敗は利用者へ通知する）。
    if (!result.ok) handleApiError(result.error)
  },
  { immediate: true },
)
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="
      info?.kind === 'conflict'
        ? t('shift.publishError.conflict.title')
        : t('shift.publishError.hourlyRateMissing.title')
    "
    modal
    class="w-full max-w-lg"
    data-testid="shift-publish-error-dialog"
  >
    <template v-if="info?.kind === 'conflict'">
      <p class="text-sm" data-testid="shift-publish-error-conflict">
        {{ t('shift.publishError.conflict.description') }}
      </p>
    </template>
    <template v-else-if="info?.kind === 'hourlyRateMissing'">
      <p class="mb-3 text-sm">{{ t('shift.publishError.hourlyRateMissing.description') }}</p>
      <ul class="flex flex-col gap-2" data-testid="shift-publish-error-missing-list">
        <li v-for="g in groups" :key="g.userId" class="text-sm">
          <span class="font-semibold">{{ nameOf(g.userId) }}</span>
          <span class="text-surface-500">: {{ g.dates.join(', ') }}</span>
        </li>
      </ul>
    </template>
    <template #footer>
      <NuxtLink
        v-if="info?.kind === 'hourlyRateMissing' && canOpenSettings && teamSlug"
        :to="`/teams/${teamSlug}/settings/hourly-rate`"
        class="mr-auto text-sm text-primary underline"
        data-testid="shift-publish-error-settings-link"
      >
        {{ t('shift.publishError.hourlyRateMissing.goSettings') }}
      </NuxtLink>
      <Button :label="t('shift.publishError.close')" @click="emit('close')" />
    </template>
  </Dialog>
</template>
