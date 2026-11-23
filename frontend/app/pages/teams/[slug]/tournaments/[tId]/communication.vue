<script setup lang="ts">
import type {
  ParticipantContactSpace,
  ParticipantContactSpaces,
  TournamentContactPanelDivision,
} from '~/types/tournament'

/**
 * 参加チーム側の大会連絡スペース画面（CMP-261118-0900・設計書 F08.7.1 01_communication.md §10.5）。
 *
 * - 読み込む一覧 API は参加者向け 1 本のみ（GET /api/v1/teams/{teamSlug}/tournaments/{tId}/contact-spaces）。
 *   主催者向け一覧・部門別一覧・組織配下の部門一覧 API は呼ばない（部門名は応答に含まれる）。
 * - 出し分けはスペースごとの accessLevel（POST のときだけ作成・返信・送信を許す）。
 *   管理操作（ピン・削除・ロック・公開設定）は参加者画面では出さない（AC-P36）。
 * - 404（非所属・非参加・大会不在など。理由は区別されない）は「利用できない」状態表示のみ。
 *   それ以外の失敗は通知と再試行導線を出す（空状態に偽装しない。AC-P30）。
 */

definePageMeta({ layout: 'team', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)
const tId = Number(route.params.tId)

const { fetchParticipantContactSpaces } = useTournamentContact()
const { showError } = useNotification()

const contact = ref<ParticipantContactSpaces | null>(null)
const loading = ref(true)
/** 404 による「利用できない」状態 */
const unavailable = ref(false)
/** 404 以外の取得失敗（再試行導線を出す） */
const loadError = ref<unknown>(null)

/** アクティブスコープ: 'tournament'=大会全体 / 部門ID（TournamentContactPanel と共有） */
const activeScope = ref<'tournament' | number>('tournament')

const panelDivisions = computed<TournamentContactPanelDivision[]>(() =>
  (contact.value?.divisions ?? []).map(d => ({ id: d.divisionId, name: d.name })),
)

const currentSpaces = computed<ParticipantContactSpace[]>(() => {
  const spaces = contact.value?.spaces ?? []
  const scope = activeScope.value
  if (scope === 'tournament') return spaces.filter(s => s.scopeType === 'TOURNAMENT')
  return spaces.filter(s => s.scopeType === 'TOURNAMENT_DIVISION' && s.scopeId === scope)
})

function canPost(space: ParticipantContactSpace | null): boolean {
  return space?.accessLevel === 'POST'
}

const canPostBulletin = computed(() => canPost(currentSpaces.value.find(s => s.spaceKind === 'BULLETIN') ?? null))
const canPostChat = computed(() => canPost(currentSpaces.value.find(s => s.spaceKind === 'CHAT') ?? null))
/** 現在のスコープに閲覧のみのスペースがあるか */
const viewOnly = computed(() => currentSpaces.value.some(s => s.accessLevel === 'VIEW'))

async function load() {
  loading.value = true
  unavailable.value = false
  loadError.value = null
  try {
    contact.value = await fetchParticipantContactSpaces(teamSlug, tId)
  }
  catch (error) {
    contact.value = null
    if (getLoadErrorHttpStatus(error) === 404) {
      // 存在秘匿の 404 TOUR_029。状態表示のみで通知は出さない
      unavailable.value = true
    }
    else {
      loadError.value = error
      showError(t('tournament.communication.load_failed'))
    }
  }
  finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader
        :title="$t('tournament.communication.participant.title')"
        :back-to="`/teams/${teamSlug}/tournaments`"
      />
    </div>

    <PageLoading v-if="loading" size="40px" />

    <DashboardErrorState
      v-else-if="unavailable"
      kind="notFoundOrForbidden"
      :title="$t('tournament.communication.participant.unavailable')"
      :message="$t('tournament.communication.participant.unavailable_hint')"
      :show-retry="false"
      testid="participant-contact-unavailable"
    />

    <DashboardErrorState
      v-else-if="loadError !== null || !contact"
      :error="loadError"
      :title="$t('tournament.communication.load_failed')"
      :retry-label="$t('tournament.communication.participant.load_retry')"
      :show-retry="true"
      testid="participant-contact-load-error"
      @retry="load"
    />

    <template v-else>
      <h2 class="mb-3 text-lg font-semibold text-surface-800 dark:text-surface-100">
        {{ contact.tournamentName }}
      </h2>

      <!-- 管理操作は渡さない（AC-P36）。作成・返信・送信は当該スペースが POST のときだけ -->
      <TournamentContactPanel
        v-model:scope="activeScope"
        :divisions="panelDivisions"
        :spaces="currentSpaces"
        :can-create="canPostBulletin"
        :can-reply="canPostBulletin"
        :can-send="canPostChat"
      >
        <template #scope-tools>
          <p
            v-if="viewOnly"
            class="mb-4 flex items-start gap-2 rounded-lg border border-surface-200 bg-surface-50 px-3 py-2 text-sm text-surface-600 dark:border-surface-700 dark:bg-surface-800 dark:text-surface-300"
          >
            <i class="pi pi-eye mt-0.5" aria-hidden="true" />
            <span>{{ $t('tournament.communication.participant.view_only_notice') }}</span>
          </p>
        </template>
      </TournamentContactPanel>
    </template>
  </div>
</template>
