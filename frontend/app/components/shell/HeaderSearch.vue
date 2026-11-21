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
/** キーボードで選択中の候補（-1 = 未選択。未選択の Enter は通常検索） */
const activeIndex = ref(-1)
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
  activeIndex.value = -1
}

async function fetchSuggestions(text: string) {
  const seq = ++requestSeq
  try {
    const list = await searchApi.suggestions(text, SUGGEST_LIMIT)
    if (seq !== requestSeq) return
    suggestions.value = list
    activeIndex.value = -1
    open.value = list.length > 0
  } catch (error: unknown) {
    // サジェストは補助機能のため通知はしないが、失敗は握りつぶさずログに残す
    if (seq !== requestSeq) return
    console.error('[headerSearch] サジェストの取得に失敗', error)
    suggestions.value = []
    open.value = false
  }
}

function cancelPendingSuggest() {
  if (debounceTimer) {
    clearTimeout(debounceTimer)
    debounceTimer = null
  }
}

function onInput() {
  cancelPendingSuggest()
  // 入力が変わった時点で、保留中の古い候補リクエストの結果を無効にする
  requestSeq++
  activeIndex.value = -1
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
  cancelPendingSuggest()
  clearSuggestions()
  router.push({ path: '/search', query: routeType.value ? { q, type: routeType.value } : { q } })
}

function selectSuggestion(item: Suggestion) {
  if (item.type !== 'KEYWORD' && item.url) {
    cancelPendingSuggest()
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

function onKeydown(event: KeyboardEvent) {
  // IME 変換中のキーは変換操作用。候補操作にも検索確定にも使わない（確定 Enter での暗黙のフォーム送信も止める）
  if (event.isComposing || event.keyCode === 229) {
    if (event.key === 'Enter') event.preventDefault()
    return
  }
  if (event.key === 'Escape') {
    open.value = false
    activeIndex.value = -1
    return
  }
  const count = suggestions.value.length
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    if (count === 0) return
    event.preventDefault()
    if (!open.value) {
      open.value = true
      activeIndex.value = event.key === 'ArrowDown' ? 0 : count - 1
      return
    }
    const step = event.key === 'ArrowDown' ? 1 : -1
    // 未選択(-1)からの ArrowDown は先頭、ArrowUp は末尾へ。端では循環する
    activeIndex.value = activeIndex.value === -1
      ? (step === 1 ? 0 : count - 1)
      : (activeIndex.value + step + count) % count
    return
  }
  if (event.key === 'Enter' && open.value && activeIndex.value >= 0) {
    // 候補を選択中の Enter だけ確定に使う。未選択は通常のフォーム送信（検索）に任せる
    event.preventDefault()
    const item = suggestions.value[activeIndex.value]
    if (item) selectSuggestion(item)
  }
}

onBeforeUnmount(() => {
  cancelPendingSuggest()
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
          role="combobox"
          aria-autocomplete="list"
          :aria-activedescendant="open && activeIndex >= 0 ? `header-search-option-${activeIndex}` : undefined"
          :placeholder="t('globalSearch.header.placeholder')"
          :aria-label="t('globalSearch.header.placeholder')"
          :aria-expanded="open"
          aria-controls="header-search-suggestions"
          data-testid="header-search-input"
          @input="onInput"
          @blur="open = false; activeIndex = -1"
          @focus="open = suggestions.length > 0"
          @keydown="onKeydown"
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
          :id="`header-search-option-${index}`"
          :key="`${item.type}-${item.text}-${index}`"
          role="option"
          :aria-selected="index === activeIndex"
          class="flex cursor-pointer items-center justify-between gap-2 px-3 py-2 text-sm hover:bg-surface-100 dark:hover:bg-surface-800"
          :class="{ 'bg-surface-100 dark:bg-surface-800': index === activeIndex }"
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
