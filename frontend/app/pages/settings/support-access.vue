<script setup lang="ts">
import type { MySupportAccessGrant, SupportAccessLog, WithGrantId } from '~/types/support-access'
import { hasGrantId } from '~/types/support-access'

/** 操作できる（grant ID のある）行。ID の無い行は API の境界で除く。 */
type MyGrantRow = WithGrantId<MySupportAccessGrant>

/**
 * F10.9 サポート閲覧の許可（本人側）。
 *
 * 運営からの「画面を見せてほしい」という依頼に、本人（未成年・後見下は保護者も）が許可・拒否する。
 * - 許可には再認証（パスワードまたはパスキー）が必須。メールのワンクリックでは許可できない。
 * - 許可すると、運営は許可から24時間だけ、閲覧のみ（変更・投稿・削除はできない）で画面を確認できる。いつでも取り消せる。
 * - 運営が書いた依頼理由はプレーンテキストで表示する（リンク化しない）。
 * - 許可・拒否・取消はすべて画面内（インライン）で完結させ、モーダルを重ねない。
 */
definePageMeta({ middleware: 'auth' })

const { t, te } = useI18n()
const notification = useNotification()
const { formatDateTime } = useDatetime()
const supportApi = useSupportAccessApi()
const passkey = usePasskeyReauthAssertion()

const showHelp = ref(false)

// ─── 一覧 ───
const pending = ref<MyGrantRow[]>([])
const active = ref<MyGrantRow[]>([])
const logs = ref<SupportAccessLog[]>([])
const loading = ref(true)
const loadFailed = ref(false)
const logsNextCursor = ref<string | null>(null)
const logsHasNext = ref(false)
const logsLoading = ref(false)

/** 期限がまだ先か（期限の値が無い行は有効とみなさない）。 */
function notExpired(iso?: string | null): boolean {
  return !!iso && Date.parse(iso) > Date.now()
}

async function loadAll() {
  loading.value = true
  loadFailed.value = false
  try {
    const [pendingRes, activeRes, logsRes] = await Promise.all([
      supportApi.listMyGrants({ status: 'PENDING', limit: 50 }),
      supportApi.listMyGrants({ status: 'APPROVED', limit: 50 }),
      supportApi.listMyLogs(),
    ])
    pending.value = pendingRes.data.filter(hasGrantId).filter(g => notExpired(g.requestExpiresAt))
    active.value = activeRes.data.filter(hasGrantId).filter(g => notExpired(g.expiresAt))
    logs.value = logsRes.data
    logsNextCursor.value = logsRes.meta.nextCursor
    logsHasNext.value = logsRes.meta.hasNext
  }
  catch {
    loadFailed.value = true
  }
  finally {
    loading.value = false
  }
}

async function loadMoreLogs() {
  if (!logsNextCursor.value) return
  logsLoading.value = true
  try {
    const res = await supportApi.listMyLogs({ cursor: logsNextCursor.value })
    logs.value = [...logs.value, ...res.data]
    logsNextCursor.value = res.meta.nextCursor
    logsHasNext.value = res.meta.hasNext
  }
  catch {
    notification.error(t('supportAccess.settings.loadError'))
  }
  finally {
    logsLoading.value = false
  }
}

onMounted(loadAll)

// ─── 表示の補助 ───
function referenceLabel(type?: string | null): string {
  if (!type) return ''
  const key = `supportAccess.reference.${type}`
  return te(key) ? t(key) : type
}

function scopeLabel(log: SupportAccessLog): string {
  if (!log.scopeType) return ''
  const key = `supportAccess.scopeType.${log.scopeType}`
  const base = te(key) ? t(key) : log.scopeType
  return log.scopeId == null ? base : `${base} #${log.scopeId}`
}

/** 保護者の承認待ち（本人は許可済みで、保護者の許可がまだ）。 */
function guardianWaiting(grant: MyGrantRow): boolean {
  // 生成型は optional。値が無い（undefined）は「許可していない／要らない」として扱う
  return grant.role === 'TARGET' && grant.guardianApprovalRequired === true && grant.targetApproved === true && grant.guardianApproved !== true
}

/** 自分の許可がまだで、保護者の許可も必要な依頼（本人側の注意書き用）。 */
function needsGuardianToo(grant: MyGrantRow): boolean {
  return grant.role === 'TARGET' && grant.guardianApprovalRequired === true && grant.targetApproved !== true
}

// ─── インライン操作（許可・拒否・取消）。同時に開くのは 1 つだけ ───
type PanelMode = 'approve' | 'deny' | 'revoke'
const panel = ref<{ id: string; mode: PanelMode } | null>(null)
const password = ref('')
const denyComment = ref('')
const panelError = ref('')
const busy = ref(false)

function openPanel(id: string, mode: PanelMode) {
  panel.value = { id, mode }
  password.value = ''
  denyComment.value = ''
  panelError.value = ''
}

function closePanel() {
  panel.value = null
  password.value = ''
  denyComment.value = ''
  panelError.value = ''
}

function isPanel(id: string, mode: PanelMode): boolean {
  return panel.value?.id === id && panel.value.mode === mode
}

function apiMessage(error: unknown, fallbackKey: string): string {
  return parseApiError(error).message ?? t(fallbackKey)
}

async function approveWith(grant: MyGrantRow, body: { currentPassword?: string; passkeyAssertionJson?: string }) {
  busy.value = true
  panelError.value = ''
  try {
    await supportApi.approveGrant(grant.id, body)
    notification.success(t('supportAccess.settings.approved'))
    closePanel()
    await loadAll()
  }
  catch (error) {
    // 再認証の失敗（SUPPORT_ACCESS_013）や試行回数の超過は BE のメッセージを出す
    panelError.value = apiMessage(error, 'supportAccess.settings.actionError')
  }
  finally {
    busy.value = false
    password.value = ''
  }
}

async function approveWithPassword(grant: MyGrantRow) {
  if (!password.value) return
  await approveWith(grant, { currentPassword: password.value })
}

async function approveWithPasskey(grant: MyGrantRow) {
  busy.value = true
  panelError.value = ''
  let assertion: string | null
  try {
    assertion = await passkey.getAssertionJson()
  }
  catch (error) {
    busy.value = false
    panelError.value = error instanceof PasskeyReauthUnsupportedError
      ? t('supportAccess.settings.passkeyUnsupported')
      : apiMessage(error, 'supportAccess.settings.actionError')
    return
  }
  busy.value = false
  if (!assertion) return // 認証器のキャンセル・タイムアウト
  await approveWith(grant, { passkeyAssertionJson: assertion })
}

async function deny(grant: MyGrantRow) {
  busy.value = true
  panelError.value = ''
  try {
    await supportApi.denyGrant(grant.id, denyComment.value.trim() || undefined)
    notification.success(t('supportAccess.settings.denied'))
    closePanel()
    await loadAll()
  }
  catch (error) {
    panelError.value = apiMessage(error, 'supportAccess.settings.actionError')
  }
  finally {
    busy.value = false
  }
}

async function revoke(grant: MyGrantRow) {
  busy.value = true
  panelError.value = ''
  try {
    await supportApi.revokeGrant(grant.id)
    notification.success(t('supportAccess.settings.revoked'))
    closePanel()
    await loadAll()
  }
  catch (error) {
    panelError.value = apiMessage(error, 'supportAccess.settings.actionError')
  }
  finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <PageHeader :title="t('supportAccess.settings.title')" help @help="showHelp = true" />
    <SupportAccessHelpDialog v-model:visible="showHelp" />

    <p class="mb-6 text-sm text-surface-600 dark:text-surface-300">
      {{ t('supportAccess.settings.description') }}
    </p>

    <Message v-if="loadFailed" severity="error" :closable="false" class="mb-4">
      {{ t('supportAccess.settings.loadError') }}
      <Button :label="t('supportAccess.admin.retry')" text size="small" @click="loadAll" />
    </Message>

    <template v-else>
      <!-- 依頼中 -->
      <SectionCard :title="t('supportAccess.settings.pending')" class="mb-6" data-testid="support-access-pending">
        <p v-if="!loading && pending.length === 0" class="text-sm text-surface-500">
          {{ t('supportAccess.settings.noPending') }}
        </p>
        <ul class="space-y-4">
          <li
            v-for="grant in pending"
            :key="grant.id"
            class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
            data-testid="support-access-pending-item"
          >
            <p v-if="grant.role === 'GUARDIAN'" class="mb-2 text-sm font-semibold text-primary">
              {{ t('supportAccess.settings.guardianRole', { name: grant.targetDisplayName }) }}
            </p>
            <dl class="grid grid-cols-1 gap-x-4 gap-y-1 text-sm sm:grid-cols-[8rem_1fr]">
              <dt class="text-surface-500">{{ t('supportAccess.settings.requestedBy') }}</dt>
              <dd>{{ grant.operatorDisplayName }}</dd>
              <dt class="text-surface-500">{{ t('supportAccess.settings.requestReason') }}</dt>
              <!-- 依頼理由はプレーンテキスト（リンク化しない・HTML として解釈しない） -->
              <dd class="whitespace-pre-wrap [overflow-wrap:anywhere]" data-testid="support-access-reason-text">{{ grant.requestReason }}</dd>
              <template v-if="grant.referenceType">
                <dt class="text-surface-500">{{ t('supportAccess.settings.reference') }}</dt>
                <dd>{{ referenceLabel(grant.referenceType) }}</dd>
              </template>
              <dt class="text-surface-500">{{ t('supportAccess.settings.requestedAt') }}</dt>
              <dd>{{ formatDateTime(grant.requestedAt) }}</dd>
              <dt class="text-surface-500">{{ t('supportAccess.settings.requestUntil') }}</dt>
              <dd>{{ formatDateTime(grant.requestExpiresAt) }}</dd>
            </dl>

            <Message v-if="guardianWaiting(grant)" severity="info" :closable="false" class="mt-3" data-testid="support-access-guardian-waiting">
              {{ t('supportAccess.guardianWaiting') }}
            </Message>
            <p v-else-if="needsGuardianToo(grant)" class="mt-3 text-xs text-surface-500">
              {{ t('supportAccess.settings.guardianToo') }}
            </p>

            <!-- 許可・拒否（本人または保護者が、いま判断できるときだけ） -->
            <div v-if="grant.canApprove && !panel?.id" class="mt-3 flex flex-wrap gap-2">
              <Button
                :label="t('supportAccess.settings.approve')"
                icon="pi pi-check"
                data-testid="support-access-approve"
                @click="openPanel(grant.id, 'approve')"
              />
              <Button
                :label="t('supportAccess.settings.deny')"
                icon="pi pi-times"
                severity="secondary"
                data-testid="support-access-deny"
                @click="openPanel(grant.id, 'deny')"
              />
            </div>

            <!-- 許可: 再認証（インライン。パスワードまたはパスキー） -->
            <form
              v-if="isPanel(grant.id, 'approve')"
              class="mt-3 space-y-3 rounded-lg bg-surface-50 p-3 dark:bg-surface-800"
              data-testid="support-access-reauth"
              @submit.prevent="approveWithPassword(grant)"
            >
              <Message v-if="panelError" severity="error" :closable="false" data-testid="support-access-panel-error">
                {{ panelError }}
              </Message>
              <p class="text-sm">{{ t('supportAccess.settings.reauthRequired') }}</p>
              <p class="text-xs text-surface-500">{{ t('supportAccess.settings.approveNotice') }}</p>
              <div class="flex flex-col gap-2">
                <label :for="`sa-pw-${grant.id}`" class="text-sm font-semibold">{{ t('supportAccess.settings.password') }}</label>
                <Password
                  v-model="password"
                  :input-id="`sa-pw-${grant.id}`"
                  :feedback="false"
                  toggle-mask
                  fluid
                  autocomplete="current-password"
                  data-testid="support-access-password"
                />
              </div>
              <div class="flex flex-wrap gap-2">
                <Button
                  type="submit"
                  :label="t('supportAccess.settings.approveWithPassword')"
                  icon="pi pi-check"
                  :loading="busy"
                  :disabled="!password || busy"
                  data-testid="support-access-approve-password"
                />
                <Button
                  v-if="passkey.supported()"
                  type="button"
                  :label="t('supportAccess.settings.approveWithPasskey')"
                  icon="pi pi-key"
                  severity="secondary"
                  :disabled="busy"
                  data-testid="support-access-approve-passkey"
                  @click="approveWithPasskey(grant)"
                />
                <Button type="button" :label="t('button.cancel')" text :disabled="busy" @click="closePanel" />
              </div>
            </form>

            <!-- 拒否（コメントは任意） -->
            <form
              v-if="isPanel(grant.id, 'deny')"
              class="mt-3 space-y-3 rounded-lg bg-surface-50 p-3 dark:bg-surface-800"
              @submit.prevent="deny(grant)"
            >
              <Message v-if="panelError" severity="error" :closable="false">
                {{ panelError }}
              </Message>
              <div class="flex flex-col gap-2">
                <label :for="`sa-deny-${grant.id}`" class="text-sm font-semibold">{{ t('supportAccess.settings.denyComment') }}</label>
                <Textarea :id="`sa-deny-${grant.id}`" v-model="denyComment" rows="3" :maxlength="500" auto-resize fluid />
              </div>
              <div class="flex gap-2">
                <Button
                  type="submit"
                  :label="t('supportAccess.settings.denyConfirm')"
                  severity="danger"
                  :loading="busy"
                  data-testid="support-access-deny-confirm"
                />
                <Button type="button" :label="t('button.cancel')" text :disabled="busy" @click="closePanel" />
              </div>
            </form>
          </li>
        </ul>
      </SectionCard>

      <!-- 有効な許可 -->
      <SectionCard :title="t('supportAccess.settings.active')" class="mb-6" data-testid="support-access-active">
        <p v-if="!loading && active.length === 0" class="text-sm text-surface-500">
          {{ t('supportAccess.settings.noActive') }}
        </p>
        <ul class="space-y-4">
          <li
            v-for="grant in active"
            :key="grant.id"
            class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
            data-testid="support-access-active-item"
          >
            <dl class="grid grid-cols-1 gap-x-4 gap-y-1 text-sm sm:grid-cols-[8rem_1fr]">
              <dt class="text-surface-500">{{ t('supportAccess.settings.requestedBy') }}</dt>
              <dd>{{ grant.operatorDisplayName }}</dd>
              <dt class="text-surface-500">{{ t('supportAccess.settings.requestReason') }}</dt>
              <dd class="whitespace-pre-wrap [overflow-wrap:anywhere]">{{ grant.requestReason }}</dd>
              <dt class="text-surface-500">{{ t('supportAccess.settings.validUntil') }}</dt>
              <dd>{{ formatDateTime(grant.expiresAt) }}</dd>
            </dl>
            <div v-if="!panel?.id" class="mt-3">
              <Button
                :label="t('supportAccess.settings.revoke')"
                icon="pi pi-ban"
                severity="danger"
                outlined
                data-testid="support-access-revoke"
                @click="openPanel(grant.id, 'revoke')"
              />
            </div>
            <div v-if="isPanel(grant.id, 'revoke')" class="mt-3 space-y-3 rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
              <Message v-if="panelError" severity="error" :closable="false">
                {{ panelError }}
              </Message>
              <p class="text-sm">{{ t('supportAccess.settings.revokeConfirm') }}</p>
              <div class="flex gap-2">
                <Button
                  :label="t('supportAccess.settings.revoke')"
                  severity="danger"
                  :loading="busy"
                  data-testid="support-access-revoke-confirm"
                  @click="revoke(grant)"
                />
                <Button :label="t('button.cancel')" text :disabled="busy" @click="closePanel" />
              </div>
            </div>
          </li>
        </ul>
      </SectionCard>

      <!-- 閲覧記録 -->
      <SectionCard :title="t('supportAccess.settings.logs')" data-testid="support-access-logs">
        <p class="mb-3 text-xs text-surface-500">{{ t('supportAccess.settings.logsDescription') }}</p>
        <p v-if="!loading && logs.length === 0" class="text-sm text-surface-500">
          {{ t('supportAccess.settings.noLogs') }}
        </p>
        <div v-else class="overflow-x-auto">
          <table class="w-full text-left text-sm">
            <thead>
              <tr class="border-b border-surface-200 text-surface-500 dark:border-surface-700">
                <th class="py-2 pr-3 font-medium">{{ t('supportAccess.logs.when') }}</th>
                <th class="py-2 pr-3 font-medium">{{ t('supportAccess.logs.operator') }}</th>
                <th class="py-2 pr-3 font-medium">{{ t('supportAccess.logs.path') }}</th>
                <th class="py-2 font-medium">{{ t('supportAccess.logs.scope') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(log, index) in logs" :key="`${log.grantId}-${log.occurredAt}-${index}`" class="border-b border-surface-100 align-top dark:border-surface-800">
                <td class="whitespace-nowrap py-2 pr-3">{{ formatDateTime(log.occurredAt) }}</td>
                <td class="py-2 pr-3">{{ log.operatorDisplayName }}</td>
                <td class="py-2 pr-3"><code class="text-xs [overflow-wrap:anywhere]">{{ log.requestPath ?? '' }}</code></td>
                <td class="py-2">{{ scopeLabel(log) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="logsHasNext" class="mt-3 text-center">
          <Button :label="t('supportAccess.admin.loadMore')" severity="secondary" size="small" :loading="logsLoading" @click="loadMoreLogs" />
        </div>
      </SectionCard>
    </template>
  </div>
</template>
