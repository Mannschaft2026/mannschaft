<script setup lang="ts">
import type { InvitationResponse } from '@/types/parental-consent'

// default レイアウトは受信箱ポーリング・通知 WebSocket・参加待ち招待取得を持ち、BE の同意ゲートで
// 403 AUTH_070 になる。背景処理を持たない auth レイアウトで描画する（CMP-261123-1124 AC4）。
definePageMeta({
  layout: 'auth',
  middleware: 'auth',
})

const { t } = useI18n()
const { sendInvitation, getInvitations, cancelInvitation, getParents } = useParentalConsentApi()
const notification = useNotification()
const { formatDate } = useDatetime()
const authStore = useAuthStore()
const runtimeConfig = useRuntimeConfig()
const api = useApi()

const invitations = ref<InvitationResponse[]>([])
const loading = ref(true)
const parentEmail = ref('')
const sending = ref(false)

/** /users/me から本人を取り直して保存する。成功し、同意待ちでなくなったときだけ true。 */
async function reloadCurrentUser(): Promise<boolean> {
  try {
    const profile = await api<{
      data: {
        id: number
        email: string
        lastName: string
        firstName: string
        avatarUrl: string | null
        systemRole: string | null
        locale: string
        timezone: string | null
        status: string | null
      }
    }>('/api/v1/users/me')
    await authStore.setUser({
      id: profile.data.id,
      email: profile.data.email,
      fullName: profile.data.lastName + ' ' + profile.data.firstName,
      profileImageUrl: profile.data.avatarUrl,
      systemRole: profile.data.systemRole ?? undefined,
      timezone: profile.data.timezone ?? undefined,
      locale: profile.data.locale || undefined,
      status: profile.data.status ?? undefined,
    })
    return !authStore.isPendingParentalConsent
  } catch {
    notification.error(t('error.unknown'))
    return false
  }
}

async function loadData() {
  loading.value = true
  try {
    const [invs, parents] = await Promise.all([getInvitations(), getParents()])
    invitations.value = invs
    // 承認済み保護者がいれば → 保護者管理画面へ
    if (parents.length > 0) {
      // 更新前のトークンは同意待ち（ppc）の印を持ったままで、参加待ち招待の GET が AUTH_070 になる。
      // 更新の完了を待ってから遷移する（CMP-261107-1833 設計 §9-1）
      // 失敗しても例外は投げず結果で返る。成功のときだけ遷移し、失敗は既存の呼び出し元（stompAuth）と同じ扱いにする
      const refreshed = await performTokenRefresh(runtimeConfig, authStore)
      if (refreshed === 'refreshed') {
        // 新しいトークンで本人を取り直し、同意待ちの印（status）を外してから遷移する。
        // 印が残ったままだと auth middleware が pending へ戻す。
        if (await reloadCurrentUser()) {
          await navigateTo('/parental-consent/manage')
        }
      } else if (refreshed === 'auth_failed') {
        await handleAuthFailureLogout(authStore)
      } else if (refreshed === 'transient') {
        notification.error(t('error.unknown'))
      }
      // session_changed: 別の本人へ切り替わっているため何もしない
    }
  } finally {
    loading.value = false
  }
}

async function handleSendInvitation() {
  if (!parentEmail.value) return
  sending.value = true
  try {
    await sendInvitation(parentEmail.value)
    parentEmail.value = ''
    notification.success(t('parental_consent.invite_sent'))
    await loadData()
  } catch (err: unknown) {
    const code = (err as { data?: { code?: string } })?.data?.code ?? ''
    const keyMap: Record<string, string> = {
      AUTH_062: 'parental_consent.error_auth_062',
      AUTH_063: 'parental_consent.error_auth_063',
      AUTH_067: 'parental_consent.error_auth_067',
      AUTH_068: 'parental_consent.error_auth_068',
      AUTH_069: 'parental_consent.error_auth_069',
    }
    notification.error(t(keyMap[code] ?? 'error.unknown'))
  } finally {
    sending.value = false
  }
}

async function handleCancelInvitation(linkId: string) {
  try {
    await cancelInvitation(linkId)
    notification.success(t('parental_consent.invite_cancelled'))
    await loadData()
  } catch {
    notification.error(t('error.unknown'))
  }
}

onMounted(async () => {
  // 同意待ち以外（一般成人など）にはこの画面を見せない。通常の遷移先へ戻し、BE ゲート対象の API も呼ばない。
  // 承認後に印が外れても manage へ進めるよう、判定は表示時の1回だけ行う（監視はしない）。
  if (!authStore.isPendingParentalConsent) {
    await navigateTo(authStore.isSystemAdmin ? '/system-admin' : '/dashboard', { replace: true })
    return
  }
  await loadData()
})
</script>

<template>
  <!-- 同意待ち以外には「18歳未満のため…」を見せない（onMounted で通常の遷移先へ戻す） -->
  <div v-if="authStore.isPendingParentalConsent" class="max-w-2xl mx-auto py-8 px-4">
    <h1 class="text-2xl font-bold mb-2">{{ $t('parental_consent.pending_title') }}</h1>
    <p class="text-gray-600 mb-6">{{ $t('parental_consent.pending_description') }}</p>

    <!-- 招待送信フォーム -->
    <div class="bg-white border border-gray-200 rounded-lg p-6 mb-6">
      <h2 class="text-lg font-semibold mb-4">{{ $t('parental_consent.invitations_title') }}</h2>
      <div class="flex gap-2">
        <input
          v-model="parentEmail"
          type="email"
          :placeholder="$t('parental_consent.invite_email_placeholder')"
          class="flex-1 px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
        <button
          :disabled="sending || !parentEmail"
          class="px-4 py-2 bg-indigo-600 text-white rounded-md hover:bg-indigo-700 disabled:opacity-50"
          @click="handleSendInvitation"
        >
          {{ $t('parental_consent.send_invite') }}
        </button>
      </div>
    </div>

    <!-- 送信済み招待一覧 -->
    <div v-if="loading" class="text-center py-8 text-gray-400">...</div>
    <div v-else-if="invitations.length === 0" class="text-center py-8 text-gray-400">
      {{ $t('parental_consent.no_invitations') }}
    </div>
    <ul v-else class="space-y-3">
      <li
        v-for="inv in invitations"
        :key="inv.linkId"
        class="bg-white border border-gray-200 rounded-lg p-4 flex items-center justify-between"
      >
        <div>
          <p class="font-medium">{{ inv.parentEmail }}</p>
          <p class="text-sm text-gray-500">
            <span
              :class="{
                'text-yellow-600': inv.status === 'PENDING',
                'text-green-600': inv.status === 'APPROVED',
                'text-red-600': inv.status === 'REJECTED' || inv.status === 'REVOKED',
              }"
            >{{ $t(`parental_consent.status_${inv.status.toLowerCase()}`) }}</span>
            · {{ $t('parental_consent.expires_at', { date: formatDate(inv.expiresAt) }) }}
          </p>
        </div>
        <button
          v-if="inv.status === 'PENDING'"
          class="text-sm text-red-600 hover:text-red-800"
          @click="handleCancelInvitation(inv.linkId)"
        >
          {{ $t('parental_consent.cancel_invite') }}
        </button>
      </li>
    </ul>

    <div class="mt-8 text-center">
      <button class="text-sm text-gray-500 hover:text-gray-700 underline" @click="authStore.serverLogout()">
        {{ $t('button.logout') }}
      </button>
    </div>
  </div>
</template>
