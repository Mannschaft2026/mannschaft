<script setup lang="ts">
/**
 * シフト管理の権限に関する案内カード（CMP-261125-2114）。PageHeader の下に置く。
 *
 * - variant="admin": チーム ADMIN 向け告知。副管理者は権限グループで「シフトの管理」を付与されないとシフトを
 *   管理できない（閲覧のみ）ことと、その手順を伝え、権限グループ画面へのリンクを付ける。
 *   閉じられる。閉じた状態はチーム別に localStorage へ保存する（読み書きできない環境では毎回表示する）。
 * - variant="deputy": 「シフトの管理」を持たない副管理者向け。操作できない理由と、次の行動（管理者への依頼）を伝える。
 */
const props = defineProps<{
  variant: 'admin' | 'deputy'
  /** 権限グループ画面へのリンク・閉じた状態の保存キー用（admin のときだけ使う）。 */
  teamSlug?: string | null
}>()

const { t } = useI18n()

const dismissed = ref(false)

function storageKey(): string | null {
  return props.teamSlug ? `shift-manage-notice-dismissed:${props.teamSlug}` : null
}

function readDismissed(): boolean {
  const key = storageKey()
  if (props.variant !== 'admin' || !key) return false
  try {
    return window.localStorage.getItem(key) === '1'
  }
  catch {
    // 読めない環境（プライベートウィンドウ・ブロック等）は表示する側に倒す。
    return false
  }
}

function onClose() {
  dismissed.value = true
  const key = storageKey()
  if (!key) return
  try {
    window.localStorage.setItem(key, '1')
  }
  catch {
    // 保存できなくても、このページ表示中は閉じたままにする（次回は再表示される）。
  }
}

// SSR で window が無いので、マウント後・チーム切替時に読み直す。
onMounted(() => {
  dismissed.value = readDismissed()
})
watch(() => props.teamSlug, () => {
  dismissed.value = readDismissed()
})
</script>

<template>
  <Message
    v-if="!(variant === 'admin' && dismissed)"
    :severity="variant === 'admin' ? 'info' : 'warn'"
    :closable="variant === 'admin'"
    class="mb-4"
    :data-testid="`shift-manage-notice-${variant}`"
    @close="onClose"
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
