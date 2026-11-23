<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'
import type { BulletinThreadResponse } from '~/types/bulletin'
import type { TournamentContactPanelDivision, TournamentContactSpace } from '~/types/tournament'

/**
 * 大会連絡スペースの本体（スコープ切替・掲示板/チャットのタブ・掲示板一覧/詳細/作成・チャット）。
 *
 * 主催組織側画面（organizations/[slug]/tournaments/[tId]/communication.vue）と
 * 参加チーム側画面（teams/[slug]/tournaments/[tId]/communication.vue）で共用する
 * （設計書 F08.7.1 01_communication.md §10.5）。
 *
 * - スペースの取得は呼び出し元が行い、現在のスコープのスペースを `spaces` で渡す。
 *   スコープは `v-model:scope` で呼び出し元と共有する（部門スペースの遅延取得は呼び出し元が担う）。
 * - スコープを切り替えると、開いていたスレッドと作成ダイアログを閉じる（別スコープへ持ち越さない）。
 * - 権限は呼び出し元が決めて渡す。既定は「管理操作なし」。主催者専用の UI（公開設定）は
 *   `scope-tools` スロットで呼び出し元が差し込む。
 */

const props = withDefaults(defineProps<{
  /** スコープ切替に出す部門（大会全体ボタンは常に先頭に出る） */
  divisions: TournamentContactPanelDivision[]
  /** 現在のスコープのスペース */
  spaces: TournamentContactSpace[]
  /** 現在のスコープのスペースを取得中か */
  spacesLoading?: boolean
  /** ピン・削除・ロック等の管理操作を許すか（掲示板の can-manage、チャットのピン・他人投稿削除） */
  canManage?: boolean
  /** 新規スレッド作成ボタンを出すか */
  canCreate?: boolean
  /** スレッド詳細で返信できるか */
  canReply?: boolean
  /** チャットで送信できるか */
  canSend?: boolean
  /** チャットの招待ダイアログに渡す組織の識別子 */
  chatOrganizationId?: string
}>(), {
  spacesLoading: false,
  canManage: false,
  canCreate: true,
  canReply: true,
  canSend: true,
  chatOrganizationId: undefined,
})

/** アクティブスコープ: 'tournament'=大会全体 / 部門ID */
const scope = defineModel<'tournament' | number>('scope', { required: true })

const { t } = useI18n()
const { getChannel } = useChatApi()
const { showError } = useNotification()

/** アクティブタブ: 'bulletin'=掲示板 / 'chat'=チャット */
const activeTab = ref<'bulletin' | 'chat'>('bulletin')

/** 掲示板で詳細表示中のスレッド（null=一覧表示） */
const selectedThread = ref<BulletinThreadResponse | null>(null)
/** 新規スレッド作成ダイアログの表示状態 */
const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

/** チャット表示用チャンネル（channelId → ChatChannelResponse） */
const chatChannelCache = ref<Record<number, ChatChannelResponse>>({})
const chatLoading = ref(false)

const activeBulletinSpace = computed<TournamentContactSpace | null>(() => findBulletinSpace(props.spaces))

/** 掲示板 API のスコープ。得られないとき null（API を呼ばず、状態として表示する） */
const activeBulletinScope = computed(() => resolveBulletinScope(activeBulletinSpace.value))

const activeChatChannelId = computed<number | null>(() => resolveChatChannelId(findChatSpace(props.spaces)))

const activeChatChannel = computed<ChatChannelResponse | null>(() => {
  const channelId = activeChatChannelId.value
  if (!channelId) return null
  return chatChannelCache.value[channelId] ?? null
})

function onScopeChange(next: 'tournament' | number) {
  // 別スコープのスレッド・作成フォームを持ち越さない
  selectedThread.value = null
  showCreateDialog.value = false
  scope.value = next
}

function onBulletinSaved() {
  listRef.value?.refresh()
}

async function loadChatChannel(channelId: number) {
  if (chatChannelCache.value[channelId]) return
  chatLoading.value = true
  try {
    const res = await getChannel(channelId)
    chatChannelCache.value[channelId] = res.data
  }
  catch {
    showError(t('tournament.communication.load_failed'))
  }
  finally {
    chatLoading.value = false
  }
}

// チャットタブを開いたとき、またはチャットタブ表示中にスコープ（＝チャンネル）が変わったときに取得する
watch([activeTab, activeChatChannelId], async ([tab, channelId]) => {
  if (tab === 'chat' && channelId) {
    await loadChatChannel(channelId)
  }
})
</script>

<template>
  <div>
    <!-- スコープ切替（大会全体 / 部門別） -->
    <div class="mb-4 flex flex-wrap gap-2">
      <Button
        :label="$t('tournament.communication.tournament_space')"
        :outlined="scope !== 'tournament'"
        size="small"
        @click="onScopeChange('tournament')"
      />
      <Button
        v-for="div in divisions"
        :key="div.id"
        :label="div.name ?? $t('tournament.communication.division_space')"
        :outlined="scope !== div.id"
        size="small"
        @click="onScopeChange(div.id)"
      />
    </div>

    <slot name="scope-tools" />

    <!-- 掲示板 / チャット タブ切替 -->
    <div class="mb-4 flex gap-2 border-b border-surface-200 dark:border-surface-700">
      <button
        type="button"
        class="-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors"
        :class="activeTab === 'bulletin' ? 'border-primary text-primary' : 'border-transparent text-surface-500 hover:text-surface-700'"
        @click="activeTab = 'bulletin'"
      >
        <i class="pi pi-clipboard mr-1" />{{ $t('tournament.communication.bulletin') }}
      </button>
      <button
        type="button"
        class="-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors"
        :class="activeTab === 'chat' ? 'border-primary text-primary' : 'border-transparent text-surface-500 hover:text-surface-700'"
        @click="activeTab = 'chat'"
      >
        <i class="pi pi-comments mr-1" />{{ $t('tournament.communication.chat') }}
      </button>
    </div>

    <!-- スペースがない場合 -->
    <DashboardEmptyState
      v-if="!spacesLoading && spaces.length === 0"
      icon="pi pi-inbox"
      :message="$t('tournament.communication.empty')"
    />

    <template v-else>
      <!-- 掲示板タブ -->
      <template v-if="activeTab === 'bulletin'">
        <div v-if="spacesLoading" class="flex justify-center py-8">
          <LoadingBounce />
        </div>
        <template v-else-if="activeBulletinScope">
          <div v-if="selectedThread" class="mx-auto max-w-3xl">
            <BulletinThreadDetail
              :thread-id="selectedThread.id"
              :can-manage="canManage"
              :can-reply="canReply"
              @back="selectedThread = null"
            />
          </div>
          <template v-else>
            <!-- 投稿権の最終判定はサーバー（403 TOUR_031）。ボタンの出し分けは呼び出し元の canCreate に従う -->
            <BulletinThreadList
              :key="`${activeBulletinScope.scopeType}-${activeBulletinScope.scopeId}`"
              ref="listRef"
              :scope-type="activeBulletinScope.scopeType"
              :scope-id="activeBulletinScope.scopeId"
              :can-manage="canManage"
              :can-create="canCreate"
              @select="(thread: BulletinThreadResponse) => selectedThread = thread"
              @create="showCreateDialog = true"
            />
            <BulletinThreadForm
              v-if="canCreate"
              :key="`form-${activeBulletinScope.scopeType}-${activeBulletinScope.scopeId}`"
              v-model:visible="showCreateDialog"
              :scope-type="activeBulletinScope.scopeType"
              :scope-id="activeBulletinScope.scopeId"
              @saved="onBulletinSaved"
            />
          </template>
        </template>
        <DashboardEmptyState
          v-else-if="activeBulletinSpace"
          icon="pi pi-exclamation-triangle"
          :message="$t('tournament.communication.bulletin_scope_unresolved')"
        />
        <DashboardEmptyState
          v-else
          icon="pi pi-clipboard"
          :message="$t('tournament.communication.no_bulletin')"
        />
      </template>

      <!-- チャットタブ -->
      <template v-if="activeTab === 'chat'">
        <div v-if="spacesLoading || chatLoading" class="flex justify-center py-8">
          <LoadingBounce />
        </div>
        <template v-else-if="activeChatChannel">
          <div class="h-[calc(100vh-18rem)] overflow-hidden rounded-xl border border-surface-300">
            <ChatMessagePanel
              :channel="activeChatChannel"
              :can-pin="canManage"
              :can-delete="canManage"
              :can-send="canSend"
              :organization-id="chatOrganizationId"
            />
          </div>
        </template>
        <DashboardEmptyState
          v-else
          icon="pi pi-comments"
          :message="$t('tournament.communication.no_chat')"
        />
      </template>
    </template>
  </div>
</template>
