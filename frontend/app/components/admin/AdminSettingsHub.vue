<script setup lang="ts">
import { matchGateKey } from '~/constants/featureGates'
import { getLoadErrorHttpStatus } from '~/utils/loadError'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  slug: string
}>()

const { t } = useI18n()
const slug = computed(() => props.slug)
const { roleName, isScopeAdmin: isAdmin, loadPermissions } = useRoleAccess(props.scopeType, slug)
const { getAdminActionRequired } = useScopeTabApi()
const { getTeam } = useTeamApi()
const { getOrganization } = useOrganizationApi()
const flags = useFeatureFlagStore()
const loading = ref(true)
const loadError = shallowRef<unknown>(null)
const confirmedScopeManager = ref(false)
// /scopes/{TYPE}/{id} の id には必ず数値 ID を渡す（slug を渡すと ScopeSlugIdConverter が TEAM として解決し別スコープを誤照会しうる）
const scopeNumericId = ref<string | null>(null)
// 無断欠席・ペナルティ区画だけの取得失敗（ハブ全体や既存リンクは巻き込まない）
const recruitmentLinkError = shallowRef<unknown>(null)
const RECRUITMENT_GATE = 'FEATURE_RECRUITMENT_ENABLED'
const recruitmentScopeType = computed(() => (props.scopeType === 'team' ? 'TEAM' : 'ORGANIZATION'))
const isSystemAdmin = computed(() => roleName.value === 'SYSTEM_ADMIN')
const scopeManagerAccess = computed(
  () => isAdmin.value || roleName.value === 'DEPUTY_ADMIN' || confirmedScopeManager.value,
)
const hubAccess = computed(() => scopeManagerAccess.value || isSystemAdmin.value)
const base = computed(
  () => `/${props.scopeType === 'team' ? 'teams' : 'organizations'}/${props.slug}`,
)

interface SettingsLink {
  /** `${base}/settings/` 配下の相対パス（absolutePath と排他） */
  path?: string
  /** スコープ設定配下ではない絶対パス（フィーチャーゲートは gate で明示する。BE 側 checkAdminOrAbove が権限の最終防衛） */
  absolutePath?: string
  /** absolutePath のリンクを絞る公開フラグキー */
  gate?: string
  title: string
  adminOnly?: boolean
  sysAllowed?: boolean
  readOnly?: boolean
}

const links = computed(() => {
  const settings: SettingsLink[] = [
    { path: 'faq-settings', title: 'faq.settings.title', adminOnly: true, sysAllowed: true },
    { path: 'public-settings', title: 'public.publicSettings.title', sysAllowed: true },
    {
      path: 'todo-status-labels',
      title: 'todo.statusLabel.management.title',
      adminOnly: props.scopeType === 'organization',
      readOnly: props.scopeType === 'team' && !isAdmin.value,
    },
  ]
  if (props.scopeType === 'team') {
    settings.unshift({ path: 'shift', title: 'teamShiftSettings.title' })
  } else {
    settings.push({
      path: 'notification-credits',
      title: 'notificationCredit.title',
      adminOnly: true,
    })
  }
  // 無断欠席・ペナルティ管理は BE の checkAdminOrAbove（ADMIN/DEPUTY_ADMIN）に合わせ、システム管理者の例外では出さない
  if (scopeManagerAccess.value && scopeNumericId.value) {
    settings.push(
      {
        absolutePath: `/scopes/${recruitmentScopeType.value}/${scopeNumericId.value}/no-shows`,
        title: 'recruitment.noShow.adminPageTitle',
        gate: RECRUITMENT_GATE,
      },
      {
        absolutePath: `/scopes/${recruitmentScopeType.value}/${scopeNumericId.value}/penalty-settings`,
        title: 'recruitment.penalty.pageTitle',
        gate: RECRUITMENT_GATE,
      },
    )
  }
  return settings.filter((link) => {
    if (link.absolutePath) {
      return !link.gate || (flags.publicLoaded && flags.isEnabled(link.gate))
    }
    const systemException = isSystemAdmin.value && link.sysAllowed
    if (!scopeManagerAccess.value && !systemException) return false
    if (link.adminOnly && !isAdmin.value && !systemException) return false
    const gate = matchGateKey(`${base.value}/settings/${link.path ?? ''}`)
    return gate === null || (flags.publicLoaded && flags.isEnabled(gate))
  })
})

/** 詳細 API の numericId を解決する（slug は URL 用で、/scopes/ 配下へは渡せない） */
async function resolveScopeNumericId(requestedSlug: string): Promise<string> {
  const result =
    props.scopeType === 'team'
      ? await getTeam(requestedSlug)
      : await getOrganization(requestedSlug)
  const numericId = result.data.numericId
  if (!Number.isInteger(numericId) || Number(numericId) <= 0) {
    throw new Error(`Scope numeric ID is unavailable: ${requestedSlug}`)
  }
  return String(numericId)
}

/** 募集機能が有効なときだけ numericId を取得し、失敗はこの区画のエラーに留める */
async function loadRecruitmentLinks(requestedSlug: string, isCurrent: () => boolean) {
  recruitmentLinkError.value = null
  scopeNumericId.value = null
  if (!scopeManagerAccess.value || !flags.publicLoaded || !flags.isEnabled(RECRUITMENT_GATE)) return
  try {
    const numericId = await resolveScopeNumericId(requestedSlug)
    if (isCurrent()) scopeNumericId.value = numericId
  } catch (error) {
    if (isCurrent()) recruitmentLinkError.value = error
  }
}

let loadSequence = 0
async function loadPage() {
  const sequence = ++loadSequence
  const requestedSlug = props.slug
  const isCurrent = () => sequence === loadSequence && requestedSlug === props.slug
  loading.value = true
  loadError.value = null
  confirmedScopeManager.value = false
  scopeNumericId.value = null
  recruitmentLinkError.value = null
  try {
    const result = await loadPermissions()
    if (!isCurrent()) return
    if (!result.ok) throw result.error
    if (roleName.value === 'SYSTEM_ADMIN' && !isAdmin.value) {
      // 表示用ロールから兼任DEPUTYを推測せず、既存API入口の正準スコープ認可で確認する。
      try {
        await getAdminActionRequired(
          props.scopeType === 'team' ? 'TEAM' : 'ORGANIZATION',
          requestedSlug,
          0,
        )
        if (!isCurrent()) return
        confirmedScopeManager.value = true
      } catch (error) {
        if (!isCurrent()) return
        if (getLoadErrorHttpStatus(error) !== 403) throw error
      }
    }
    if (scopeManagerAccess.value && !flags.publicLoaded) {
      await flags.loadPublicFlags()
    }
    await loadRecruitmentLinks(requestedSlug, isCurrent)
  } catch (error) {
    if (isCurrent()) loadError.value = error
  } finally {
    if (isCurrent()) loading.value = false
  }
}

onMounted(loadPage)
watch(slug, loadPage)
</script>

<template>
  <div class="space-y-6 p-4 md:p-6">
    <PageHeader :title="t('adminConsole.cards.settings.title')" />
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="loadError" :error="loadError" @retry="loadPage" />
    <DashboardEmptyState
      v-else-if="!hubAccess"
      icon="pi pi-lock"
      :message="t('adminConsole.middleware.accessDeniedBody')"
    />
    <div v-else class="grid gap-4 sm:grid-cols-2">
      <SectionCard v-for="link in links" :key="link.absolutePath ?? link.path">
        <NuxtLink
          :to="link.absolutePath ?? `${base}/settings/${link.path}`"
          class="flex min-h-11 min-w-0 items-center justify-between gap-3 text-primary-600 hover:underline dark:text-primary-400"
        >
          <span>{{ t(link.title) }}</span>
          <i class="pi pi-arrow-right shrink-0" aria-hidden="true" />
        </NuxtLink>
        <p v-if="link.readOnly" class="mt-2 text-sm text-surface-500">
          {{ t('adminConsole.settingsHub.readOnly') }}
        </p>
      </SectionCard>
      <SectionCard v-if="recruitmentLinkError">
        <DashboardErrorState :error="recruitmentLinkError" @retry="loadPage" />
      </SectionCard>
    </div>
  </div>
</template>
