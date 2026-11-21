<script setup lang="ts">
/**
 * F04.6 全体検索の入口（CMP-261116-0722 PR-8 / AC-E4）。
 *
 * - デスクトップ(md 以上): ヘッダーに検索欄を置き、Enter で /search?q=...（/search 表示中は type も引き継ぐ）へ遷移する。
 * - モバイル: 検索欄は幅を取るため、虫眼鏡アイコンのリンクから /search へ遷移する（q・type があれば引き継ぐ）。
 * - サジェストは BE の GET /search/suggestions（debounce 300ms）が返した候補だけを出す。FE で別データは取らない。
 * - q・type の正本は URL。/search 表示中は URL の値を入力欄へ復元・追従する。
 */
import type { ContentType, Suggestion } from '~/types/search'
import { CONTENT_TYPES } from '~/types/search'

const SUGGEST_DEBOUNCE_MS = 300
const SUGGEST_LIMIT = 5
const MIN_QUERY_LENGTH = 2

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const searchApi = useSearchApi()
const notification = useNotification()

const onSearchPage = computed(() => route.path === '/search')
const routeQuery = computed(() => (onSearchPage.value && typeof route.query.q === 'string' ? route.query.q : ''))
const routeType = computed<ContentType | null>(() => {
  const raw = route.query.type
  return onSearchPage.value && typeof raw === 'string' && (CONTENT_TYPES as readonly string[]).includes(raw)
    ? (raw as ContentType)
    : null
})

const keyword = ref(routeQuery.value)
const suggestions = ref<Suggestion[]>([])
const open = ref(false)
let debounceTimer: ReturnType<typeof setTimeout> | null = null
let requestSeq = 0

// /search の URL（再読込・戻る・サジェスト選択）に入力欄を追従させる
watch(routeQuery, (q) => {
  keyword.value = q
})

const mobileHref = computed(() => {
  if (!routeQuery.value) return '/search'
  const params = new URLSearchParams({ q: routeQuery.value })
  if (routeType.value) params.set('type', routeType.value)
  return `/search?${params.toString()}`
})

function clearSuggestions() {
  requestSeq++
  suggestions.value = []
  open.value = false
}

async function fetchSuggestions(text: string) {
  const seq = ++requestSeq
  try {
    const list = await searchApi.suggestions(text, SUGGEST_LIMIT)
    if (seq !== requestSeq) return
    suggestions.value = list
    open.value = list.length > 0
  } catch (error: unknown) {
    // サジェストは補助機能のため通知はしないが、失敗は握りつぶさずログに残す
    if (seq !== requestSeq) return
    console.error('[headerSearch] サジェストの取得に失敗', error)
    suggestions.value = []
    open.value = false
  }
}

function onInput() {
  if (debounceTimer) clearTimeout(debounceTimer)
  const text = keyword.value.trim()
  if (text.length === 0) {
    clearSuggestions()
    return
  }
  debounceTimer = setTimeout(() => fetchSuggestions(text), SUGGEST_DEBOUNCE_MS)
}

function goSearch(text: string) {
  const q = text.trim()
  if (q.length < MIN_QUERY_LENGTH) {
    notification.warn(t('globalSearch.tooShort'))
    return
  }
  clearSuggestions()
  router.push({ path: '/search', query: routeType.value ? { q, type: routeType.value } : { q } })
}

function selectSuggestion(item: Suggestion) {
  if (item.type !== 'KEYWORD' && item.url) {
    clearSuggestions()
    router.push(item.url)
    return
  }
  keyword.value = item.text
  goSearch(item.text)
}

function suggestionLabel(item: Suggestion) {
  return item.type === 'KEYWORD' ? t('globalSearch.header.keyword') : t(`globalSearch.types.${item.type}`)
}

onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
})
</script>

<template>
  <div class="flex items-center">
    <!-- デスクトップ: 検索欄 -->
    <form
      class="relative hidden md:block"
      role="search"
      data-testid="header-search-form"
      @submit.prevent="goSearch(keyword)"
    >
      <IconField>
        <InputIcon class="pi pi-search" />
        <InputText
          v-model="keyword"
          type="search"
          class="!h-10 w-56 lg:w-72"
          autocomplete="off"
          :placeholder="t('globalSearch.header.placeholder')"
          :aria-label="t('globalSearch.header.placeholder')"
          :aria-expanded="open"
          aria-controls="header-search-suggestions"
          data-testid="header-search-input"
          @input="onInput"
          @blur="open = false"
          @focus="open = suggestions.length > 0"
          @keydown.esc="open = false"
        />
      </IconField>
      <ul
        v-if="open"
        id="header-search-suggestions"
        class="absolute left-0 top-full z-50 mt-1 w-full overflow-hidden rounded-lg border border-surface bg-surface-0 shadow-lg dark:border-surface-700 dark:bg-surface-900"
        role="listbox"
        :aria-label="t('globalSearch.header.suggestions')"
        data-testid="header-search-suggestions"
        @mousedown.prevent
      >
        <li
          v-for="(item, index) in suggestions"
          :key="`${item.type}-${item.text}-${index}`"
          role="option"
          class="flex cursor-pointer items-center justify-between gap-2 px-3 py-2 text-sm hover:bg-surface-100 dark:hover:bg-surface-800"
          :data-testid="`header-search-suggestion-${index}`"
          @click="selectSuggestion(item)"
        >
          <span class="truncate">{{ item.text }}</span>
          <span class="shrink-0 text-xs text-surface-500">{{ suggestionLabel(item) }}</span>
        </li>
      </ul>
    </form>

    <!-- モバイル: アイコンから /search へ -->
    <NuxtLink
      :to="mobileHref"
      class="flex min-h-11 min-w-11 shrink-0 items-center justify-center rounded-lg p-2 transition-colors hover:bg-surface-100 md:hidden dark:hover:bg-surface-800"
      :aria-label="t('globalSearch.header.open')"
      :title="t('globalSearch.header.open')"
      data-testid="header-search-mobile-link"
    >
      <i class="pi pi-search text-surface-600 dark:text-surface-300" />
    </NuxtLink>
  </div>
</template>
