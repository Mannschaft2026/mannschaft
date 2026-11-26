<script setup lang="ts">
import dayjs from 'dayjs'
import type { components } from '~/types/generated'
import type { TournamentResponse, TournamentSport } from '~/types/tournament'
import { TOURNAMENT_SPORTS } from '~/types/tournament'
import {
  TOURNAMENT_VISIBILITY_LEVELS,
  type TournamentVisibility,
} from '~/utils/tournamentStandings'
import { extractApiFieldErrors } from '~/utils/apiFieldErrors'
import { parseApiError } from '~/utils/apiError'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = String(route.params.slug)

const { t } = useI18n()
const notification = useNotification()
const { markIfDenied } = useScopeNotFound()
const { getTournaments, createTournament } = useTournamentApi()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)
const { userTimezone } = useDatetime()

/** 競技 8 種のセレクタ選択肢（生成型 TournamentSport から導出・ラベルは i18n）。 */
const sportOptions = computed(() =>
  TOURNAMENT_SPORTS.map((sport) => ({
    value: sport,
    label: t(`tournament.sport.options.${sport}`),
  })),
)

/** 可視性 6 レベルのセレクタ選択肢（ラベル＋説明は i18n）。 */
const visibilityOptions = computed(() =>
  TOURNAMENT_VISIBILITY_LEVELS.map((level) => ({
    value: level,
    label: t(`tournament.visibility.levels.${level}.label`),
    description: t(`tournament.visibility.levels.${level}.description`),
  })),
)

const tournaments = ref<TournamentResponse[]>([])
const loading = ref(false)

/** 作成ボディの format 許容値（BE CreateTournamentRequest.format）。 */
const FORMAT_VALUES = ['LEAGUE', 'KNOCKOUT', 'GROUP_KNOCKOUT']
const formatOptions = computed(() => FORMAT_VALUES.map((value) => ({ value, label: t(`tournament.formats.${value}`) })))
/** 欄ごとにエラーを表示できる BE のフィールド名。これ以外の field は通知に出す。 */
const FORM_FIELDS = ['name', 'season', 'format', 'sport', 'description', 'visibility', 'winPoints', 'drawPoints', 'lossPoints']

const showCreateDialog = ref(false)
const saving = ref(false)
/** BE の 400 fieldErrors（field → message）。作成リクエストのキー名と一致する欄に表示する。 */
const fieldErrors = ref<Record<string, string>>({})
const form = ref({
  name: '',
  sport: 'SOCCER' as TournamentSport,
  format: 'LEAGUE' as 'LEAGUE' | 'KNOCKOUT' | 'GROUP_KNOCKOUT',
  seasonYear: dayjs().tz(userTimezone.value).year() as number | null,
  visibility: 'PUBLIC' as TournamentVisibility,
  description: '',
  winPoints: 3,
  drawPoints: 1,
  lossPoints: 0,
})

async function load() {
  loading.value = true
  try {
    const res = await getTournaments(orgId)
    tournaments.value = res.data
  } catch (e) {
    // 非所属・権限なし（403/404）は共通の「見つかりません」へ。トーストは出さない（CMP-261125-1108）
    if (markIfDenied(e)) return
    notification.error(t('tournament.list.loadError'))
  } finally {
    loading.value = false
  }
}

function getStatusClass(s: string): string {
  switch (s) {
    case 'DRAFT':
      return 'bg-surface-100 text-surface-600'
    case 'OPEN':
      return 'bg-green-100 text-green-700'
    case 'IN_PROGRESS':
      return 'bg-blue-100 text-blue-700'
    case 'COMPLETED':
      return 'bg-purple-100 text-purple-700'
    default:
      return 'bg-surface-100'
  }
}

function getFormatLabel(f: string): string {
  return FORMAT_VALUES.includes(f) ? t(`tournament.formats.${f}`) : f
}

function openCreateDialog() {
  form.value = {
    name: '',
    sport: 'SOCCER',
    format: 'LEAGUE',
    seasonYear: dayjs().tz(userTimezone.value).year(),
    visibility: 'PUBLIC',
    description: '',
    winPoints: 3,
    drawPoints: 1,
    lossPoints: 0,
  }
  fieldErrors.value = {}
  showCreateDialog.value = true
}

async function handleCreate() {
  const errs: Record<string, string> = {}
  if (!form.value.name.trim()) errs.name = t('tournament.create.nameRequired')
  if (form.value.seasonYear == null) errs.season = t('tournament.create.seasonRequired')
  fieldErrors.value = errs
  if (Object.keys(errs).length > 0 || form.value.seasonYear == null) return
  saving.value = true
  // BE の CreateTournamentRequest（生成型）と一致するボディのみ送る
  const body: components['schemas']['CreateTournamentRequest'] = {
    name: form.value.name.trim(),
    sport: form.value.sport,
    format: form.value.format,
    season: String(form.value.seasonYear),
    visibility: form.value.visibility,
    description: form.value.description || undefined,
    winPoints: form.value.winPoints,
    drawPoints: form.value.drawPoints,
    lossPoints: form.value.lossPoints,
  }
  try {
    await createTournament(orgId, body)
    notification.success(t('tournament.create.success'))
    showCreateDialog.value = false
    await load()
  } catch (e) {
    const errors = extractApiFieldErrors(e)
    fieldErrors.value = Object.fromEntries(errors.map((fe) => [fe.field, fe.message]))
    let detail: string | undefined
    if (errors.length > 0) {
      // 欄に出せるものは欄に出す。出せない field だけ通知に載せる。
      const unknown = errors.filter((fe) => !FORM_FIELDS.includes(fe.field))
      detail = unknown.length > 0
        ? unknown.map((fe) => `${fe.field}: ${fe.message}`).join(' / ')
        : t('tournament.create.createFailedHint')
    } else {
      detail = parseApiError(e).message
    }
    notification.error(t('tournament.create.createFailed'), detail)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadPermissions()
  load()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="$t('tournament.list.title')" />
      <Button :label="$t('tournament.create.openButton')" icon="pi pi-plus" size="small" data-testid="create-open" @click="openCreateDialog" />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="grid gap-4 sm:grid-cols-2">
      <NuxtLink
        v-for="tour in tournaments"
        :key="tour.id"
        :to="`/organizations/${orgId}/tournaments/${tour.id}`"
        class="block rounded-xl border border-surface-300 bg-surface-0 p-4 transition hover:border-primary-400 hover:shadow-sm"
      >
        <div class="mb-2 flex items-center gap-2">
          <span :class="getStatusClass(tour.structure?.status ?? '')" class="rounded px-2 py-0.5 text-xs font-medium">{{
            tour.structure?.status
          }}</span>
          <span class="rounded bg-surface-100 px-1.5 py-0.5 text-xs">{{
            getFormatLabel(tour.content?.format ?? '')
          }}</span>
        </div>
        <h3 class="text-sm font-semibold">{{ tour.content?.name }}</h3>
        <div class="mt-2 flex items-center gap-3 text-xs text-surface-400">
          <span v-if="tour.content?.season">{{ tour.content.season }}</span>
          <span>{{
            $t('tournament.list.scoreSummary', {
              win: tour.scoring?.winPoints,
              draw: tour.scoring?.drawPoints,
              loss: tour.scoring?.lossPoints,
            })
          }}</span>
        </div>
      </NuxtLink>
      <DashboardEmptyState v-if="tournaments.length === 0" class="col-span-full" icon="pi pi-trophy" :message="$t('tournament.list.empty')" />
    </div>

    <Dialog v-model:visible="showCreateDialog" modal :header="$t('tournament.create.title')" :style="{ width: '30rem', maxWidth: '95vw' }">
      <div class="flex flex-col gap-4 py-2">
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ $t('tournament.create.fields.name') }} <span class="text-red-500">*</span></label>
          <InputText v-model="form.name" :placeholder="$t('tournament.create.namePlaceholder')" class="w-full" :invalid="!!fieldErrors.name" />
          <small v-if="fieldErrors.name" class="text-xs text-red-600" data-testid="create-error-name">{{ fieldErrors.name }}</small>
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ $t('tournament.create.fields.season') }} <span class="text-red-500">*</span></label>
          <InputNumber
            v-model="form.seasonYear"
            :min="2000"
            :max="2100"
            :use-grouping="false"
            :invalid="!!fieldErrors.season"
            class="w-full"
            input-class="w-full"
            data-testid="create-season"
          />
          <small v-if="fieldErrors.season" class="text-xs text-red-600" data-testid="create-error-season">{{ fieldErrors.season }}</small>
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ $t('tournament.sport.label') }}</label>
          <Select
            v-model="form.sport"
            :options="sportOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            data-testid="create-sport-select"
          />
          <p class="text-xs text-surface-500">{{ $t('tournament.sport.help') }}</p>
          <small v-if="fieldErrors.sport" class="text-xs text-red-600" data-testid="create-error-sport">{{ fieldErrors.sport }}</small>
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ $t('tournament.create.fields.format') }} <span class="text-red-500">*</span></label>
          <Select
            v-model="form.format"
            :options="formatOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
          <small v-if="fieldErrors.format" class="text-xs text-red-600" data-testid="create-error-format">{{ fieldErrors.format }}</small>
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ $t('tournament.create.fields.description') }}</label>
          <Textarea v-model="form.description" rows="2" class="w-full" />
          <small v-if="fieldErrors.description" class="text-xs text-red-600" data-testid="create-error-description">{{ fieldErrors.description }}</small>
        </div>
        <div v-if="isAdminOrDeputy" class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ $t('tournament.visibility.title') }}</label>
          <Select
            v-model="form.visibility"
            :options="visibilityOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            data-testid="create-visibility-select"
          >
            <template #option="{ option }">
              <div class="flex flex-col">
                <span class="font-medium">{{ option.label }}</span>
                <span class="text-xs text-surface-500">{{ option.description }}</span>
              </div>
            </template>
          </Select>
          <p class="text-xs text-surface-500">
            {{ $t(`tournament.visibility.levels.${form.visibility}.description`) }}
          </p>
          <small v-if="fieldErrors.visibility" class="text-xs text-red-600" data-testid="create-error-visibility">{{ fieldErrors.visibility }}</small>
        </div>
        <div class="grid grid-cols-1 gap-3 sm:grid-cols-3 [&>*]:min-w-0">
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">{{ $t('tournament.create.fields.winPoints') }}</label>
            <InputNumber v-model="form.winPoints" :min="0" :max="255" :invalid="!!fieldErrors.winPoints" class="w-full" input-class="w-full min-w-0" data-testid="create-win-points" />
            <small v-if="fieldErrors.winPoints" class="text-xs text-red-600" :data-testid="'create-error-winPoints'">{{ fieldErrors.winPoints }}</small>
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">{{ $t('tournament.create.fields.drawPoints') }}</label>
            <InputNumber v-model="form.drawPoints" :min="0" :max="255" :invalid="!!fieldErrors.drawPoints" class="w-full" input-class="w-full min-w-0" data-testid="create-draw-points" />
            <small v-if="fieldErrors.drawPoints" class="text-xs text-red-600" :data-testid="'create-error-drawPoints'">{{ fieldErrors.drawPoints }}</small>
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">{{ $t('tournament.create.fields.lossPoints') }}</label>
            <InputNumber v-model="form.lossPoints" :min="0" :max="255" :invalid="!!fieldErrors.lossPoints" class="w-full" input-class="w-full min-w-0" data-testid="create-loss-points" />
            <small v-if="fieldErrors.lossPoints" class="text-xs text-red-600" :data-testid="'create-error-lossPoints'">{{ fieldErrors.lossPoints }}</small>
          </div>
        </div>
      </div>
      <template #footer>
        <Button :label="$t('tournament.create.cancel')" text :disabled="saving" @click="showCreateDialog = false" />
        <Button :label="$t('tournament.create.submit')" icon="pi pi-check" data-testid="create-submit" :loading="saving" @click="handleCreate" />
      </template>
    </Dialog>
  </div>
</template>
