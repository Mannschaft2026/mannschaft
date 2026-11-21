<script setup lang="ts">
import type { ActionMemoInputExtras, Mood } from '~/types/actionMemo'

/**
 * F02.5 行動メモ入力フォーム。
 *
 * <p>設計書 §4.x / §5.1 に従い、以下の要件を満たす:</p>
 * <ul>
 *   <li>Enter で送信、Shift+Enter で改行</li>
 *   <li>空文字 / 5,000 文字超で送信ボタン disabled</li>
 *   <li>{@code mood_enabled = true} の場合のみ MoodSelector を表示</li>
 *   <li>下書き自動保存（debounce 1秒）→ 送信成功でクリア</li>
 *   <li>送信失敗時は入力欄をクリアしない（書きかけを失わせない）</li>
 *   <li>画面側で選んだカテゴリ・所要時間・進捗率・TODO 完了・チーム投稿先は {@code extras} で受け取り、
 *       作成リクエストに載せる（チームは作成成功後に publish-to-team。WORK のみ）</li>
 * </ul>
 */

const props = defineProps<{
  extras?: ActionMemoInputExtras
}>()

const emit = defineEmits<{
  created: [memoId: number]
  /** 下書きと一緒に保存されていた選択値（カテゴリ・投稿先・所要時間など）を復元したとき */
  'draft-restored': [extras: ActionMemoInputExtras]
}>()

const store = useActionMemoStore()
const auth = useAuthStore()
const { t } = useI18n()

const MAX_LENGTH = 5000
const DRAFT_DEBOUNCE_MS = 1000

const content = ref<string>('')
const mood = ref<Mood | null>(null)
const selectedTagIds = ref<number[]>([])
const submitting = ref(false)
const draftSaveTimer = ref<ReturnType<typeof setTimeout> | null>(null)
const draftSavedFlash = ref(false)
// 作成済みだが公開に失敗したメモ。再送信は作成せず公開だけ再試行する（入力は保持）
// 下書きにも保存し、画面を離れて戻っても重複作成せず公開だけ再試行できるようにする
const pendingPublish = ref<{ memoId: number; teamId: number; content: string } | null>(null)

function pendingForDraft() {
  return pendingPublish.value ? { memoId: pendingPublish.value.memoId, teamId: pendingPublish.value.teamId } : null
}

const userId = computed<number | string>(() => auth.user?.id ?? 'anon')

const charCount = computed(() => content.value.length)
const tooLong = computed(() => charCount.value > MAX_LENGTH)
const isEmpty = computed(() => content.value.trim().length === 0)
const canSubmit = computed(() => !isEmpty.value && !tooLong.value && !submitting.value)

// === 下書きの復元 ===
onMounted(() => {
  const draft = store.loadDraft(userId.value)
  if (draft) {
    content.value = draft
    const savedExtras = store.loadDraftExtras(userId.value)
    if (savedExtras) emit('draft-restored', savedExtras)
    const pp = store.loadDraftPendingPublish(userId.value)
    if (pp) pendingPublish.value = { memoId: pp.memoId, teamId: pp.teamId, content: draft }
  }
})

// === 下書きの自動保存 (debounce 1秒) ===
// 本文の変更に加え、画面側の選択値の変更も下書きへ反映する（選択値は送信対象のため一緒に保存）
watch([content, () => props.extras], ([next]) => {
  // 公開待ちのメモとは別の内容に編集されたら、再試行状態は破棄する（新規メモとして扱う）
  if (pendingPublish.value && next !== pendingPublish.value.content) pendingPublish.value = null
  if (draftSaveTimer.value) clearTimeout(draftSaveTimer.value)
  draftSaveTimer.value = setTimeout(() => {
    store.saveDraft(userId.value, content.value, props.extras, pendingForDraft())
    if (next.length > 0) {
      draftSavedFlash.value = true
      setTimeout(() => (draftSavedFlash.value = false), 1500)
    }
    draftSaveTimer.value = null
  }, DRAFT_DEBOUNCE_MS)
})

onBeforeUnmount(() => {
  if (draftSaveTimer.value) clearTimeout(draftSaveTimer.value)
})

// === 送信処理 ===
function resetAfterSuccess(memoId: number) {
  emit('created', memoId)
  // 成功 → 入力欄クリア + mood + タグ リセット
  content.value = ''
  mood.value = null
  selectedTagIds.value = []
  pendingPublish.value = null
  store.clearDraft(userId.value)
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    // 公開だけ失敗している場合は、メモを再作成せず公開のみ再試行する
    const pending = pendingPublish.value
    if (pending) {
      if (await store.publishToTeam(pending.memoId, { teamId: pending.teamId })) {
        resetAfterSuccess(pending.memoId)
      }
      return
    }

    // 送信開始時に値を固定する（応答待ち中の画面側の選択変更を、送信済みメモに波及させない）
    const snap = { ...props.extras }
    const publishTeamId = snap.category === 'WORK' && snap.teamId != null ? snap.teamId : undefined
    const created = await store.createMemo(
      {
        content: content.value,
        mood: store.isMoodEnabled ? mood.value : null,
        tagIds: selectedTagIds.value.length > 0 ? selectedTagIds.value : undefined,
        category: snap.category,
        durationMinutes: snap.durationMinutes ?? undefined,
        progressRate: snap.progressRate ?? undefined,
        completesTodo: snap.completesTodo ? true : undefined,
      },
      { publishTeamId },
    )
    if (!created) return // 失敗時は入力欄を保持（下書きとしてそのまま）

    // チーム投稿は作成 API に項目が無いため別 API。オフライン（仮 ID=負数）はキューに公開要求を持たせ、
    // 同期後に store が実行する。
    if (publishTeamId != null && created.id > 0) {
      const memoContent = content.value
      if (!(await store.publishToTeam(created.id, { teamId: publishTeamId }))) {
        pendingPublish.value = { memoId: created.id, teamId: publishTeamId, content: memoContent }
        // 即時に下書きへ保存（debounce を待たずに画面を離れても、再送信で重複作成させない）
        store.saveDraft(userId.value, memoContent, props.extras, pendingForDraft())
        return
      }
    }
    resetAfterSuccess(created.id)
  } finally {
    submitting.value = false
  }
}

function onKeydown(event: KeyboardEvent) {
  // Shift+Enter は通常の改行（テキストエリアのデフォルト動作に任せる）
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}
</script>

<template>
  <DashboardWidgetCard :scrollable="false">
    <form
      class="flex flex-col gap-2"
      data-testid="action-memo-input"
      @submit.prevent="submit"
    >
      <textarea
        v-model="content"
        data-testid="action-memo-input-textarea"
        :placeholder="t('action_memo.input.placeholder')"
        rows="3"
        class="w-full resize-y rounded-lg border border-surface-200 bg-transparent p-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:border-surface-700"
        @keydown="onKeydown"
      />

      <div v-if="store.isMoodEnabled" class="px-1">
        <MoodSelector v-model="mood" />
      </div>

      <div class="px-1">
        <TagPicker v-model="selectedTagIds" />
      </div>

      <div class="flex flex-wrap items-center justify-between gap-2 px-1 text-xs">
        <div class="flex items-center gap-3">
          <span
            :class="
              tooLong ? 'text-rose-600' : 'text-surface-500 dark:text-surface-400'
            "
            data-testid="action-memo-input-charcount"
          >
            {{ t('action_memo.input.char_count', { count: charCount }) }}
          </span>
          <span class="hidden text-surface-400 sm:inline">
            {{ t('action_memo.input.shift_enter_hint') }}
          </span>
          <span
            v-if="pendingPublish"
            class="text-rose-600"
            role="alert"
            data-testid="action-memo-input-publish-failed"
          >
            {{ t('action_memo.input.publish_failed') }}
          </span>
          <span
            v-if="draftSavedFlash"
            class="text-emerald-500"
            data-testid="action-memo-input-draftsaved"
          >
            {{ t('action_memo.input.draft_saved') }}
          </span>
        </div>
        <button
          type="submit"
          data-testid="action-memo-input-submit"
          :disabled="!canSubmit"
          class="rounded-lg bg-primary px-4 py-1.5 text-sm font-medium text-white transition-colors disabled:cursor-not-allowed disabled:opacity-50"
        >
          {{ t('action_memo.input.submit') }}
        </button>
      </div>
    </form>
  </DashboardWidgetCard>
</template>
