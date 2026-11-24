<script setup lang="ts">
import type { ActionMemo } from '~/types/actionMemo'

/**
 * 行動メモ → 公開した個人投稿への導線（CMP-261123-1201）。
 *
 * <p>選択日の個人投稿（本人だけが見られる）へのリンクを出す。リンク先は
 * overridePostId（公開応答で得た ID。あれば最優先）→ memos の非 null な timelinePostId の最初の値。
 * 無ければ何も描画しない。チーム投稿 ID（teamTimelinePostId）へはリンクしない（/timeline に出るため）。</p>
 */
const props = defineProps<{
  memos: ActionMemo[]
  overridePostId?: number | null
}>()

const { t } = useI18n()

const postId = computed<number | null>(() => {
  if (props.overridePostId != null) return props.overridePostId
  return props.memos.find((m) => m.timelinePostId != null)?.timelinePostId ?? null
})
</script>

<template>
  <div
    v-if="postId !== null"
    class="flex flex-wrap items-center gap-x-3 gap-y-1 px-1"
    data-testid="action-memo-published-post"
  >
    <NuxtLink
      :to="`/timeline/${postId}`"
      class="inline-flex min-h-11 items-center gap-1 rounded-lg px-2 text-sm font-medium text-primary hover:bg-primary/10"
      data-testid="action-memo-published-post-link"
    >
      <i class="pi pi-eye text-xs" aria-hidden="true" />
      {{ t('action_memo.published_post.view_link') }}
    </NuxtLink>
    <span class="text-xs text-surface-500 dark:text-surface-400" data-testid="action-memo-published-post-note">
      {{ t('action_memo.published_post.published_note') }}
    </span>
  </div>
</template>
