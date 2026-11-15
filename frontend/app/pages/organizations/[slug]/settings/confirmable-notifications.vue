<script setup lang="ts">
/**
 * F04.9 §CMP-260909-1141: 組織確認通知設定ページ。
 *
 * teams/[slug]/settings/confirmable-notifications.vue の組織版。
 * 背景・移設理由はそちらの Javadoc コメントを参照。
 *
 * scopeId は親シェル（useOrgShellContext）に頼らず、ページ自身が slug から組織を取得して解決する。
 * 親シェルは SHELL_SEGMENTS に一致するルートでしか組織を読み込まず、settings 配下では org が null のまま
 * 子が描画され、/api/v1/organizations//confirmable-* を要求してしまう欠陥があった（CMP-261115-1635）。
 */
definePageMeta({ layout: 'organization', middleware: ['auth', 'confirmable-notification-guard'] })

const route = useRoute()
const orgApi = useOrganizationApi()
const orgSlug = computed(() => String(route.params.slug))
const scopeId = ref('')
const scopeLoading = ref(true)
const groupsVersion = ref(0)
const templatesVersion = ref(0)
const circulationPath = computed(() => `/organizations/${String(route.params.slug)}/circulation`)

const historyRef = ref<{ refresh: () => void } | null>(null)
function onNotificationSent() {
  historyRef.value?.refresh()
}

onMounted(async () => {
  try {
    const response = await orgApi.getOrganization(String(route.params.slug))
    const numericId = Number(response.data.numericId)
    if (!Number.isSafeInteger(numericId) || numericId <= 0) throw new Error('organization_numeric_id_missing')
    scopeId.value = String(numericId)
  }
  catch {
    showError(createError({ statusCode: 503, statusMessage: 'Service Unavailable', fatal: true }))
  }
  finally {
    scopeLoading.value = false
  }
})
</script>

<template>
  <div class="mx-auto max-w-4xl p-4">
    <PageHeader :title="$t('confirmable.page.settings_title')" />
    <p class="-mt-3 mb-5 text-sm text-surface-500">
      {{ $t('confirmable.page.settings_subtitle') }}
    </p>
    <ConfirmableCirculationGuide current-feature="quickConfirm" :target-path="circulationPath" />

    <PageLoading v-if="scopeLoading" />
    <template v-else-if="scopeId">
      <!-- 日常操作を先に、低頻度の設定を最後に表示する。 -->
      <section class="mt-8">
        <h2 class="mb-4 text-lg font-semibold">{{ $t('confirmable.send') }}</h2>
        <ConfirmableNotificationSender
          scope-type="ORGANIZATION"
          :scope-id="scopeId"
          :scope-slug="orgSlug"
          :groups-version="groupsVersion + templatesVersion"
          @sent="onNotificationSent"
        />
      </section>

      <section class="mt-8">
        <h2 class="mb-4 text-lg font-semibold">{{ $t('confirmable.history') }}</h2>
        <ConfirmableNotificationHistory
          ref="historyRef"
          scope-type="ORGANIZATION"
          :scope-id="scopeId"
        />
      </section>

      <section class="mt-8">
        <h2 class="mb-4 text-lg font-semibold">{{ $t('confirmable.recipient_groups') }}</h2>
        <ConfirmableRecipientGroupManager scope-type="ORGANIZATION" :scope-id="scopeId" :scope-slug="orgSlug" @changed="groupsVersion++" />
      </section>

      <section class="mt-8">
        <ConfirmableTemplateManager scope-type="ORGANIZATION" :scope-id="scopeId" :groups-version="groupsVersion" @changed="templatesVersion++" />
      </section>

      <section class="mt-8">
        <h2 class="mb-4 text-lg font-semibold">{{ $t('confirmable.settings') }}</h2>
        <ConfirmableNotificationSettings scope-type="ORGANIZATION" :scope-id="scopeId" />
      </section>
    </template>
  </div>
</template>
