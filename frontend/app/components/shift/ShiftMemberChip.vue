<template>
  <div
    class="relative group inline-flex max-w-full items-center gap-1 rounded-full px-2 py-1 text-sm cursor-grab select-none transition-colors"
    :class="chipClass"
  >
    <!-- 長い氏名はモバイルでは切らずに折り返す（デスクトップは従来どおり省略表示） -->
    <span class="md:truncate md:max-w-24 min-w-0 [overflow-wrap:anywhere]">{{ displayName }}</span>
    <button
      v-if="removable"
      type="button"
      class="ml-1 hover:text-red-500 transition-colors leading-none disabled:opacity-40 disabled:cursor-not-allowed max-md:inline-flex max-md:min-h-11 max-md:min-w-11 max-md:items-center max-md:justify-center"
      :aria-label="removeLabel ?? $t('button.delete')"
      :disabled="disabled"
      data-testid="shift-chip-remove"
      @click.stop="$emit('remove')"
    >
      &times;
    </button>
    <!-- 警告ツールチップ -->
    <span
      v-if="hasWarning && warningMessage"
      class="absolute bottom-full left-1/2 -translate-x-1/2 mb-1 z-50 hidden group-hover:block bg-yellow-800 text-yellow-100 text-xs rounded px-2 py-1 whitespace-nowrap pointer-events-none"
    >
      {{ warningMessage }}
    </span>
  </div>
</template>

<script setup lang="ts">
interface Props {
  displayName: string
  hasWarning?: boolean
  warningMessage?: string
  removable?: boolean
  /** 操作中は外せない（二度押し防止） */
  disabled?: boolean
  /** ×ボタンの読み上げ名（例: 「田中さんの割当を外す」）。未指定は共通の「削除」 */
  removeLabel?: string
}

const props = defineProps<Props>()

defineEmits<{
  remove: []
}>()

const chipClass = computed(() => ({
  'bg-surface-100 text-surface-700 border border-surface-300': !props.hasWarning,
  'bg-yellow-50 text-yellow-800 border-2 border-yellow-400': props.hasWarning,
}))
</script>
