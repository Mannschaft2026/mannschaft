<script setup lang="ts">
import type { PointSummary, UserBadge, RankingEntry } from '~/types/gamification'
import { isScopeNotFound } from '~/utils/loadError'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const gamificationApi = useGamificationApi()
const notification = useNotification()
const { markIfDenied } = useScopeNotFound()
const { formatDate } = useDatetime()

const points = ref<PointSummary | null>(null)
const badges = ref<UserBadge[]>([])
const rankings = ref<RankingEntry[]>([])
const loading = ref(true)
const activeTab = ref('0')
const rankingPeriod = ref<'WEEKLY' | 'MONTHLY' | 'YEARLY'>('MONTHLY')

/**
 * 3 つの取得は認可が同一ではない（CMP-261125-1108）。ポイント・バッジは所属チェックのみだが、
 * ランキングはさらに機能フラグ（FEATURE_GAMIFICATION_ENABLED。無効時は所属者にも 403）を要する。
 * そのため「全取得が 403/404」のときだけ非所属・権限なしとみなして共通の「見つかりません」に寄せ、
 * 一部だけ失敗したときは取得できた分を表示する（失敗はトーストで知らせる）。
 */
async function loadData() {
  loading.value = true
  try {
    const [pts, bdg, rnk] = await Promise.allSettled([
      gamificationApi.getMyPoints(teamSlug.value),
      gamificationApi.getMyBadges(teamSlug.value),
      gamificationApi.getRankings(teamSlug.value, rankingPeriod.value),
    ])
    const failures = [pts, bdg, rnk]
      .filter((r): r is PromiseRejectedResult => r.status === 'rejected')
      .map(r => r.reason as unknown)
    if (failures.length === 3 && failures.every(isScopeNotFound)) {
      // 非所属・権限なし（403/404）は共通の「見つかりません」へ。トーストは出さない
      markIfDenied(failures[0])
      return
    }
    if (pts.status === 'fulfilled') points.value = pts.value
    if (bdg.status === 'fulfilled') badges.value = bdg.value
    if (rnk.status === 'fulfilled') rankings.value = rnk.value
    if (failures.length > 0) notification.error('ゲーミフィケーションデータの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function loadRankings() {
  try {
    rankings.value = await gamificationApi.getRankings(teamSlug.value, rankingPeriod.value)
  } catch {
    notification.error('ランキングの取得に失敗しました')
  }
}

watch(rankingPeriod, loadRankings)
onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <PageHeader title="ポイント・バッジ" class="mb-6" />

    <PageLoading v-if="loading" />

    <template v-else>
      <div v-if="points" class="mb-6 grid gap-4 md:grid-cols-3">
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">累計ポイント</p>
            <p class="text-3xl font-bold text-primary">{{ points.totalPoints.toLocaleString() }}</p>
          </template>
        </Card>
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">今月のポイント</p>
            <p class="text-3xl font-bold text-green-600">
              {{ points.monthlyPoints.toLocaleString() }}
            </p>
          </template>
        </Card>
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">獲得バッジ</p>
            <p class="text-3xl font-bold text-yellow-600">{{ points.badgeCount }}</p>
          </template>
        </Card>
      </div>

      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="0">バッジ</Tab>
          <Tab value="1">ランキング</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="0">
            <DashboardEmptyState
              v-if="badges.length === 0"
              icon="pi pi-star"
              message="まだバッジを獲得していません"
            />
            <div v-else class="grid gap-4 sm:grid-cols-3 lg:grid-cols-4">
              <SectionCard
                v-for="ub in badges"
                :key="ub.id"
                class="flex flex-col items-center"
              >
                <img
                  v-if="ub.badge.iconUrl"
                  :src="ub.badge.iconUrl"
                  alt=""
                  class="mb-2 h-16 w-16"
                >
                <div
                  v-else
                  class="mb-2 flex h-16 w-16 items-center justify-center rounded-full bg-yellow-100 text-2xl"
                >
                  <i class="pi pi-star-fill text-yellow-500" />
                </div>
                <p class="text-sm font-semibold text-center">{{ ub.badge.name }}</p>
                <p class="text-xs text-surface-500 text-center">{{ ub.badge.description }}</p>
                <p class="mt-1 text-xs text-surface-400">
                  {{ formatDate(ub.awardedAt) }}
                </p>
              </SectionCard>
            </div>
          </TabPanel>
          <TabPanel value="1">
            <div class="mb-4 flex justify-end">
              <SelectButton
                v-model="rankingPeriod"
                :options="[
                  { label: '週間', value: 'WEEKLY' },
                  { label: '月間', value: 'MONTHLY' },
                  { label: '年間', value: 'YEARLY' },
                ]"
                option-label="label"
                option-value="value"
              />
            </div>
            <DashboardEmptyState
              v-if="rankings.length === 0"
              icon="pi pi-chart-bar"
              message="ランキングデータがありません"
            />
            <div v-else class="space-y-2">
              <SectionCard
                v-for="entry in rankings"
                :key="entry.userId"
                class="flex items-center gap-4"
              >
                <span
                  class="flex h-8 w-8 items-center justify-center rounded-full text-sm font-bold"
                  :class="
                    entry.rank <= 3
                      ? 'bg-yellow-100 text-yellow-700'
                      : 'bg-surface-100 text-surface-600'
                  "
                >
                  {{ entry.rank }}
                </span>
                <img
                  v-if="entry.avatarUrl"
                  :src="entry.avatarUrl"
                  alt=""
                  class="h-10 w-10 rounded-full object-cover"
                >
                <div
                  v-else
                  class="flex h-10 w-10 items-center justify-center rounded-full bg-primary/10 text-primary"
                >
                  <i class="pi pi-user" />
                </div>
                <div class="flex-1">
                  <p class="font-medium">{{ entry.displayName }}</p>
                </div>
                <p class="text-lg font-bold text-primary">{{ entry.points.toLocaleString() }} pt</p>
              </SectionCard>
            </div>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
