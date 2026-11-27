<script setup lang="ts">
/**
 * 特定商取引法に基づく表記をモーダルで表示。v-model:visible で開閉。本体は CommerceDisclosureContent に分離。
 * anchor="contact" を指定すると、開いた後にお問い合わせ項目へスクロールする。
 * ページ（/commerce-disclosure）と同時に DOM にあっても id が重複しないよう、本文の contact id は付けない。
 */
const props = defineProps<{
  anchor?: 'contact'
}>()
const visible = defineModel<boolean>('visible', { default: false })
const { t } = useI18n()

const bodyRef = ref<HTMLElement | null>(null)

// 開いた直後（本文が DOM に載った後）に anchor の項目へスクロールする。
// Dialog の show イベント（トランジション完了依存）には頼らず、本文の要素参照が付いた時点で行う
// （Dialog は開くまで本文を描画しないため bodyRef の設定が「本文が載った」合図になる）。
watch(
  [visible, () => props.anchor, bodyRef],
  ([isVisible, anchor, body]) => {
    if (!isVisible || !anchor || !body) return
    body.querySelector(`[data-section="${anchor}"]`)?.scrollIntoView({ block: 'start' })
  },
  { flush: 'post' },
)
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('landing.legal.commerce.title')"
    class="w-full max-w-2xl"
    data-testid="commerce-disclosure-modal"
  >
    <div ref="bodyRef" class="overflow-y-auto py-2" style="max-height: 70vh">
      <CommerceDisclosureContent hide-title contact-id="" />
    </div>
    <template #footer>
      <Button :label="t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
