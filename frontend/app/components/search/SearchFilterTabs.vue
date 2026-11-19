<script setup lang="ts">
import type { ContentType } from '~/types/search'
import { CONTENT_TYPES } from '~/types/search'

const props = defineProps<{
  typeCounts: Partial<Record<ContentType, number>>
  activeType: ContentType | 'ALL'
}>()

const emit = defineEmits<{
  select: [type: ContentType | 'ALL']
}>()

const { t } = useI18n()

const totalCount = computed(() =>
  CONTENT_TYPES.reduce((sum, type) => sum + (props.typeCounts[type] ?? 0), 0),
)

/** 件数のある種別と、選択中の種別をタブにする（BE の種別宣言順） */
const tabs = computed(() => {
  const items: Array<{ type: ContentType | 'ALL'; label: string; count: number }> = [
    { type: 'ALL', label: t('globalSearch.types.ALL'), count: totalCount.value },
  ]
  for (const type of CONTENT_TYPES) {
    const count = props.typeCounts[type] ?? 0
    if (count > 0 || props.activeType === type) {
      items.push({ type, label: t(`globalSearch.types.${type}`), count })
    }
  }
  return items
})
</script>

<template>
  <div class="flex flex-wrap gap-2" role="tablist">
    <Button
      v-for="tab in tabs"
      :key="tab.type"
      :label="`${tab.label} (${tab.count})`"
      :severity="activeType === tab.type ? 'primary' : 'secondary'"
      :outlined="activeType !== tab.type"
      :aria-selected="activeType === tab.type"
      :data-testid="`global-search-tab-${tab.type}`"
      role="tab"
      size="small"
      @click="emit('select', tab.type)"
    />
  </div>
</template>
