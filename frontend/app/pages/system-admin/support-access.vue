<script setup lang="ts">
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Dialog from 'primevue/dialog'
import InputNumber from 'primevue/inputnumber'
import Message from 'primevue/message'
import Select from 'primevue/select'
import Tab from 'primevue/tab'
import TabList from 'primevue/tablist'
import Tabs from 'primevue/tabs'
import Tag from 'primevue/tag'
import Textarea from 'primevue/textarea'
import type {
  SupportAccessGrant,
  SupportAccessLog,
  SupportAccessStatus,
  WithGrantId,
} from '~/types/support-access'
import { hasGrantId } from '~/types/support-access'
import { toSupportAccessSession } from '~/stores/useSupportAccessStore'

/** 操作できる（grant ID のある）行。ID の無い行は API の境界で除く。 */
type GrantRow = WithGrantId<SupportAccessGrant>

/**
 * F10.9 サポート閲覧（本人承認制の代理ログイン）— 運営側の画面。
 *
 * 運営は「依頼 → 本人の許可 → 代理閲覧の開始」の順に進める。代理閲覧は見るだけで、許可は許可から24時間で切れる。
 * 依頼の理由は本人にそのまま表示される（プレーンテキスト）。
 */
definePageMeta({ middleware: ['auth', 'system-admin'] })

const { t, te } = useI18n()
const route = useRoute()
const router = useRouter()
const notification = useNotification()
const { formatDateTime } = useDatetime()
const store = useSupportAccessStore()
const supportApi = useSupportAccessApi()

const REASON_MIN = 10
const REASON_MAX = 1000

// ─── 一覧（状態別タブ） ───
type TabValue = 'ALL' | SupportAccessStatus
const TAB_ORDER: TabValue[] = ['ALL', 'PENDING', 'APPROVED', 'DENIED', 'REVOKED', 'EXPIRED', 'CANCELLED']
const activeTab = ref<TabValue>('ALL')
const grants = ref<GrantRow[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const loadFailed = ref(false)
const nextCursor = ref<string | null>(null)
const hasNext = ref(false)

function tabLabel(tab: TabValue): string {
  return tab === 'ALL' ? t('supportAccess.admin.tabAll') : t(`supportAccess.status.${tab}`)
}

async function loadGrants(more = false) {
  if (more) loadingMore.value = true
  else loading.value = true
  loadFailed.value = false
  try {
    const res = await supportApi.listOperatorGrants({
      status: activeTab.value === 'ALL' ? undefined : activeTab.value,
      cursor: more ? nextCursor.value : null,
    })
    const rows = res.data.filter(hasGrantId)
    grants.value = more ? [...grants.value, ...rows] : rows
    nextCursor.value = res.meta.nextCursor
    hasNext.value = res.meta.hasNext
  }
  catch {
    loadFailed.value = true
  }
  finally {
    loading.value = false
    loadingMore.value = false
  }
}

watch(activeTab, () => loadGrants())

// ─── 表示の補助 ───
function statusSeverity(status: SupportAccessStatus): 'info' | 'success' | 'secondary' | 'warn' | 'danger' {
  switch (status) {
    case 'PENDING': return 'info'
    case 'APPROVED': return 'success'
    case 'DENIED': return 'danger'
    default: return 'secondary'
  }
}

function guardianWaiting(grant: GrantRow): boolean {
  return grant.status === 'PENDING' && grant.guardianApprovalRequired === true
}

function referenceLabel(type?: string | null, id?: number | null): string {
  if (!type) return ''
  const key = `supportAccess.reference.${type}`
  const base = te(key) ? t(key) : type
  return id == null ? base : `${base} #${id}`
}

function endedReasonLabel(reason: string | null): string {
  if (!reason) return ''
  const key = `supportAccess.endedReason.${reason}`
  return te(key) ? t(key) : reason
}

function apiMessage(error: unknown, fallbackKey: string): string {
  return parseApiError(error).message ?? t(fallbackKey)
}

function canStart(grant: GrantRow): boolean {
  return grant.status === 'APPROVED' && !!grant.expiresAt && Date.parse(grant.expiresAt) > Date.now()
}

function canCancel(grant: GrantRow): boolean {
  return grant.status === 'PENDING' || grant.status === 'APPROVED'
}

// ─── 依頼ダイアログ ───
const createVisible = ref(false)
const creating = ref(false)
const createError = ref('')
const form = reactive({
  targetUserId: null as number | null,
  reason: '',
  referenceType: null as string | null,
  referenceId: null as number | null,
})
const referenceTypeOptions = computed(() => [
  { label: t('supportAccess.admin.referenceNone'), value: null },
  { label: t('supportAccess.reference.FEEDBACK'), value: 'FEEDBACK' },
])
const reasonLength = computed(() => form.reason.trim().length)
const reasonValid = computed(() => reasonLength.value >= REASON_MIN && reasonLength.value <= REASON_MAX)
const canSubmit = computed(() => form.targetUserId !== null && form.targetUserId > 0 && reasonValid.value && !creating.value)

function openCreate(prefill?: { targetUserId?: number | null; referenceType?: string | null; referenceId?: number | null }) {
  form.targetUserId = prefill?.targetUserId ?? null
  form.reason = ''
  form.referenceType = prefill?.referenceType ?? null
  form.referenceId = prefill?.referenceId ?? null
  createError.value = ''
  createVisible.value = true
}

async function submitCreate() {
  if (!canSubmit.value || form.targetUserId === null) return
  creating.value = true
  createError.value = ''
  try {
    await supportApi.createGrant({
      targetUserId: form.targetUserId,
      reason: form.reason.trim(),
      ...(form.referenceType ? { referenceType: form.referenceType } : {}),
      ...(form.referenceType && form.referenceId ? { referenceId: form.referenceId } : {}),
    })
    createVisible.value = false
    notification.success(t('supportAccess.admin.requested'))
    activeTab.value = 'ALL'
    await loadGrants()
  }
  catch (error) {
    // 対象が不正・重複・上限超過などの理由は BE のメッセージ（本人の言語）をそのまま出す
    createError.value = apiMessage(error, 'supportAccess.admin.requestError')
  }
  finally {
    creating.value = false
  }
}

// ─── 開始・撤回・終了 ───
const busyId = ref<string | null>(null)

async function startViewing(grant: GrantRow) {
  busyId.value = grant.id
  try {
    const res = await supportApi.startSession(grant.id)
    const session = toSupportAccessSession(res.data)
    if (!session) {
      // 応答に必須の値（grant・本人・期限）が欠けていたら閲覧を始めない（本人の画面を不完全な状態で開かない）
      notification.error(t('supportAccess.admin.startError'))
      return
    }
    store.startSession(session)
    notification.success(t('supportAccess.admin.started', { name: session.targetDisplayName }))
    await navigateTo('/')
  }
  catch (error) {
    notification.error(apiMessage(error, 'supportAccess.admin.startError'))
  }
  finally {
    busyId.value = null
  }
}

function confirmCancel(grant: GrantRow) {
  cancelTarget.value = grant
}
const cancelTarget = ref<GrantRow | null>(null)

async function cancelGrant() {
  const grant = cancelTarget.value
  if (!grant) return
  busyId.value = grant.id
  try {
    await supportApi.cancelGrant(grant.id)
    notification.success(t('supportAccess.admin.cancelled'))
    cancelTarget.value = null
    await loadGrants()
  }
  catch (error) {
    notification.error(apiMessage(error, 'supportAccess.admin.cancelError'))
  }
  finally {
    busyId.value = null
  }
}

// ─── 詳細（閲覧記録） ───
const detailGrant = ref<GrantRow | null>(null)
const detailVisible = ref(false)
const logs = ref<SupportAccessLog[]>([])
const logsLoading = ref(false)
const logsFailed = ref(false)
const logsNextCursor = ref<string | null>(null)
const logsHasNext = ref(false)

async function loadLogs(more = false) {
  const grant = detailGrant.value
  if (!grant) return
  logsLoading.value = true
  logsFailed.value = false
  try {
    const res = await supportApi.listGrantLogs(grant.id, more ? logsNextCursor.value : null)
    logs.value = more ? [...logs.value, ...res.data] : res.data
    logsNextCursor.value = res.meta.nextCursor
    logsHasNext.value = res.meta.hasNext
  }
  catch {
    logsFailed.value = true
  }
  finally {
    logsLoading.value = false
  }
}

async function openDetail(grant: GrantRow) {
  detailGrant.value = grant
  detailVisible.value = true
  logs.value = []
  await loadLogs()
}

function eventKindLabel(kind: string): string {
  const key = `supportAccess.eventKind.${kind}`
  return te(key) ? t(key) : kind
}

function scopeLabel(log: SupportAccessLog): string {
  if (!log.scopeType) return ''
  const key = `supportAccess.scopeType.${log.scopeType}`
  const base = te(key) ? t(key) : log.scopeType
  return log.scopeId == null ? base : `${base} #${log.scopeId}`
}

// ─── 初期表示 ───
function queryNumber(value: unknown): number | null {
  const raw = Array.isArray(value) ? value[0] : value
  const n = Number(raw)
  return Number.isInteger(n) && n > 0 ? n : null
}

onMounted(async () => {
  // 目安箱の「閲覧許可を依頼」から来た場合は、対象者と参照を入れた依頼ダイアログを開く
  const targetUserId = queryNumber(route.query.targetUserId)
  if (targetUserId) {
    const referenceType = typeof route.query.referenceType === 'string' ? route.query.referenceType : null
    openCreate({ targetUserId, referenceType, referenceId: queryNumber(route.query.referenceId) })
    await router.replace({ path: route.path })
  }
  await loadGrants()
})
</script>

<template>
  <div class="mx-auto max-w-6xl p-6">
    <PageHeader :title="t('supportAccess.admin.title')">
      <template #actions>
        <Button
          icon="pi pi-plus"
          :label="t('supportAccess.admin.request')"
          data-testid="support-access-request"
          @click="openCreate()"
        />
      </template>
      <template #subtitle>
        {{ t('supportAccess.admin.description') }}
      </template>
    </PageHeader>

    <Message severity="info" :closable="false" class="mb-4">
      {{ t('supportAccess.admin.notice') }}
    </Message>

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab v-for="tab in TAB_ORDER" :key="tab" :value="tab">
          {{ tabLabel(tab) }}
        </Tab>
      </TabList>
    </Tabs>

    <Message v-if="loadFailed" severity="error" :closable="false" class="mt-4">
      {{ t('supportAccess.admin.loadError') }}
      <Button :label="t('supportAccess.admin.retry')" text size="small" @click="loadGrants()" />
    </Message>

    <DataTable
      v-else
      :value="grants"
      :loading="loading"
      data-key="id"
      class="mt-4"
      data-testid="support-access-table"
    >
      <template #empty>
        <p class="py-6 text-center text-surface-500">
          {{ t('supportAccess.admin.empty') }}
        </p>
      </template>
      <Column :header="t('supportAccess.admin.columns.target')">
        <template #body="{ data }">
          <div class="font-medium">
            {{ data.targetDisplayName }}
          </div>
          <div class="text-xs text-surface-500">
            ID {{ data.targetUserId }}
            <template v-if="data.referenceType">
              ・{{ referenceLabel(data.referenceType, data.referenceId) }}
            </template>
          </div>
        </template>
      </Column>
      <Column :header="t('supportAccess.admin.columns.status')">
        <template #body="{ data }">
          <Tag :value="t(`supportAccess.status.${data.status}`)" :severity="statusSeverity(data.status)" />
          <div v-if="guardianWaiting(data)" class="mt-1 text-xs text-surface-500">
            {{ t('supportAccess.guardianWaiting') }}
          </div>
          <div v-if="data.endedReason" class="mt-1 text-xs text-surface-500">
            {{ endedReasonLabel(data.endedReason) }}
          </div>
          <div v-if="store.grantId === data.id && store.isActive" class="mt-1 text-xs font-semibold text-orange-600">
            {{ t('supportAccess.admin.viewingNow') }}
          </div>
        </template>
      </Column>
      <Column :header="t('supportAccess.admin.columns.requestedAt')">
        <template #body="{ data }">
          {{ formatDateTime(data.requestedAt) }}
        </template>
      </Column>
      <Column :header="t('supportAccess.admin.columns.expiresAt')">
        <template #body="{ data }">
          <template v-if="data.status === 'APPROVED'">
            {{ formatDateTime(data.expiresAt) }}
          </template>
          <template v-else-if="data.status === 'PENDING'">
            <span class="text-xs text-surface-500">{{ t('supportAccess.admin.requestUntil', { date: formatDateTime(data.requestExpiresAt) }) }}</span>
          </template>
        </template>
      </Column>
      <Column :header="t('label.actions')" class="w-72">
        <template #body="{ data }">
          <div class="flex flex-wrap gap-1">
            <Button
              :label="t('supportAccess.admin.detail')"
              icon="pi pi-list"
              size="small"
              text
              @click="openDetail(data)"
            />
            <Button
              v-if="canStart(data)"
              :label="t('supportAccess.admin.start')"
              icon="pi pi-eye"
              size="small"
              severity="warn"
              :loading="busyId === data.id"
              data-testid="support-access-start"
              @click="startViewing(data)"
            />
            <Button
              v-if="store.grantId === data.id && store.isActive"
              :label="t('supportAccess.banner.end')"
              icon="pi pi-times"
              size="small"
              severity="secondary"
              @click="store.endSession()"
            />
            <Button
              v-if="canCancel(data)"
              :label="t('supportAccess.admin.cancel')"
              icon="pi pi-ban"
              size="small"
              severity="danger"
              text
              data-testid="support-access-cancel"
              @click="confirmCancel(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <div v-if="hasNext" class="mt-4 text-center">
      <Button
        :label="t('supportAccess.admin.loadMore')"
        severity="secondary"
        :loading="loadingMore"
        @click="loadGrants(true)"
      />
    </div>

    <!-- 依頼ダイアログ -->
    <Dialog
      v-model:visible="createVisible"
      :header="t('supportAccess.admin.request')"
      modal
      class="w-full max-w-xl"
      data-testid="support-access-create-dialog"
    >
      <form class="space-y-4" @submit.prevent="submitCreate">
        <Message v-if="createError" severity="error" :closable="false" data-testid="support-access-create-error">
          {{ createError }}
        </Message>
        <p class="text-sm text-surface-500">
          {{ t('supportAccess.admin.createDescription') }}
        </p>

        <div class="flex flex-col gap-2">
          <label for="sa-target" class="text-sm font-semibold">{{ t('supportAccess.admin.targetUserId') }}</label>
          <InputNumber v-model="form.targetUserId" input-id="sa-target" :use-grouping="false" :min="1" fluid />
        </div>

        <div class="flex flex-col gap-2">
          <label for="sa-reason" class="text-sm font-semibold">{{ t('supportAccess.admin.reason') }}</label>
          <Textarea
            id="sa-reason"
            v-model="form.reason"
            rows="5"
            :maxlength="REASON_MAX"
            auto-resize
            fluid
            data-testid="support-access-reason"
          />
          <div class="flex justify-between text-xs text-surface-500">
            <span>{{ t('supportAccess.admin.reasonHint', { min: REASON_MIN, max: REASON_MAX }) }}</span>
            <span :class="{ 'text-red-600': reasonLength > 0 && !reasonValid }">{{ reasonLength }} / {{ REASON_MAX }}</span>
          </div>
        </div>

        <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div class="flex flex-col gap-2">
            <label class="text-sm font-semibold">{{ t('supportAccess.admin.reference') }}</label>
            <Select
              v-model="form.referenceType"
              :options="referenceTypeOptions"
              option-label="label"
              option-value="value"
              fluid
            />
          </div>
          <div v-if="form.referenceType" class="flex flex-col gap-2">
            <label for="sa-ref-id" class="text-sm font-semibold">{{ t('supportAccess.admin.referenceId') }}</label>
            <InputNumber v-model="form.referenceId" input-id="sa-ref-id" :use-grouping="false" :min="1" fluid />
          </div>
        </div>

        <div class="flex justify-end gap-2">
          <Button type="button" :label="t('button.cancel')" severity="secondary" @click="createVisible = false" />
          <Button
            type="submit"
            :label="t('supportAccess.admin.submit')"
            :loading="creating"
            :disabled="!canSubmit"
            data-testid="support-access-submit"
          />
        </div>
      </form>
    </Dialog>

    <!-- 撤回の確認 -->
    <Dialog
      :visible="cancelTarget !== null"
      :header="t('supportAccess.admin.cancel')"
      modal
      class="w-full max-w-md"
      @update:visible="(v: boolean) => { if (!v) cancelTarget = null }"
    >
      <p class="text-sm">
        {{ t('supportAccess.admin.cancelConfirm', { name: cancelTarget?.targetDisplayName ?? '' }) }}
      </p>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" text @click="cancelTarget = null" />
        <Button
          :label="t('supportAccess.admin.cancel')"
          severity="danger"
          :loading="busyId === cancelTarget?.id"
          data-testid="support-access-cancel-confirm"
          @click="cancelGrant"
        />
      </template>
    </Dialog>

    <!-- 詳細（閲覧記録） -->
    <Dialog
      v-model:visible="detailVisible"
      :header="t('supportAccess.admin.detail')"
      modal
      class="w-full max-w-3xl"
      data-testid="support-access-detail-dialog"
    >
      <div v-if="detailGrant" class="space-y-4">
        <dl class="grid grid-cols-1 gap-x-4 gap-y-2 text-sm sm:grid-cols-[8rem_1fr]">
          <dt class="text-surface-500">{{ t('supportAccess.admin.columns.target') }}</dt>
          <dd>{{ detailGrant.targetDisplayName }}（ID {{ detailGrant.targetUserId }}）</dd>
          <dt class="text-surface-500">{{ t('supportAccess.admin.columns.status') }}</dt>
          <dd>
            {{ t(`supportAccess.status.${detailGrant.status}`) }}
            <span v-if="detailGrant.endedReason" class="text-surface-500">（{{ endedReasonLabel(detailGrant.endedReason) }}）</span>
          </dd>
          <dt class="text-surface-500">{{ t('supportAccess.admin.reason') }}</dt>
          <!-- 依頼理由はプレーンテキストで表示する（リンク化しない） -->
          <dd class="whitespace-pre-wrap [overflow-wrap:anywhere]">{{ detailGrant.requestReason }}</dd>
          <template v-if="detailGrant.referenceType">
            <dt class="text-surface-500">{{ t('supportAccess.admin.reference') }}</dt>
            <dd>{{ referenceLabel(detailGrant.referenceType, detailGrant.referenceId) }}</dd>
          </template>
          <dt class="text-surface-500">{{ t('supportAccess.admin.columns.requestedAt') }}</dt>
          <dd>{{ formatDateTime(detailGrant.requestedAt) }}</dd>
          <template v-if="detailGrant.expiresAt">
            <dt class="text-surface-500">{{ t('supportAccess.admin.columns.expiresAt') }}</dt>
            <dd>{{ formatDateTime(detailGrant.expiresAt) }}</dd>
          </template>
        </dl>

        <h3 class="text-base font-semibold">{{ t('supportAccess.admin.logs') }}</h3>
        <Message v-if="logsFailed" severity="error" :closable="false">
          {{ t('supportAccess.admin.loadError') }}
        </Message>
        <DataTable v-else :value="logs" :loading="logsLoading" size="small" data-testid="support-access-logs">
          <template #empty>
            <p class="py-4 text-center text-surface-500">{{ t('supportAccess.admin.noLogs') }}</p>
          </template>
          <Column :header="t('supportAccess.logs.when')">
            <template #body="{ data }">{{ formatDateTime(data.occurredAt) }}</template>
          </Column>
          <Column :header="t('supportAccess.logs.kind')">
            <template #body="{ data }">{{ eventKindLabel(data.eventKind) }}</template>
          </Column>
          <Column :header="t('supportAccess.logs.path')">
            <template #body="{ data }">
              <code class="text-xs [overflow-wrap:anywhere]">{{ data.requestPath ?? '' }}</code>
            </template>
          </Column>
          <Column :header="t('supportAccess.logs.scope')">
            <template #body="{ data }">{{ scopeLabel(data) }}</template>
          </Column>
        </DataTable>
        <div v-if="logsHasNext" class="text-center">
          <Button :label="t('supportAccess.admin.loadMore')" severity="secondary" size="small" :loading="logsLoading" @click="loadLogs(true)" />
        </div>
      </div>
    </Dialog>
  </div>
</template>
