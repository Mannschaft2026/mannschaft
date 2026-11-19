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
      <div class="mt-1 flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10 text-primary">
        <i :class="typeIcon" />
      </div>
      <div class="min-w-0 flex-1">
        <div class="flex items-center gap-2">
          <Badge :value="typeLabel" severity="secondary" class="text-xs" />
          <span v-if="result.scope" class="text-xs text-surface-500">{{ result.scope.name }}</span>
        </div>
        <p v-if="result.title" class="mt-1 truncate font-medium">{{ result.title }}</p>
        <!-- snippet は BE が HTML エスケープ後に <mark> だけを付けたもの。念のため sanitize してから描画する -->
        <!-- eslint-disable vue/no-v-html -->
        <p
          v-if="result.snippet"
          class="mt-1 line-clamp-2 text-sm text-surface-600 dark:text-surface-400"
          v-html="sanitizeHtml(result.snippet)"
        />
        <!-- eslint-enable vue/no-v-html -->
        <div class="mt-2 flex items-center gap-3 text-xs text-surface-400">
          <span v-if="result.author">
            <i class="pi pi-user mr-1" />{{ result.author.displayName }}
          </span>
          <span v-if="result.createdAt">{{ formatDate(result.createdAt) }}</span>
        </div>
      </div>
    </div>
  </component>
</template>
