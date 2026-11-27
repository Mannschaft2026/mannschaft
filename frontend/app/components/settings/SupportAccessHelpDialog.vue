<script setup lang="ts">
/**
 * サポート閲覧の許可（設定 > サポート閲覧）の使い方モーダル。
 * 「色付き丸アイコン＋カード」方式。モーダルの上にモーダルは重ねない（本画面は許可・再認証をインラインで行う）。
 * i18n: common.json の supportAccess.help.*
 */
const visible = defineModel<boolean>('visible', { default: false })
const { t } = useI18n()

const cards = [
  { key: 'what', icon: 'pi-eye', tone: 'blue' },
  { key: 'allow', icon: 'pi-check-circle', tone: 'green' },
  { key: 'limits', icon: 'pi-lock', tone: 'violet' },
  { key: 'revoke', icon: 'pi-ban', tone: 'orange' },
  { key: 'logs', icon: 'pi-history', tone: 'teal' },
] as const

const toneClass: Record<(typeof cards)[number]['tone'], string> = {
  blue: 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400',
  green: 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400',
  violet: 'bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400',
  orange: 'bg-orange-100 text-orange-600 dark:bg-orange-900/30 dark:text-orange-400',
  teal: 'bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400',
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('supportAccess.help.title')"
    class="w-full max-w-2xl"
    data-testid="support-access-help-modal"
  >
    <div class="space-y-4 overflow-y-auto py-2">
      <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
        {{ t('supportAccess.help.description') }}
      </p>
      <SectionCard v-for="card in cards" :key="card.key">
        <div class="flex items-start gap-4">
          <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full" :class="toneClass[card.tone]">
            <i class="pi text-xl" :class="card.icon" aria-hidden="true" />
          </div>
          <div>
            <h2 class="mb-2 text-lg font-semibold">
              {{ t(`supportAccess.help.${card.key}.title`) }}
            </h2>
            <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
              {{ t(`supportAccess.help.${card.key}.body`) }}
            </p>
          </div>
        </div>
      </SectionCard>
    </div>
    <template #footer>
      <Button :label="t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
