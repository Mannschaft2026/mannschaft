<script setup lang="ts">
/**
 * シフト管理の権限に関する案内カード（CMP-261125-2114）。PageHeader の下に置く。
 *
 * - variant="admin": チーム ADMIN 向け告知。副管理者は MANAGE_SHIFTS が無いとシフトを管理できなくなったことを伝え、
 *   権限グループ設定画面へのリンクを付ける。
 * - variant="deputy": MANAGE_SHIFTS を持たない副管理者向け。操作できない理由を伝える。
 */
defineProps<{
  variant: 'admin' | 'deputy'
  /** 権限グループ画面へのリンク用（admin のときだけ使う）。 */
  teamSlug?: string | null
}>()

const { t } = useI18n()
</script>

<template>
  <Message
    :severity="variant === 'admin' ? 'info' : 'warn'"
    :closable="false"
    class="mb-4"
    :data-testid="`shift-manage-notice-${variant}`"
  >
    <span v-if="variant === 'deputy'">{{ t('shift.permission.noManagePermission') }}</span>
    <template v-else>
      <span>{{ t('shift.permission.adminNotice') }}</span>
      <NuxtLink
        v-if="teamSlug"
        :to="`/teams/${teamSlug}/permission-groups`"
        class="ml-2 whitespace-nowrap text-primary underline"
        data-testid="shift-manage-notice-link"
      >
        {{ t('shift.permission.adminNoticeLink') }}
      </NuxtLink>
    </template>
  </Message>
</template>
