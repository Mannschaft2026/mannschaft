<script setup lang="ts">
import type { ContentType, SearchResult } from '~/types/search'

const props = defineProps<{
  result: SearchResult
}>()

const { t } = useI18n()
const { formatDate } = useDatetime()

const TYPE_ICONS: Record<ContentType, string> = {
  POST: 'pi pi-comment',
  MESSAGE: 'pi pi-comments',
  THREAD: 'pi pi-list',
  ARTICLE: 'pi pi-file',
  FILE: 'pi pi-paperclip',
  USER: 'pi pi-user',
  TEAM: 'pi pi-users',
  ORGANIZATION: 'pi pi-building',
  ACTIVITY: 'pi pi-calendar',
}

/** 題名の 1 区間。mark=true は highlights.title が指す一致箇所 */
interface TitleSegment {
  text: string
  mark: boolean
}

/**
 * highlights.title（[start, end)・プレーンテキスト基準。BE の Java 文字インデックス＝JS の UTF-16 インデックス）
 * で題名を区切る。v-html を使わずテキスト補間で描画するため、題名中の HTML 風文字列は要素にならない。
 * 範囲が不正（欠落・非整数・逆転・空・範囲外）なら強調せず題名全体を 1 区間で返す。
 */
const titleSegments = computed<TitleSegment[]>(() => {
  const title = props.result.title ?? ''
  const range: unknown = props.result.highlights?.title
  if (!Array.isArray(range) || range.length !== 2) return [{ text: title, mark: false }]
  const [start, end] = range as unknown[]
  if (
    !Number.isInteger(start)
    || !Number.isInteger(end)
    || (start as number) < 0
    || (start as number) >= (end as number)
    || (end as number) > title.length
  ) {
    return [{ text: title, mark: false }]
  }
  const s = start as number
  const e = end as number
  return [
    { text: title.slice(0, s), mark: false },
    { text: title.slice(s, e), mark: true },
    { text: title.slice(e), mark: false },
  ].filter(seg => seg.text.length > 0)
})

const typeIcon = computed(() => TYPE_ICONS[props.result.type] ?? 'pi pi-circle')
const typeLabel = computed(() => t(`globalSearch.types.${props.result.type}`))
/** リンク不可（url=null）の結果はカードとして表示するだけにする */
const linkTarget = computed(() => props.result.url)
// :is に文字列 'NuxtLink' を渡すとリンクにならないため、コンポーネント実体を解決して渡す
const NuxtLinkComponent = resolveComponent('NuxtLink')
</script>

<template>
  <component
    :is="linkTarget ? NuxtLinkComponent : 'div'"
    :to="linkTarget ?? undefined"
    class="block rounded-lg border border-surface-300 p-4 transition-shadow dark:border-surface-600"
    :class="{ 'hover:shadow-md': linkTarget }"
    :data-testid="`global-search-result-${result.type}-${result.id}`"
  >
    <div class="flex items-start gap-3">
      <div class="mt-1 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
        <i :class="typeIcon" />
      </div>
      <div class="min-w-0 flex-1">
        <div class="flex min-w-0 items-center gap-2">
          <!-- バッジは縮めず折り返さない（狭幅で「投/稿」と縦に折れるのを防ぐ）。縮むのはスコープ名の側 -->
          <Badge
            :value="typeLabel"
            severity="secondary"
            class="shrink-0 whitespace-nowrap text-xs"
            data-testid="global-search-result-type-badge"
          />
          <span
            v-if="result.scope"
            class="min-w-0 truncate text-xs text-surface-500"
            data-testid="global-search-result-scope"
          >{{ result.scope.name }}</span>
        </div>
        <!-- title はプレーンテキスト＋highlights.title（設計 F04.6 §4）。v-html を使わず区間ごとに描画する -->
        <p v-if="result.title" class="mt-1 truncate font-medium" data-testid="global-search-result-title">
          <template v-for="(seg, i) in titleSegments" :key="i">
            <mark v-if="seg.mark" class="rounded-sm bg-yellow-200 text-inherit dark:bg-yellow-700/60">{{ seg.text }}</mark>
            <template v-else>{{ seg.text }}</template>
          </template>
        </p>
        <!-- snippet は BE が HTML エスケープ後に <mark> だけを付けたもの。念のため sanitize してから描画する -->
        <!-- eslint-disable vue/no-v-html -->
        <p
          v-if="result.snippet"
          class="mt-1 line-clamp-2 text-sm text-surface-600 dark:text-surface-400"
          v-html="sanitizeHtml(result.snippet)"
        />
        <!-- eslint-enable vue/no-v-html -->
        <!-- 狭幅（360px）で日付がカード外へはみ出さないよう、行は折り返し可・作成者名は省略表示・日付は分割しない -->
        <div
          class="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-surface-400"
          data-testid="global-search-result-meta"
        >
          <span
            v-if="result.author"
            class="min-w-0 max-w-full truncate"
            data-testid="global-search-result-author"
          >
            <i class="pi pi-user mr-1" />{{ result.author.displayName }}
          </span>
          <span
            v-if="result.createdAt"
            class="shrink-0 whitespace-nowrap"
            data-testid="global-search-result-date"
          >{{ formatDate(result.createdAt) }}</span>
        </div>
      </div>
    </div>
  </component>
</template>
