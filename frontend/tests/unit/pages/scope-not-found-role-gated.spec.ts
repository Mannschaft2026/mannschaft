import { computed, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import FriendFoldersPage from '~/pages/teams/[slug]/friend-folders.vue'
import FeeStatementsPage from '~/pages/teams/[slug]/billing/fee-statements.vue'
import OrgPaymentRequestsPage from '~/pages/organizations/[slug]/payment-requests/index.vue'
import { useScopeNotFound } from '~/composables/useScopeNotFound'

/**
 * CMP-261125-1108 アリシゼーション指摘: 画面側のロール判定で出していた
 * 「権限がありません」「管理者のみアクセスできます」を、共通の「見つかりません」（notFound）へ寄せる。
 *
 *   - 非所属・権限なし（所属していて必要ロールが無い場合を含む）→ notFound が立つ・旧文言を出さない・トーストなし
 *   - 権限あり → 従来表示で notFound は立たない
 *   - 権限取得中は notFound を立てない（読み込み中に「見つかりません」がちらつかない）
 */

const state = vi.hoisted(() => ({
  role: 'ADMIN' as string | null,
  permissionResult: { ok: true } as { ok: true } | { ok: false; error: unknown },
  gate: null as Promise<void> | null,
}))
const handleApiError = vi.fn()
const notifyError = vi.fn()
const getFeeStatement = vi.fn()
const listOrganizationPaymentRequests = vi.fn()

const isAdmin = () => state.role === 'ADMIN'
const isAdminOrDeputy = () => state.role === 'ADMIN' || state.role === 'DEPUTY_ADMIN'

mockNuxtImport('useTeamApi', () => () => ({ getTeam: vi.fn().mockResolvedValue({ data: { id: 'x', numericId: 1 } }) }))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'scope-a' } }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key, locale: ref('ja') }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: computed(isAdmin),
  isAdminOrDeputy: computed(isAdminOrDeputy),
  can: () => false,
  loadPermissions: async () => {
    if (state.gate) await state.gate
    return state.permissionResult
  },
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(), error: notifyError, warn: vi.fn(), info: vi.fn(),
}))
mockNuxtImport('useFriendFoldersApi', () => () => ({ deleteFolder: vi.fn(), listFolders: vi.fn().mockResolvedValue([]) }))
mockNuxtImport('useConfirmDialog', () => () => ({ confirmAction: vi.fn() }))
mockNuxtImport('useActivityScopeId', () => () => ({ resolveScopeId: vi.fn().mockResolvedValue(1) }))
mockNuxtImport('usePaymentApi', () => () => ({ getFeeStatement, exportFeeStatementPdf: vi.fn() }))
mockNuxtImport('usePaymentRequestApi', () => () => ({ listOrganizationPaymentRequests }))
mockNuxtImport('useConfirm', () => () => ({ require: vi.fn() }))

const stubs = {
  PageHeader: { props: ['title'], template: '<h1>{{ title }}</h1>' },
  PageLoading: { template: '<div data-testid="loading" />' },
  FriendFolderList: { template: '<section data-testid="folder-list" />' },
  FriendFolderFormDialog: true,
  FriendFolderMembersPanel: true,
  BackButton: true,
  LoadingBounce: true,
  Message: { template: '<div><slot /></div>' },
  Button: true, Select: true, Tag: true, ConfirmDialog: true,
  NuxtLink: { template: '<a><slot /></a>' },
}

const OLD_WORDS = [
  'friends.errors.permission_denied',
  'payment.admin.permissionDenied',
]

const pages = [
  { name: 'friend-folders（フレンドフォルダ）', component: FriendFoldersPage, allowed: ['ADMIN'], denied: ['DEPUTY_ADMIN', 'MEMBER', 'SUPPORTER', null], marker: 'folders.title' },
  { name: 'billing/fee-statements（手数料明細）', component: FeeStatementsPage, allowed: ['ADMIN'], denied: ['DEPUTY_ADMIN', 'MEMBER', 'SUPPORTER', null], marker: 'Fee Statement' },
  { name: '組織 payment-requests（請求管理）', component: OrgPaymentRequestsPage, allowed: ['ADMIN', 'DEPUTY_ADMIN'], denied: ['MEMBER', 'SUPPORTER', null], marker: 'payment.membership.paymentRequest.adminTitle' },
] as const

async function mountPage(component: object) {
  const wrapper = await mountSuspended(component, { global: { stubs } })
  await flushPromises()
  return wrapper
}

describe.each(pages)('$name のロール判定', ({ component, allowed, denied, marker }) => {
  beforeEach(() => {
    vi.clearAllMocks()
    useScopeNotFound().reset()
    state.role = 'ADMIN'
    state.permissionResult = { ok: true }
    state.gate = null
    getFeeStatement.mockResolvedValue({ data: { period: '2026-11', totalFeeAmount: 1, currency: 'JPY', issuerName: 'x' } })
    listOrganizationPaymentRequests.mockResolvedValue({ data: [] })
  })

  it.each(denied.map((r) => [String(r), r]))('ロール %s は notFound が立ち、旧文言もトーストも出ない', async (_label, role) => {
    state.role = role as string | null
    const wrapper = await mountPage(component)
    expect(useScopeNotFound().notFound.value).toBe(true)
    for (const word of OLD_WORDS) expect(wrapper.text()).not.toContain(word)
    expect(notifyError).not.toHaveBeenCalled()
    expect(handleApiError).not.toHaveBeenCalled()
  })

  it.each(allowed.map((r) => [r]))('ロール %s は従来表示で notFound は立たない', async (role) => {
    state.role = role
    const wrapper = await mountPage(component)
    expect(useScopeNotFound().notFound.value).toBe(false)
    expect(wrapper.text() + wrapper.html()).toContain(marker)
    for (const word of OLD_WORDS) expect(wrapper.text()).not.toContain(word)
  })

  it('権限取得が 403 なら notFound（トーストなし）', async () => {
    state.role = null
    state.permissionResult = { ok: false, error: { statusCode: 403, response: { status: 403 } } }
    await mountPage(component)
    expect(useScopeNotFound().notFound.value).toBe(true)
    expect(notifyError).not.toHaveBeenCalled()
    expect(handleApiError).not.toHaveBeenCalled()
  })

  it('権限取得が 500 なら notFound にせず通常のエラー通知', async () => {
    state.role = null
    state.permissionResult = { ok: false, error: { statusCode: 500, response: { status: 500 } } }
    await mountPage(component)
    expect(useScopeNotFound().notFound.value).toBe(false)
    expect(handleApiError.mock.calls.length + notifyError.mock.calls.length).toBeGreaterThan(0)
  })

  it('権限取得中は notFound を立てない（ちらつき防止）', async () => {
    state.role = 'MEMBER'
    let release!: () => void
    state.gate = new Promise<void>((resolve) => { release = resolve })
    const pending = mountSuspended(component, { global: { stubs } })
    await flushPromises()
    expect(useScopeNotFound().notFound.value).toBe(false)
    release()
    await pending
    await flushPromises()
    expect(useScopeNotFound().notFound.value).toBe(true)
  })
})

describe('billing/fee-statements の 404（対象月データなし）は従来どおり', () => {
  it('404 は noData 表示で notFound は立たない', async () => {
    vi.clearAllMocks()
    useScopeNotFound().reset()
    state.role = 'ADMIN'
    state.permissionResult = { ok: true }
    state.gate = null
    getFeeStatement.mockRejectedValue({ status: 404 })
    const wrapper = await mountPage(FeeStatementsPage)
    expect(useScopeNotFound().notFound.value).toBe(false)
    expect(wrapper.text()).toContain('No data for the selected month')
  })
})
