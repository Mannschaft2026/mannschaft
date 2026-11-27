<script setup lang="ts">
import Button from 'primevue/button'

/**
 * F10.9 サポート閲覧中バナー（戦役Z-B）。旧・管理者変身のバナーを置き換える。
 * 対象者・残り時間・「閲覧のみ」・終了ボタンを画面最上部に固定表示する。
 * 閲覧が終わった（終了ボタン・期限切れ・取消）ら、本人データを持たない画面へ全読み込みで戻す。
 */
const store = useSupportAccessStore()
const { t } = useI18n()
const notification = useNotification()

const ENDED_PATH = '/system-admin/support-access'

const remaining = computed(() => {
  const total = Math.max(0, store.remainingSeconds)
  return { hours: Math.floor(total / 3600), minutes: Math.floor((total % 3600) / 60) }
})

const ending = ref(false)

// 閲覧のみの共通表示: 閲覧中は html にクラスを付け、保存・送信ボタン（type="submit"）を無効に見せる（main.css）。
// 書込は BE が SUPPORT_ACCESS_003 で必ず拒否する（useApi がトーストで知らせる）。ここは壊れたように見せないための表示。
watchEffect(() => {
  if (import.meta.client) document.documentElement.classList.toggle('support-access-readonly', store.isActive)
})
onBeforeUnmount(() => {
  if (import.meta.client) document.documentElement.classList.remove('support-access-readonly')
})

/** 全読み込みで運営者本人の画面へ戻る。本人の画面を描くために読み込んだ Pinia・各キャッシュがすべて破棄される。 */
function returnToOperatorView() {
  if (import.meta.client) window.location.assign(ENDED_PATH)
}

async function endViewing() {
  if (ending.value) return
  ending.value = true
  try {
    await store.endSession()
    returnToOperatorView()
  }
  catch {
    // 手元の閲覧状態とキャッシュはストア側で必ず消えている。BE へ終了を記録できなかったことを伝えてから戻る。
    notification.warn(t('supportAccess.banner.endError'))
    setTimeout(returnToOperatorView, 3000)
  }
}

// 終了ボタン以外（期限切れ・BE 側の取消）で閲覧中でなくなったときも、同じ画面へ戻る。
watch(
  () => store.isActive,
  (active, wasActive) => {
    if (wasActive && !active && !ending.value) {
      notification.info(t('supportAccess.banner.expired'))
      setTimeout(returnToOperatorView, 1500)
    }
  },
)
</script>

<template>
  <Teleport to="body">
    <div
      v-if="store.isActive"
      class="fixed top-0 left-0 right-0 z-[9999] flex flex-wrap items-center justify-between gap-2 bg-orange-500 px-4 py-2 text-white shadow-md"
      role="status"
      data-testid="support-access-banner"
    >
      <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
        <i class="pi pi-eye text-white" />
        <span class="text-sm font-semibold">
          {{ t('supportAccess.banner.viewing', { name: store.targetDisplayName }) }}
        </span>
        <span class="rounded bg-white/20 px-2 py-0.5 text-xs font-semibold">
          {{ t('supportAccess.banner.readOnly') }}
        </span>
        <span class="text-xs">
          {{ t('supportAccess.banner.remaining', { hours: remaining.hours, minutes: remaining.minutes }) }}
        </span>
      </div>
      <Button
        :label="t('supportAccess.banner.end')"
        icon="pi pi-times"
        text
        size="small"
        class="text-white hover:text-orange-100"
        :loading="ending"
        data-testid="support-access-banner-end"
        @click="endViewing"
      />
    </div>
  </Teleport>
</template>
