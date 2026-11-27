<script setup lang="ts">
/**
 * 非所属・権限なし・不存在のチーム／組織の子ページで、本文に出す共通の「見つかりません」（CMP-261125-1108）。
 *
 * 権限・所属・存在の有無を示唆しない中立文言。再試行は出さない（5xx・通信断の汎用エラーとは別物）。
 * シェルのタブ・戻る導線は親側に残る。
 */
const { t } = useI18n()
</script>

<template>
  <div data-testid="scope-not-found" class="mx-auto max-w-2xl p-6 text-center">
    <i class="pi pi-exclamation-circle text-4xl text-surface-400" aria-hidden="true" />
    <h2 class="mt-4 text-lg font-medium text-surface-700 dark:text-surface-200">
      {{ t('common.scopeNotFound.title') }}
    </h2>
    <p class="mt-1 text-sm text-surface-500">
      {{ t('common.scopeNotFound.body') }}
    </p>
    <NuxtLink to="/dashboard" class="mt-4 inline-flex min-h-11 items-center text-primary-600 hover:underline">
      <i class="pi pi-arrow-left mr-1" aria-hidden="true" />
      {{ t('common.scopeShell.back_to_dashboard') }}
    </NuxtLink>
  </div>
</template>
