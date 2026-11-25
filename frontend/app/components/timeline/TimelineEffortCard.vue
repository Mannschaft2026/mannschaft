<script setup lang="ts">
import type { TimelinePostResponse } from '~/types/timeline'

/**
 * F06.4.1 努力由来のタイムライン投稿カード（親設計 §2.2）。
 *
 * <p>通常の投稿と違い、いいね・みたよ・コメント・返信・引用・再投稿・ブックマーク・ピン留め・削除・編集の操作を
 * 一切出さない（BE は EFFORT_003 で拒否する）。共有をやめるときは記録の「共有を取り消す」から行う。
 * 値そのものは読み取り時に F00 を通した共有からだけ組み立てるため、ここでは見出しと「活動履歴で見る」だけを出す。</p>
 */
const props = defineProps<{ post: TimelinePostResponse }>()

const { t } = useI18n()
const route = useRoute()

/** リンクの slug。チームのタイムラインでは現在のルート、個人集約タイムラインでは投稿の scope.slug。 */
const teamSlug = computed<string | null>(() => {
  const fromRoute = route.params.slug
  if (route.path.startsWith('/teams/') && typeof fromRoute === 'string' && fromRoute !== '') return fromRoute
  const scope = props.post.scope
  if (scope?.scopeType === 'TEAM' && scope.slug) return scope.slug
  return null
})

const authorName = computed(() => props.post.user?.displayName ?? '')
</script>

<template>
  <div
    class="rounded-xl border-2 border-surface-400 bg-surface-0 p-4 dark:bg-surface-800"
    data-testid="timeline-effort-card"
  >
    <div class="flex items-center gap-2 text-sm font-medium text-surface-700 dark:text-surface-100">
      <i class="pi pi-chart-line text-primary" aria-hidden="true" />
      <span>{{ t('effort.teamHistory.sharedBy', { name: authorName }) }}</span>
    </div>
    <div v-if="teamSlug" class="mt-2">
      <NuxtLink
        :to="`/teams/${teamSlug}/effort-records`"
        class="inline-flex min-h-11 items-center gap-1 text-sm font-medium text-primary hover:underline"
        data-testid="timeline-effort-link"
      >
        {{ t('effort.teamHistory.fromTimeline') }}
        <i class="pi pi-arrow-right text-xs" aria-hidden="true" />
      </NuxtLink>
    </div>
  </div>
</template>
