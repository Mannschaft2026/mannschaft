<script setup lang="ts">
import type { ContentType, SearchResult, SearchResponse } from '~/types/search'
import { CONTENT_TYPES } from '~/types/search'

definePageMeta({ middleware: 'auth' })

const DETAIL_PAGE_SIZE = 20

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const searchApi = useSearchApi()
const notification = useNotification()

function typeFromRoute(): ContentType | 'ALL' {
  const raw = route.query.type
  return typeof raw === 'string' && (CONTENT_TYPES as readonly string[]).includes(raw)
    ? (raw as ContentType)
    : 'ALL'
}

const query = ref(typeof route.query.q === 'string' ? route.query.q : '')
const activeType = ref<ContentType | 'ALL'>(typeFromRoute())
const results = ref<SearchResult[]>([])
/** 種別タブの件数。概要ビューの typeCounts を保持し、詳細ビューでは該当種別だけ更新する */
const typeCounts = ref<Partial<Record<ContentType, number>>>({})
/** typeCounts がどの検索語の概要件数か。検索語が変わったら概要を取り直し、別検索の件数を混在させない */
const countsQuery = ref('')
const timedOutTypes = ref<ContentType[]>([])
const zeroHelp = ref<SearchResponse['data']['zeroResultsHelp']>(null)
const viewMode = ref<SearchResponse['meta']['viewMode']>('OVERVIEW')
const total = ref(0)
const currentPage = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const searched = ref(false)

const trimmedQuery = computed(() => query.value.trim())
const showPaginator = computed(() => viewMode.value === 'DETAIL' && totalPages.value > 1)
const timedOutLabel = computed(() =>
  timedOutTypes.value.map(type => t(`globalSearch.types.${type}`)).join(', '),
)

async function performSearch(page = 0) {
  if (trimmedQuery.value.length < 2) {
    notification.warn(t('globalSearch.tooShort'))
    return
  }
  loading.value = true
  try {
    const detailType = activeType.value === 'ALL' ? null : activeType.value
    const q = trimmedQuery.value
    // 詳細ビューで検索語が変わった場合は、他種別の件数が前の検索語のまま残らないよう概要も取り直す
    const needsOverview = detailType !== null && countsQuery.value !== q
    const [res, overview] = await Promise.all([
      searchApi.search({
        q,
        types: detailType ? [detailType] : undefined,
        page: detailType ? page : undefined,
        size: detailType ? DETAIL_PAGE_SIZE : undefined,
      }),
      needsOverview ? searchApi.search({ q }) : Promise.resolve(null),
    ])
    results.value = res.data.results
    timedOutTypes.value = res.data.timedOutTypes
    zeroHelp.value = res.data.zeroResultsHelp ?? null
    viewMode.value = res.meta.viewMode
    total.value = res.meta.total
    currentPage.value = res.meta.page
    totalPages.value = res.meta.totalPages
    if (detailType) {
      const base = overview ? overview.data.typeCounts : typeCounts.value
      typeCounts.value = { ...base, [detailType]: res.data.typeCounts[detailType] ?? 0 }
    } else {
      typeCounts.value = { ...res.data.typeCounts }
    }
    countsQuery.value = q
    searched.value = true
    await router.replace({
      query: detailType ? { q: trimmedQuery.value, type: detailType } : { q: trimmedQuery.value },
    })
  } catch (error: unknown) {
    // 失敗は利用者へ通知し、結果表示は前回のまま残す（握りつぶさない）
    console.error('[globalSearch] 検索に失敗', error)
    notification.error(t('globalSearch.failed'))
  } finally {
    loading.value = false
  }
}

function runSearch(page = 0) {
  performSearch(page)
}

function submit() {
  runSearch(0)
}

function handleTypeSelect(type: ContentType | 'ALL') {
  activeType.value = type
  runSearch(0)
}

function handlePageChange(event: { page: number }) {
  runSearch(event.page)
}

function useSuggestion(text: string) {
  query.value = text
  activeType.value = 'ALL'
  runSearch(0)
}

onMounted(() => {
  if (trimmedQuery.value.length >= 2) runSearch(0)
})

// ヘッダー検索欄・サジェスト・戻る操作で URL の q / type が変わったら画面へ反映して再検索する。
// 画面自身が router.replace で書き戻した URL（現在の状態と同じ）では再検索しない。
watch(
  () => [route.query.q, route.query.type] as const,
  () => {
    const q = typeof route.query.q === 'string' ? route.query.q : ''
    const type = typeFromRoute()
    if (q.trim() === trimmedQuery.value && type === activeType.value) return
    query.value = q
    activeType.value = type
    if (trimmedQuery.value.length >= 2) runSearch(0)
  },
)
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <PageHeader :title="t('globalSearch.title')" :back="false" />

    <form class="mb-6 flex gap-2" role="search" @submit.prevent="submit">
      <InputText
        v-model="query"
        class="flex-1"
        :placeholder="t('globalSearch.placeholder')"
        :aria-label="t('globalSearch.placeholder')"
        data-testid="global-search-input"
      />
      <Button
        type="submit"
        :label="t('globalSearch.submit')"
        icon="pi pi-search"
        :loading="loading"
        data-testid="global-search-submit"
      />
    </form>

    <div v-if="searched" class="mb-4">
      <SearchFilterTabs
        :type-counts="typeCounts"
        :active-type="activeType"
        @select="handleTypeSelect"
      />
    </div>

    <div
      v-if="timedOutTypes.length > 0"
      class="mb-4 rounded-lg border border-yellow-300 bg-yellow-50 p-3 text-sm text-yellow-800 dark:border-yellow-700 dark:bg-yellow-950 dark:text-yellow-200"
      data-testid="global-search-timeout"
    >
      <i class="pi pi-exclamation-triangle mr-1" />
      {{ t('globalSearch.timedOut', { types: timedOutLabel }) }}
    </div>

    <PageLoading v-if="loading" />

    <div
      v-else-if="searched && results.length === 0"
      class="py-12 text-center"
      data-testid="global-search-empty"
    >
      <i class="pi pi-search mb-2 text-4xl text-surface-400" />
      <p class="text-surface-500">{{ t('globalSearch.empty') }}</p>
      <div v-if="zeroHelp" class="mt-4 space-y-2">
        <p v-if="zeroHelp.didYouMean" class="text-sm">
          {{ t('globalSearch.didYouMean') }}
          <button
            type="button"
            class="text-primary hover:underline"
            @click="useSuggestion(zeroHelp.didYouMean)"
          >
            {{ zeroHelp.didYouMean }}
          </button>
        </p>
        <p v-if="zeroHelp.broaderQuery" class="text-sm">
          {{ t('globalSearch.broaderQuery') }}
          <button
            type="button"
            class="text-primary hover:underline"
            @click="useSuggestion(zeroHelp.broaderQuery)"
          >
            {{ zeroHelp.broaderQuery }}
          </button>
        </p>
      </div>
    </div>

    <div v-else class="space-y-3" data-testid="global-search-results">
      <SearchResultCard
        v-for="result in results"
        :key="`${result.type}-${result.id}`"
        :result="result"
      />
    </div>

    <div v-if="showPaginator" class="mt-6 flex justify-center" data-testid="global-search-paginator">
      <Paginator
        :rows="DETAIL_PAGE_SIZE"
        :total-records="total"
        :first="currentPage * DETAIL_PAGE_SIZE"
        @page="handlePageChange"
      />
    </div>
  </div>
</template>
