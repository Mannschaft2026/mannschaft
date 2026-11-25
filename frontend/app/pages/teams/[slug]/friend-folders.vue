<script setup lang="ts">
/**
 * F01.5 フレンドフォルダ管理画面 — `/teams/{id}/friend-folders`。
 *
 * 権限:
 * - 認証必須（`auth` middleware）。
 * - フォルダ CRUD 操作は ADMIN、または MANAGE_FRIEND_TEAMS 保持の DEPUTY_ADMIN のみ。
 * - それ以外のロール・非所属は、権限を示唆せず共通の「見つかりません」（ScopeNotFound）にする
 *   （CMP-261125-1108）。権限取得が済むまでは読み込み表示で、「見つかりません」をちらつかせない。
 *
 * UI:
 * - {@link FriendFolderList} でフォルダカード一覧を表示
 * - 「フォルダ作成」ボタン（上限到達時は disabled）
 * - 上限案内「残り{remaining}個作成可能（最大20個）」
 * - 作成/編集ダイアログ（{@link FriendFolderFormDialog}）
 * - メンバー管理パネル（{@link FriendFolderMembersPanel}）
 */
import type { TeamFriendFolderView } from '~/types/friendFolders'

definePageMeta({ layout: 'team', middleware: 'auth' })

/** フォルダ数上限（設計書 §4） */
const FOLDER_MAX = 20

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const { t } = useI18n()
const { isAdmin, isAdminOrDeputy, can, loadPermissions } = useRoleAccess('team', teamSlug)

const { deleteFolder } = useFriendFoldersApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { markIfDenied, markDenied } = useScopeNotFound()
const { confirmAction } = useConfirmDialog()

// ----- 権限 -----
// フォルダ管理操作: ADMIN または DEPUTY_ADMIN で MANAGE_FRIEND_TEAMS 保持
const canManage = computed(
  () => isAdmin.value || (isAdminOrDeputy.value && can('MANAGE_FRIEND_TEAMS')),
)

/** 権限取得が済んだか（成功・失敗いずれでも true）。済むまでは「見つかりません」を出さない。 */
const permissionsResolved = ref(false)

// ----- 一覧コンポーネント参照 -----
const listRef = ref<{
  refresh: () => Promise<void>
  folders: TeamFriendFolderView[]
} | null>(null)

// ----- 作成・編集ダイアログ -----
const formDialogOpen = ref(false)
const editingFolder = ref<TeamFriendFolderView | null>(null)

// ----- メンバー管理パネル -----
const membersPanelOpen = ref(false)
const membersPanelFolder = ref<TeamFriendFolderView | null>(null)

// ----- 残数表示 -----
const folderCount = computed(() => listRef.value?.folders?.length ?? 0)
const remaining = computed(() => Math.max(0, FOLDER_MAX - folderCount.value))
const atLimit = computed(() => folderCount.value >= FOLDER_MAX)

// ----- ハンドラ -----
function openCreateDialog() {
  if (atLimit.value) {
    notification.warn(t('dialog.error'), t('folders.messages.max_exceeded'))
    return
  }
  editingFolder.value = null
  formDialogOpen.value = true
}

function openEditDialog(folder: TeamFriendFolderView) {
  editingFolder.value = folder
  formDialogOpen.value = true
}

function openMembersPanel(folder: TeamFriendFolderView) {
  membersPanelFolder.value = folder
  membersPanelOpen.value = true
}

async function onFolderSaved() {
  await listRef.value?.refresh()
}

function handleDelete(folderId: number) {
  const target = listRef.value?.folders.find((f) => f.id === folderId)
  if (!target) return
  confirmAction({
    header: t('folders.actions.delete'),
    message: t('folders.messages.delete_confirm'),
    onAccept: async () => {
      try {
        await deleteFolder(teamSlug.value, folderId)
        notification.success(t('folders.messages.deleted'))
        await listRef.value?.refresh()
      }
      catch (error) {
        handleApiError(error)
      }
    },
  })
}

async function onMembersUpdated() {
  // メンバー数表示を最新化するため一覧を再取得
  await listRef.value?.refresh()
}

onMounted(async () => {
  const result = await loadPermissions()
  permissionsResolved.value = true
  if (!result.ok) {
    // 非所属・権限なし（403/404）は共通の「見つかりません」へ。それ以外は通常のエラー通知
    if (markIfDenied(result.error)) return
    handleApiError(result.error)
    return
  }
  // 所属していても管理権限が無ければ、権限の有無を示唆せず共通の「見つかりません」へ
  if (!canManage.value) markDenied()
})
</script>

<template>
  <div>
    <PageHeader
      class="md:-mt-1.5"
      :title="t('folders.title')"
      :subtitle="canManage ? t('folders.list.remaining', { remaining }) : undefined"
    >
      <template #actions>
        <Button
          v-if="canManage"
          :label="t('folders.create')"
          icon="pi pi-plus"
          :disabled="atLimit"
          @click="openCreateDialog"
        />
      </template>
    </PageHeader>

    <!-- 権限取得中（権限なしは ScopeNotFound がシェルで本文を置き換える） -->
    <PageLoading v-if="!permissionsResolved" />

    <!-- フォルダ一覧 -->
    <FriendFolderList
      v-else-if="canManage"
      ref="listRef"
      scope-not-found-on-denied
      :team-id="teamSlug"
      :can-edit="canManage"
      @edit="openEditDialog"
      @delete="handleDelete"
      @manage-members="openMembersPanel"
    />

    <!-- 作成・編集ダイアログ -->
    <FriendFolderFormDialog
      v-model="formDialogOpen"
      :team-id="teamSlug"
      :folder="editingFolder"
      @saved="onFolderSaved"
    />

    <!-- メンバー管理パネル -->
    <FriendFolderMembersPanel
      v-model="membersPanelOpen"
      :team-id="teamSlug"
      :folder="membersPanelFolder"
      @updated="onMembersUpdated"
    />
  </div>
</template>
