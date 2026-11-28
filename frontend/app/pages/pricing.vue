<script setup lang="ts">
/**
 * 公開価格ページ /pricing（認証不要・SSR）。
 *
 * 設計書: docs/features/F20.1_entitlement_billing/05_billing_center.md §公開価格（BC-10 / BC-11 / BC-20）。
 * - `GET /api/v1/public/billing/plans?scopeKind=` を useAsyncData で SSR 取得する。
 * - USER は税込の月額を主表示（税抜・税額・税率を併記）。TEAM/ORG は人数が未確定のため確定額を出さず、
 *   quoteRequired=true のとき「ログイン後に対象チーム/組織で確定見積り」を案内する。
 * - available=false は価格 null（準備中）。guest/auth のリダイレクトは置かない。
 * - プラン名・説明は displayNameKey / descriptionKey を i18n キーとして解決する（認証版 billing/plans.vue と同じ
 *   `billing.plans.*` / `billing.features.*` キーを共用する）。
 */
import type {
  BillingScopeKind,
  PublicBillingMoney,
  PublicBillingPlan,
  PublicBillingPriceBand,
} from '~/composables/useBillingApi'

definePageMeta({ layout: 'landing' })

const { t, locale } = useI18n()
const billingApi = useBillingApi()

useSeoMeta({
  title: () => `${t('billing.pricing.title')} - Mannschaft`,
  description: () => t('billing.pricing.description'),
})
useSeoPublicPage({
  canonicalPath: '/pricing',
  title: () => t('billing.pricing.title'),
  description: () => t('billing.pricing.description'),
})

const scopes: BillingScopeKind[] = ['USER', 'TEAM', 'ORG']
const scopeKind = ref<BillingScopeKind>('USER')
const scopeLabelKey: Record<BillingScopeKind, string> = {
  USER: 'billing.pricing.scopeUser',
  TEAM: 'billing.pricing.scopeTeam',
  ORG: 'billing.pricing.scopeOrg',
}

// キーを scopeKind 依存にすると、タブ切替で自動的に再取得され、スコープごとにキャッシュが分かれる。
const { data: catalog, error, status, refresh } = await useAsyncData(
  () => `public-billing-plans-${scopeKind.value}`,
  async () => (await billingApi.getPublicPlans(scopeKind.value)).data,
)
// 選択中スコープと一致しないカタログ（再取得中の旧スコープ分）は表示しない。
const loading = computed(() =>
  catalog.value?.scopeKind !== scopeKind.value && status.value !== 'error',
)

const plans = computed(() => catalog.value?.plans ?? [])
const addons = computed(() => catalog.value?.addons ?? [])
/** プランの featureKeys は表示名キーを持たないため、アドオン一覧の featureKey→displayNameKey で解決する。 */
const featureNameKey = computed(() => {
  const map = new Map<string, string>()
  for (const a of addons.value) {
    if (a.featureKey && a.displayNameKey) map.set(a.featureKey, a.displayNameKey)
  }
  return map
})
function featureLabels(plan: PublicBillingPlan): string[] {
  return (plan.featureKeys ?? []).map(k => featureNameKey.value.get(k)).filter((k): k is string => !!k)
}

/** 現在ロケール・応答の通貨で金額を整形する（通貨記号の直書きをしない）。 */
const formatMoney = (n: number | undefined, m: PublicBillingMoney) =>
  new Intl.NumberFormat(locale.value, { style: 'currency', currency: m.currency ?? 'JPY' }).format(n ?? 0)
const isFree = (m?: PublicBillingMoney | null) => m?.amountIncludingTax === 0
const bandsOf = (plan: PublicBillingPlan): PublicBillingPriceBand[] =>
  scopeKind.value === 'USER' ? [] : (plan.priceBands ?? [])
</script>

<template>
  <div class="min-h-screen bg-surface-50 dark:bg-surface-900">
    <div id="main-content" class="mx-auto max-w-6xl px-4 py-12">
      <h1 class="mb-3 text-center text-3xl font-bold text-surface-900 dark:text-white md:text-4xl">
        {{ t('billing.pricing.heading') }}
      </h1>
      <p class="mx-auto mb-8 max-w-2xl text-center text-surface-500">
        {{ t('billing.pricing.description') }}
      </p>

      <div class="mb-8 flex justify-center gap-2" role="group" :aria-label="t('billing.pricing.scopeLabel')">
        <button
          v-for="s in scopes"
          :key="s"
          type="button"
          class="min-h-[44px] rounded-full border px-5 text-sm font-medium transition-colors"
          :class="scopeKind === s ? 'border-primary bg-primary text-white' : 'border-surface-300 text-surface-600 hover:bg-surface-100 dark:border-surface-600 dark:text-surface-300 dark:hover:bg-surface-800'"
          :aria-pressed="scopeKind === s"
          :data-testid="`pricing-scope-${s}`"
          @click="scopeKind = s"
        >
          {{ t(scopeLabelKey[s]) }}
        </button>
      </div>

      <PageLoading v-if="loading" />

      <div
        v-else-if="error"
        class="mx-auto max-w-md rounded-lg border border-red-200 bg-red-50 p-6 text-center dark:border-red-900 dark:bg-red-950"
        role="alert"
        data-testid="pricing-error"
      >
        <p class="mb-4 text-sm text-red-700 dark:text-red-300">
          {{ t('billing.pricing.loadError') }}
        </p>
        <Button :label="t('billing.pricing.retry')" severity="secondary" @click="refresh()" />
      </div>

      <template v-else>
        <div class="grid gap-6 md:grid-cols-3">
          <SectionCard v-for="plan in plans" :key="plan.planKey">
            <template #header>
              <h2 class="text-lg font-semibold">
                {{ plan.displayNameKey ? t(plan.displayNameKey) : plan.planKey }}
              </h2>
            </template>
            <div :data-testid="`pricing-plan-${plan.planKey}`" class="contents">
              <p class="mb-3 text-sm text-surface-500">
                {{ plan.descriptionKey ? t(plan.descriptionKey) : '' }}
              </p>

              <div class="mb-4">
                <Tag v-if="!plan.available" :value="t('billing.pricing.comingSoon')" severity="secondary" />
                <p v-else-if="plan.quoteRequired" class="text-sm text-surface-600 dark:text-surface-300">
                  {{ t('billing.pricing.quoteRequired') }}
                </p>
                <template v-else-if="plan.startingMonthlyTotal">
                  <span v-if="isFree(plan.startingMonthlyTotal)" class="text-2xl font-bold">{{ t('billing.pricing.free') }}</span>
                  <template v-else>
                    <span class="text-2xl font-bold">{{ formatMoney(plan.startingMonthlyTotal.amountIncludingTax, plan.startingMonthlyTotal) }}</span>
                    <span class="text-sm text-surface-500">{{ t('billing.plans.perMonth') }}</span>
                    <p class="text-xs text-surface-500">
                      {{ t('billing.pricing.taxIncluded') }}
                      {{ t('billing.pricing.taxBreakdown', {
                        excl: formatMoney(plan.startingMonthlyTotal.amountExcludingTax, plan.startingMonthlyTotal),
                        taxName: plan.startingMonthlyTotal.taxName ?? '',
                        tax: formatMoney(plan.startingMonthlyTotal.taxAmount, plan.startingMonthlyTotal),
                        rate: (plan.startingMonthlyTotal.taxRateBasisPoints ?? 0) / 100,
                      }) }}
                    </p>
                  </template>
                </template>
              </div>

              <ul v-if="plan.available && featureLabels(plan).length > 0" class="space-y-1.5">
                <li v-for="k in featureLabels(plan)" :key="k" class="flex items-start gap-2 text-sm">
                  <i class="pi pi-check mt-0.5 shrink-0 text-primary" aria-hidden="true" />
                  <span>{{ t(k) }}</span>
                </li>
              </ul>

              <ul v-if="plan.available && bandsOf(plan).length > 0" class="mt-3 text-xs text-surface-500">
                <li v-for="(b, i) in bandsOf(plan)" :key="i">
                  {{ b.maxMembers == null
                    ? `${b.minMembers}${t('billing.plans.memberBandUnlimited')}`
                    : t('billing.plans.memberBandRange', { min: b.minMembers, max: b.maxMembers }) }}
                </li>
              </ul>
            </div>
          </SectionCard>
        </div>

        <div v-if="addons.length > 0" class="mt-10">
          <h2 class="mb-4 text-xl font-bold text-surface-900 dark:text-white">
            {{ t('billing.pricing.addonsHeading') }}
          </h2>
          <ul class="grid gap-3 md:grid-cols-2">
            <li
              v-for="a in addons"
              :key="a.featureKey"
              class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
              :data-testid="`pricing-addon-${a.featureKey}`"
            >
              <p class="font-medium">
                {{ a.displayNameKey ? t(a.displayNameKey) : a.featureKey }}
              </p>
              <p class="mb-2 text-xs text-surface-500">
                {{ a.descriptionKey ? t(a.descriptionKey) : '' }}
              </p>
              <Tag v-if="!a.available" :value="t('billing.pricing.comingSoon')" severity="secondary" />
              <p v-else-if="a.quoteRequired" class="text-sm">
                {{ t('billing.pricing.quoteRequired') }}
              </p>
              <p v-else-if="a.startingMonthlyTotal" class="text-sm font-semibold">
                <template v-if="isFree(a.startingMonthlyTotal)">
                  {{ t('billing.pricing.free') }}
                </template>
                <template v-else>
                  {{ formatMoney(a.startingMonthlyTotal.amountIncludingTax, a.startingMonthlyTotal) }}{{ t('billing.plans.perMonth') }}
                  <span class="text-xs font-normal text-surface-500">{{ t('billing.pricing.taxIncluded') }}</span>
                </template>
              </p>
            </li>
          </ul>
        </div>

        <div class="mt-12 flex flex-wrap justify-center gap-3">
          <NuxtLink to="/register">
            <Button :label="t('billing.pricing.register')" />
          </NuxtLink>
          <NuxtLink to="/login">
            <Button :label="t('billing.pricing.login')" severity="secondary" />
          </NuxtLink>
        </div>
      </template>
    </div>
  </div>
</template>
