<script setup lang="ts">
import type { AnnouncementFeedDto, AnnouncementFeedItem, AnnouncementFeedMeta } from '~/types/announcement'
import { toAnnouncementItem } from '~/utils/announcementAdapter'

const { t } = useI18n()
const props = withDefaults(
  defineProps<{
    embedded?: boolean
  }>(),
  { embedded: false },
)

const teamStore = useTeamStore()
const api = useApi()
const { relativeTime } = useRelativeTime()
const { captureQuiet } = useErrorReport()

interface TeamAnnouncement extends AnnouncementFeedItem {
  scopeName: string
  scopeSlug: string
}

/** 表示件数の上限（従来どおり） */
const MAX_ITEMS = 20
/** 組織スコープ分が混ざるため BE 上限(50)で取り、チーム分だけ残す */
const FETCH_LIMIT = 50

const items = ref<TeamAnnouncement[]>([])
const loading = ref(false)

const priorityConfig = computed<Record<string, { label: string; class: string }>>(() => ({
  URGENT: { label: t('dashboard.widgets.priority.critical'), class: 'bg-red-100 text-red-700' },
  IMPORTANT: { label: t('dashboard.widgets.priority.important'), class: 'bg-orange-100 text-orange-700' },
}))

async function load() {
  if (teamStore.myTeams.length === 0) return
  loading.value = true
  try {
    // 個人横断お知らせ（announcement_feeds）。既読も含めて表示する
    const res = await api<{ data?: AnnouncementFeedDto[]; meta?: AnnouncementFeedMeta }>(
      `/api/v1/announcements/me?limit=${FETCH_LIMIT}&include_read=true`,
    )
    const teamById = new Map(teamStore.myTeams.map((team) => [Number(team.id), team]))
    const result: TeamAnnouncement[] = []
    for (const dto of res.data ?? []) {
      if (dto.scopeType !== 'TEAM') continue
      const team = teamById.get(Number(dto.scopeId))
      if (!team) continue
      result.push({
        ...toAnnouncementItem(dto),
        scopeName: dto.scopeName || team.nickname1 || team.name,
        scopeSlug: team.slug,
      })
    }
    items.value = result
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
      .slice(0, MAX_ITEMS)
  } catch (error) {
    captureQuiet(error, { context: 'WidgetTeamAnnouncements: 個人横断お知らせ取得' })
    items.value = []
  } finally {
    loading.value = false
  }
}

/** 所属チームが 1 つならそのお知らせ一覧、複数ならチーム一覧へ */
const viewAllTo = computed(() =>
  teamStore.myTeams.length === 1 && teamStore.myTeams[0]
    ? `/teams/${teamStore.myTeams[0].slug}/announcements`
    : '/teams',
)

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    v-if="!props.embedded"
    :title="t('dashboard.widgets.teamAnnouncements.title')"
    icon="pi pi-users"
    :to="viewAllTo"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <div v-if="teamStore.myTeams.length === 0">
      <DashboardEmptyState icon="pi pi-users" :message="t('dashboard.widgets.teamAnnouncements.noTeam')" />
    </div>
    <div v-else-if="items.length === 0">
      <DashboardEmptyState icon="pi pi-clipboard" :message="t('dashboard.widgets.teamAnnouncements.empty')" />
    </div>
    <div v-else class="divide-y divide-surface-300 dark:divide-surface-600">
      <NuxtLink
        v-for="item in items"
        :key="`${item.scopeType}-${item.id}`"
        :to="`/teams/${item.scopeSlug}/announcements`"
        class="flex items-start gap-2 py-2.5 transition-colors hover:bg-surface-50 dark:hover:bg-surface-700/50"
      >
        <i v-if="item.isPinned" class="pi pi-thumbtack mt-0.5 shrink-0 text-xs text-orange-500" />
        <div class="min-w-0 flex-1">
          <div class="mb-0.5 flex flex-wrap items-center gap-1.5">
            <span
              v-if="priorityConfig[item.priority]"
              :class="priorityConfig[item.priority]?.class"
              class="rounded px-1.5 py-0.5 text-xs font-medium"
            >
              {{ priorityConfig[item.priority]?.label }}
            </span>
            <span class="truncate text-sm font-medium text-surface-700 dark:text-surface-200">
              {{ item.title }}
            </span>
          </div>
          <div class="flex items-center gap-2 text-xs text-surface-400">
            <Tag :value="item.scopeName" severity="info" class="text-xs" />
            <span>{{ relativeTime(item.createdAt) }}</span>
          </div>
        </div>
      </NuxtLink>
      <div class="flex justify-end pt-2">
        <NuxtLink :to="viewAllTo" class="text-sm text-primary hover:underline">
          {{ $t('button.view_all') }}
        </NuxtLink>
      </div>
    </div>
  </DashboardWidgetCard>

  <template v-else>
    <PageLoading v-if="loading" />
    <div v-else-if="teamStore.myTeams.length === 0">
      <DashboardEmptyState icon="pi pi-users" :message="t('dashboard.widgets.teamAnnouncements.noTeam')" />
    </div>
    <div v-else-if="items.length === 0">
      <DashboardEmptyState icon="pi pi-clipboard" :message="t('dashboard.widgets.teamAnnouncements.empty')" />
    </div>
    <div v-else class="divide-y divide-surface-300 dark:divide-surface-600">
      <NuxtLink
        v-for="item in items"
        :key="`${item.scopeType}-${item.id}`"
        :to="`/teams/${item.scopeSlug}/announcements`"
        class="flex items-start gap-2 py-2.5 transition-colors hover:bg-surface-50 dark:hover:bg-surface-700/50"
      >
        <i v-if="item.isPinned" class="pi pi-thumbtack mt-0.5 shrink-0 text-xs text-orange-500" />
        <div class="min-w-0 flex-1">
          <div class="mb-0.5 flex flex-wrap items-center gap-1.5">
            <span
              v-if="priorityConfig[item.priority]"
              :class="priorityConfig[item.priority]?.class"
              class="rounded px-1.5 py-0.5 text-xs font-medium"
            >
              {{ priorityConfig[item.priority]?.label }}
            </span>
            <span class="truncate text-sm font-medium text-surface-700 dark:text-surface-200">
              {{ item.title }}
            </span>
          </div>
          <div class="flex items-center gap-2 text-xs text-surface-400">
            <Tag :value="item.scopeName" severity="info" class="text-xs" />
            <span>{{ relativeTime(item.createdAt) }}</span>
          </div>
        </div>
      </NuxtLink>
    </div>
  </template>
</template>
