<script setup lang="ts">
import type { ConfirmableTarget } from '~/types/confirmable'
import type { ChildrenResponse } from '~/types/organization'

interface TargetOption {
  target: ConfirmableTarget
  name: string
  depth: number
}

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  /** 組織スコープの slug。配下の取得 API は {slug} でしか受けないため数値IDでは要求できない */
  scopeSlug?: string
  modelValue: ConfirmableTarget[]
}>()
const emit = defineEmits<{
  'update:modelValue': [value: ConfirmableTarget[]]
  /** 候補として解決できた対象の名前（宛先グループ一覧の表示名解決用） */
  resolved: [value: { target: ConfirmableTarget; name: string }]
}>()
const api = useApi()
const { getTeamsInOrg } = useOrganizationApi()
const { getTeam } = useTeamApi()
const uid = useId()
const { handleApiError } = useErrorHandler()
const { t } = useI18n()
const options = ref<TargetOption[]>([])
const cursors = ref<Record<string, string | null>>({})
const hasMore = ref<Record<string, boolean>>({})
const opened = ref<Record<string, boolean>>({})
// 組織ID → slug（配下取得 API は slug で受ける）
const orgSlugs = ref<Record<string, string>>({})
const loading = ref(false)
const keyword = ref('')
const currentPage = ref(0)
const pageSize = 20

const currentScopeOption = computed<TargetOption | null>(() => {
  const id = Number(props.scopeId)
  if (!Number.isSafeInteger(id) || id <= 0) return null
  return {
    target: { type: props.scopeType, id },
    name: t('confirmable.current_scope'),
    depth: 0,
  }
})
const availableOptions = computed(() => {
  const current = currentScopeOption.value
  if (!current) return options.value
  return [current, ...options.value.filter(option => key(option.target) !== key(current.target))]
})
const filtered = computed(() => availableOptions.value.filter(option => option.name.toLocaleLowerCase().includes(keyword.value.trim().toLocaleLowerCase())))
const pageCount = computed(() => Math.ceil(filtered.value.length / pageSize))
const visible = computed(() => filtered.value.slice(currentPage.value * pageSize, (currentPage.value + 1) * pageSize))

function key(target: ConfirmableTarget) { return `${target.type}:${target.id}` }
function isSelected(target: ConfirmableTarget) { return props.modelValue.some(item => key(item) === key(target)) }
function toggle(target: ConfirmableTarget) {
  emit('update:modelValue', isSelected(target)
    ? props.modelValue.filter(item => key(item) !== key(target))
    : [...props.modelValue, target])
}
function addOption(target: ConfirmableTarget, name: string, depth: number) {
  emit('resolved', { target, name })
  if (!options.value.some(option => key(option.target) === key(target))) options.value.push({ target, name, depth })
}
async function loadOrganization(orgId: number, depth: number) {
  const orgKey = String(orgId)
  const orgSlug = orgSlugs.value[orgKey]
  if (!orgSlug) return
  loading.value = true
  try {
    const query = new URLSearchParams({ size: '50' })
    if (cursors.value[orgKey]) query.set('cursor', cursors.value[orgKey]!)
    const children = await api<ChildrenResponse>(`/api/v1/organizations/${orgSlug}/children?${query}`)
    for (const child of children.data ?? []) {
      if (child.slug) orgSlugs.value[String(child.id)] = child.slug
      addOption({ type: 'ORGANIZATION', id: child.id }, child.name, depth + 1)
    }
    cursors.value[orgKey] = children.meta?.nextCursor ?? null
    hasMore.value[orgKey] = children.meta?.hasNext ?? false
    if (!opened.value[orgKey]) {
      const teams = await getTeamsInOrg(orgSlug)
      // 一覧の id はスラッグ。確認通知の対象は数値IDが必要なので、チームごとに numericId を引く
      const resolvedTeams = await Promise.allSettled(teams.data.map(async (team) => {
        const detail = await getTeam(team.slug)
        const numericId = Number(detail.data.numericId)
        if (!Number.isSafeInteger(numericId) || numericId <= 0) throw new Error('team_numeric_id_missing')
        return { numericId, name: team.name }
      }))
      let allResolved = true
      for (const result of resolvedTeams) {
        if (result.status === 'fulfilled') addOption({ type: 'TEAM', id: result.value.numericId }, result.value.name, depth + 1)
        else { allResolved = false; handleApiError(result.reason, 'confirmable target team') }
      }
      // 一部でも解決に失敗したら展開済みにしない（展開ボタンから再取得できる状態を残す）
      opened.value[orgKey] = allResolved
    }
  } catch (error) { handleApiError(error, 'confirmable target options') } finally { loading.value = false }
}
function expand(option: TargetOption) { return loadOrganization(option.target.id, option.depth) }
function loadMore(orgId: number) {
  const parent = availableOptions.value.find(option => option.target.type === 'ORGANIZATION' && option.target.id === orgId)
  if (parent) return loadOrganization(orgId, parent.depth)
}
watch(keyword, () => { currentPage.value = 0 })
watch(() => [props.scopeType, props.scopeId, props.scopeSlug] as const, async () => {
  options.value = []; cursors.value = {}; hasMore.value = {}; opened.value = {}; orgSlugs.value = {}; currentPage.value = 0
  const id = Number(props.scopeId)
  if (!Number.isSafeInteger(id) || id <= 0) return
  emit('resolved', { target: { type: props.scopeType, id }, name: t('confirmable.current_scope') })
  if (props.scopeType === 'ORGANIZATION' && props.scopeSlug) {
    orgSlugs.value[String(id)] = props.scopeSlug
    await loadOrganization(id, 0)
  }
}, { immediate: true })
</script>

<template>
  <div class="flex flex-col gap-2" data-testid="confirmable-target-picker">
    <InputText v-model="keyword" :placeholder="$t('confirmable.search_targets')" />
    <div class="max-h-64 overflow-y-auto rounded border border-surface-200 p-2">
      <div v-for="option in visible" :key="key(option.target)" class="flex items-center gap-2 py-1" :style="{ paddingLeft: `${Math.min(option.depth, 8) * 12}px` }">
        <Checkbox :model-value="isSelected(option.target)" binary :input-id="`${uid}-${key(option.target)}`" @update:model-value="toggle(option.target)" />
        <label :for="`${uid}-${key(option.target)}`" class="min-w-0 flex-1 truncate">{{ option.name }} ({{ $t(`confirmable.target_type.${option.target.type}`) }})</label>
        <Button v-if="option.target.type === 'ORGANIZATION' && orgSlugs[String(option.target.id)] && !opened[String(option.target.id)]" icon="pi pi-angle-down" text size="small" :aria-label="$t('confirmable.open_children')" @click="expand(option)" />
        <Button v-if="option.target.type === 'ORGANIZATION' && hasMore[String(option.target.id)]" icon="pi pi-plus" text size="small" :aria-label="$t('confirmable.load_more_targets')" @click="loadMore(option.target.id)" />
      </div>
      <PageLoading v-if="loading" size="24px" />
      <p v-if="!loading && visible.length === 0" class="text-sm text-surface-500">{{ $t('confirmable.no_targets') }}</p>
    </div>
    <div v-if="pageCount > 1" class="flex items-center gap-2">
      <Button icon="pi pi-chevron-left" text size="small" :disabled="currentPage === 0" :aria-label="$t('confirmable.previous_targets')" @click="currentPage--" />
      <span class="text-sm">{{ currentPage + 1 }} / {{ pageCount }}</span>
      <Button icon="pi pi-chevron-right" text size="small" :disabled="currentPage + 1 >= pageCount" :aria-label="$t('confirmable.next_targets')" @click="currentPage++" />
    </div>
    <p v-if="modelValue.length" class="text-sm text-surface-500">{{ $t('confirmable.selected_targets', { count: modelValue.length }) }}</p>
  </div>
</template>
