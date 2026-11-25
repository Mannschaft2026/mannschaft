<script setup lang="ts">
/**
 * チーム配下画面が開けないときの理由表示（CMP-261124-0159 PR-F1 / AC-0159-86・87・88）。
 *
 * - forbidden: チームの公開範囲の外（メンバー限定のチームなど）。空表示やトーストにせず、理由を出す。
 * - notFound : チームが見つからない（URL 誤り・削除）。
 * チームの存在・非存在の判別材料を増やさないよう、文言は一般的な理由に留める（件名や人数などは出さない）。
 */
defineProps<{
  kind: 'forbidden' | 'notFound'
}>()

const { t } = useI18n()
</script>

<template>
  <div
    class="mx-auto max-w-2xl p-6 text-center"
    data-testid="team-access-denied"
    :data-kind="kind"
    role="alert"
  >
    <i
      :class="['pi', kind === 'forbidden' ? 'pi-lock' : 'pi-search', 'text-4xl text-surface-400']"
      aria-hidden="true"
    />
    <p class="mt-4 text-lg font-medium text-surface-700 dark:text-surface-200">
      {{ kind === 'forbidden' ? t('team.access.forbiddenTitle') : t('team.access.notFoundTitle') }}
    </p>
    <p class="mt-1 text-sm text-surface-500">
      {{ kind === 'forbidden' ? t('team.access.forbiddenBody') : t('team.access.notFoundBody') }}
    </p>
    <div class="mt-4 flex flex-col items-center gap-3">
      <NuxtLink to="/dashboard" class="inline-flex min-h-11 items-center text-primary-600 hover:underline">
        <i class="pi pi-arrow-left mr-1" aria-hidden="true" />
        {{ t('team.access.backToHome') }}
      </NuxtLink>
    </div>
  </div>
</template>
