<script setup lang="ts">
import type { TournamentContactSpace, TournamentDivision } from '~/types/tournament'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgSlug = String(route.params.slug)
const tId = Number(route.params.tId)

const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)
const { fetchContactSpaces, fetchDivisionContactSpaces, toggleVisibility, toggleDivisionVisibility } = useTournamentContact()
const { getDivisions } = useTournamentApi()
const { showSuccess, showError } = useNotification()
const { markIfDenied, notFound } = useScopeNotFound()

/** アクティブスコープ: 'tournament'=大会全体 / divisionId（TournamentContactPanel と共有） */
const activeScope = ref<'tournament' | number>('tournament')

/** 大会レベルの連絡スペース */
const tournamentSpaces = ref<TournamentContactSpace[]>([])
/** ディビジョン別の連絡スペース（divisionId → spaces） */
const divisionSpacesMap = ref<Record<number, TournamentContactSpace[]>>({})
/** ディビジョン一覧 */
const divisions = ref<TournamentDivision[]>([])

const loading = ref(true)
const spacesLoading = ref(false)

// ===== アクティブなスペース取得 =====

const currentSpaces = computed<TournamentContactSpace[]>(() => {
  if (activeScope.value === 'tournament') return tournamentSpaces.value
  return divisionSpacesMap.value[activeScope.value as number] ?? []
})

// ===== データ取得 =====

async function loadTournamentSpaces() {
  spacesLoading.value = true
  try {
    tournamentSpaces.value = await fetchContactSpaces(tId)
  }
  catch (e) {
    // 非所属・権限なし・不可視大会（403/404）は共通の「見つかりません」へ（CMP-261125-1108）
    if (markIfDenied(e)) return
    showError(t('tournament.communication.load_failed'))
  }
  finally {
    spacesLoading.value = false
  }
}

async function loadDivisionSpaces(divisionId: number) {
  if (divisionSpacesMap.value[divisionId] !== undefined) return
  spacesLoading.value = true
  try {
    divisionSpacesMap.value[divisionId] = await fetchDivisionContactSpaces(tId, divisionId)
  }
  catch {
    showError(t('tournament.communication.load_failed'))
  }
  finally {
    spacesLoading.value = false
  }
}

// スコープ切替時の部門スペース取得（スレッド・作成フォームのリセットとチャットの取得は TournamentContactPanel が行う）
watch(activeScope, async (scope) => {
  if (scope !== 'tournament') {
    await loadDivisionSpaces(scope)
  }
})

// ===== 公開設定トグル =====

async function onToggleVisibility(space: TournamentContactSpace, newValue: boolean) {
  try {
    if (activeScope.value === 'tournament') {
      await toggleVisibility(tId, space.id, newValue)
      // ローカル状態を更新
      const idx = tournamentSpaces.value.findIndex(s => s.id === space.id)
      if (idx !== -1) {
        const existing = tournamentSpaces.value[idx]!
        tournamentSpaces.value[idx] = { ...existing, id: existing.id, isPublic: newValue }
      }
    }
    else {
      const divId = activeScope.value as number
      await toggleDivisionVisibility(tId, divId, space.id, newValue)
      const spaces = divisionSpacesMap.value[divId] ?? []
      const idx = spaces.findIndex(s => s.id === space.id)
      if (idx !== -1) {
        divisionSpacesMap.value[divId] = spaces.map((s, i) => i === idx ? { ...s, isPublic: newValue } : s)
      }
    }
    showSuccess(t('tournament.communication.visibility_saved'))
  }
  catch {
    showError(t('tournament.communication.visibility_failed'))
  }
}

onMounted(async () => {
  try {
    // 権限・連絡スペース・ディビジョン一覧は失敗を独立に扱う。
    // Promise.all でまとめると「どれか1つでも失敗＝全部失敗」に粒度が粗くなり、
    // 取得できているものまで捨てたり、無関係な失敗文言を出したりする（Issue #2770）。
    // なお loadTournamentSpaces は自前で失敗を通知するため、ここでは結果を見ない。
    const [permResult, , divResult] = await Promise.allSettled([
      loadPermissions(),
      loadTournamentSpaces(),
      // 取得失敗を空配列に偽装せず、失敗として表面化させる
      getDivisions(orgSlug, tId),
    ])

    // loadPermissions は失敗を throw ではなく戻り値（{ ok: false }）で返す契約のため、
    // allSettled の rejected だけを見ていると取得失敗を取りこぼし、
    // 通知が出ないまま管理操作だけが無言で無効化される。両方を失敗として扱う。
    // 連絡スペース・ディビジョン一覧が 403/404 なら共通の「見つかりません」へ置き換わるため、他のトーストは出さない
    if (divResult.status === 'rejected') markIfDenied(divResult.reason)
    if (notFound.value) return

    const permissionsFailed = permResult.status === 'rejected' || !permResult.value.ok
    if (permissionsFailed) {
      showError(t('tournament.communication.permissions_load_failed'))
    }

    if (divResult.status === 'fulfilled') {
      divisions.value = divResult.value.data
    }
    else {
      showError(t('tournament.communication.divisions_load_failed'))
    }
  }
  finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader :title="$t('tournament.communication.title')" :back-to="`/organizations/${orgSlug}/tournaments/${tId}`" />
    </div>

    <PageLoading v-if="loading" size="40px" />

    <!-- 作成ボタンは出す。投稿権（主催ADMIN・参加チーム代表）の最終判定はサーバー（403 TOUR_031） -->
    <TournamentContactPanel
      v-else
      v-model:scope="activeScope"
      :divisions="divisions"
      :spaces="currentSpaces"
      :spaces-loading="spacesLoading"
      :can-manage="isAdminOrDeputy"
      :can-create="true"
      :chat-organization-id="orgSlug"
    >
      <template #scope-tools>
        <!-- 公開設定（主催者ADMINのみ） -->
        <div v-if="isAdminOrDeputy && currentSpaces.length > 0" class="mb-4 flex flex-wrap gap-4">
          <div
            v-for="space in currentSpaces"
            :key="space.id"
            class="flex items-center gap-2 rounded-lg border border-surface-200 bg-surface-50 px-3 py-2 text-sm"
          >
            <span class="text-surface-600">{{ $t('tournament.communication.visibility_toggle') }}:</span>
            <ToggleSwitch
              :model-value="space.isPublic"
              @update:model-value="(v: boolean) => onToggleVisibility(space, v)"
            />
            <span class="text-xs" :class="space.isPublic ? 'text-green-600' : 'text-surface-400'">
              {{ space.isPublic ? $t('tournament.communication.public') : $t('tournament.communication.private') }}
            </span>
          </div>
        </div>
      </template>
    </TournamentContactPanel>
  </div>
</template>
