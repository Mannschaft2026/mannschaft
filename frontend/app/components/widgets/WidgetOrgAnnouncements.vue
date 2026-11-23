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

const orgStore = useOrganizationStore()
const api = useApi()
const { relativeTime } = useRelativeTime()
const { captureQuiet } = useErrorReport()

interface OrgAnnouncement extends AnnouncementFeedItem {
  scopeName: string
  scopeSlug: string
}

/** 表示件数の上限（従来どおり） */
const MAX_ITEMS = 20
/** チームスコープ分が混ざるため BE 上限(50)で取り、組織分だけ残す */
const FETCH_LIMIT = 50

const items = ref<OrgAnnouncement[]>([])
const loading = ref(false)

const priorityConfig = computed<Record<string, { label: string; class: string }>>(() => ({
  URGENT: { label: t('dashboard.widgets.priority.critical'), class: 'bg-red-100 text-red-700' },
  IMPORTANT: { label: t('dashboard.widgets.priority.important'), class: 'bg-orange-100 text-orange-700' },
}))

async function load() {
  if (orgStore.myOrganizations.length === 0) return
  loading.value = true
  try {
    // 個人横断お知らせ（announcement_feeds）。既読も含めて表示する
    const res = await api<{ data?: AnnouncementFeedDto[]; meta?: AnnouncementFeedMeta }>(
      `/api/v1/announcements/me?limit=${FETCH_LIMIT}&include_read=true`,
    )
    const orgById = new Map(orgStore.myOrganizations.map((org) => [Number(org.id), org]))
    const result: OrgAnnouncement[] = []
    for (const dto of res.data ?? []) {
      if (dto.scopeType !== 'ORGANIZATION') continue
      const org = orgById.get(Number(dto.scopeId))
      if (!org) continue
      result.push({
        ...toAnnouncementItem(dto),
        scopeName: dto.scopeName || org.nickname1 || org.name,
        scopeSlug: org.slug,
      })
    }
    items.value = result
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
      .slice(0, MAX_ITEMS)
  } catch (error) {
    captureQuiet(error, { context: 'WidgetOrgAnnouncements: 個人横断お知らせ取得' })
    items.value = []
  } finally {
    loading.value = false
  }
}

/** 所属組織が 1 つならその組織トップ、複数なら組織一覧へ（組織のお知らせ一覧ページは無い） */
const viewAllTo = computed(() =>
  orgStore.myOrganizations.length === 1 && orgStore.myOrganizations[0]
    ? `/organizations/${orgStore.myOrganizations[0].slug}`
    : '/organizations',
)

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    v-if="!props.embedded"
    :title="t('dashboard.widgets.orgAnnouncements.title')"
    icon="pi pi-building"
    :to="viewAllTo"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <div v-if="orgStore.myOrganizations.length === 0">
      <DashboardEmptyState icon="pi pi-building" :message="t('dashboard.widgets.orgAnnouncements.noOrg')" />
    </div>
    <div v-else-if="items.length === 0">
      <DashboardEmptyState icon="pi pi-clipboard" :message="t('dashboard.widgets.orgAnnouncements.empty')" />
    </div>
    <div v-else class="divide-y divide-surface-300 dark:divide-surface-600">
      <NuxtLink
        v-for="item in items"
        :key="`${item.scopeType}-${item.id}`"
        :to="`/organizations/${item.scopeSlug}`"
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
            <Tag :value="item.scopeName" severity="secondary" class="text-xs" />
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
    <div v-else-if="orgStore.myOrganizations.length === 0">
      <DashboardEmptyState icon="pi pi-building" :message="t('dashboard.widgets.orgAnnouncements.noOrg')" />
    </div>
    <div v-else-if="items.length === 0">
      <DashboardEmptyState icon="pi pi-clipboard" :message="t('dashboard.widgets.orgAnnouncements.empty')" />
    </div>
    <div v-else class="divide-y divide-surface-300 dark:divide-surface-600">
      <NuxtLink
        v-for="item in items"
        :key="`${item.scopeType}-${item.id}`"
        :to="`/organizations/${item.scopeSlug}`"
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
            <Tag :value="item.scopeName" severity="secondary" class="text-xs" />
            <span>{{ relativeTime(item.createdAt) }}</span>
          </div>
        </div>
      </NuxtLink>
    </div>
  </template>
</template>
