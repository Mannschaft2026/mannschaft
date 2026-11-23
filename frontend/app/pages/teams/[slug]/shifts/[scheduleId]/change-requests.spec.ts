// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import PrimeVue from 'primevue/config'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Textarea from 'primevue/textarea'
import Page from './change-requests.vue'
import PageHeader from '~/components/PageHeader.vue'
import ShiftChangeRequestList from '~/components/shift/ShiftChangeRequestList.vue'
import type { ChangeRequest, ChangeRequestStatus } from '~/types/shift'

// ページ・権限composable・変更依頼API・子一覧は実物。通信境界だけ応答を差し替える。
const mocks = vi.hoisted(() => ({ api: vi.fn(), handleApiError: vi.fn(), error: vi.fn(), success: vi.fn() }))
const params = reactive({ slug: 'team-a', scheduleId: '425' })
const auth = reactive({ user: { id: 1, systemRole: 'MEMBER' }, get currentUser() { return this.user } })
mockNuxtImport('useRoute', () => () => ({ params }))
mockNuxtImport('useAuthStore', () => () => auth)
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useNotification', () => () => ({ error: mocks.error, success: mocks.success }))

function request(status: ChangeRequestStatus = 'OPEN', id = 7): ChangeRequest {
  return {
    id, scheduleId: 425, slotId: null,
    requestInfo: { requestedBy: 2, requestType: 'PRE_CONFIRM_EDIT', reason: 'reason' },
    reviewInfo: { status, reviewerId: null, reviewComment: null, reviewedAt: null },
    timing: { createdAt: '2026-10-27T12:00:00', expiresAt: null },
    version: 0,
  }
}
function permissions(roleName: string | null, scopeAdmin = false) {
  return { data: { roleName, scopeAdmin, permissions: [] } }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
let role: string | null
let rows: ChangeRequest[]
let permissionError: boolean
let listError: boolean
let membersError: boolean
let reviewFailure: unknown
let wrapper: VueWrapper | undefined
function respond(url: string, options?: { body?: { decision: ChangeRequestStatus } }) {
  if (url.endsWith('/me/permissions')) {
    return permissionError ? Promise.reject(new Error('permissions unavailable')) : Promise.resolve(permissions(role, true))
  }
  // PR 版の page が呼ぶシフト表・チーム取得（チーム一致検証）。teamId と numericId を一致させる。
  if (/\/shifts\/schedules\/\d+$/.test(url)) return Promise.resolve({ data: { id: 425, teamId: 12, status: { status: 'COLLECTING' } } })
  if (/\/api\/v1\/teams\/[^/]+$/.test(url)) return Promise.resolve({ data: { numericId: 12 } })
  if (url.endsWith('/members/all')) {
    return membersError ? Promise.reject(new Error('members unavailable')) : Promise.resolve({ data: [{ userId: 2, displayName: '山田花子' }] })
  }
  if (url.includes('/review')) {
    if (reviewFailure) return Promise.reject(reviewFailure)
    return Promise.resolve({ data: request(options!.body!.decision) })
  }
  if (url.includes('/change-requests?')) {
    return listError ? Promise.reject(new Error('list unavailable')) : Promise.resolve({ data: rows })
  }
  throw new Error(`Unexpected API: ${url}`)
}
function renderPage() {
  wrapper = mount(Page, { global: {
    plugins: [[PrimeVue, { unstyled: true }]],
    components: { ShiftChangeRequestList, DataTable, Column, Button },
    mocks: { $t: (key: string) => key },
    stubs: {
      ShiftChangeRequestForm: true, PageHeader: true,
      SectionCard: { template: '<section><slot /></section>' },
      NuxtLink: { template: '<a><slot /></a>' },
      Tag: true, Textarea: true, PageLoading: true,
      DashboardErrorState: { template: '<div data-testid="change-request-load-error" />' },
      Dialog: { props: ['visible'], template: '<div v-if="visible" data-testid="review-dialog"><slot /><slot name="footer" /></div>' },
    },
  } })
  return wrapper
}
function reviewButtons() {
  // 広幅の表と狭幅のカードに同じ操作が重複して出るので、表の側だけ数える
  return wrapper!.findAll('[data-testid="change-request-table"] button').filter((button) =>
    ['shift.changeRequest.approve', 'shift.changeRequest.reject'].includes(button.text()))
}

beforeEach(() => {
  vi.clearAllMocks()
  params.slug = 'team-a'
  params.scheduleId = '425'
  auth.user = { id: 1, systemRole: 'MEMBER' }
  role = 'ADMIN'
  rows = [request()]
  permissionError = false
  listError = false
  membersError = false
  reviewFailure = undefined
  mocks.api.mockImplementation(respond)
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined })

describe('変更依頼一覧のメンバー表示名', () => {
  it('生の userId でなく表示名を出し、解決できない依頼者は不明なメンバー', async () => {
    rows = [request('OPEN', 7), { ...request('OPEN', 8), requestInfo: { requestedBy: 99, requestType: 'PRE_CONFIRM_EDIT', reason: 'r' } }]
    renderPage()
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/teams/team-a/members/all')
    expect(wrapper!.text()).toContain('山田花子')
    expect(wrapper!.text()).toContain('shift.changeRequest.unknownRequester')
  })
  it('members/all の取得失敗は handleApiError に渡し、不明なメンバー表示にする', async () => {
    membersError = true
    renderPage()
    await flushPromises()
    expect(mocks.handleApiError).toHaveBeenCalled()
    expect(wrapper!.text()).toContain('shift.changeRequest.unknownRequester')
  })
})

describe('変更依頼のチーム権限による審査UI（CMP1432-A）', () => {
  it.each(['ACCEPTED', 'REJECTED'] as const)('AC1 純チームADMINが%sを画面から送信できる', async (decision) => {
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(2)
    await reviewButtons()[decision === 'ACCEPTED' ? 0 : 1]!.trigger('click')
    const confirm = wrapper!.get('[data-testid="review-dialog"]').findAll('button').at(-1)!
    await confirm.trigger('click')
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/shifts/change-requests/7/review', {
      method: 'PATCH', body: { decision, reviewComment: undefined, version: 0 },
    })
  })

  it('審査ダイアログのコメントと対象依頼の version を review に渡す', async () => {
    rows = [{ ...request(), version: 3 }]
    renderPage()
    await flushPromises()
    await reviewButtons()[0]!.trigger('click')
    wrapper!.getComponent(Textarea).vm.$emit('update:modelValue', '承認します')
    await wrapper!.get('[data-testid="review-dialog"]').findAll('button').at(-1)!.trigger('click')
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/shifts/change-requests/7/review', {
      method: 'PATCH', body: { decision: 'ACCEPTED', reviewComment: '承認します', version: 3 },
    })
  })

  it('390px のカードで操作列に届くよう DataTable は横スクロール可能', async () => {
    renderPage()
    await flushPromises()
    const table = wrapper!.findComponent(DataTable)
    expect(table.props('scrollable')).toBe(true)
  })

  it('AC2 DEPUTY_ADMINも審査ボタンを表示する', async () => {
    role = 'DEPUTY_ADMIN'
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(2)
  })

  it.each(['MEMBER', 'SUPPORTER', 'GUEST', null, 'SYSTEM_ADMIN'])('AC2 %sはscopeAdmin=trueでも表示しない（既存UI契約）', async (currentRole) => {
    role = currentRole
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC3 チーム切替で旧ADMINを即時破棄し、他チームMEMBERには表示しない', async () => {
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(2)
    const next = deferred<ReturnType<typeof permissions>>()
    mocks.api.mockImplementation((url: string) => url.endsWith('/me/permissions') ? next.promise : respond(url))
    params.slug = 'team-b'
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/teams/team-b/me/permissions')
    next.resolve(permissions('MEMBER'))
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC3 チーム切替後に旧ADMIN応答が遅れても復活しない', async () => {
    const old = deferred<ReturnType<typeof permissions>>()
    mocks.api.mockImplementation((url: string) => url.includes('/team-a/me/permissions') ? old.promise : respond(url))
    renderPage()
    await flushPromises()
    role = 'MEMBER'
    params.slug = 'team-b'
    await flushPromises()
    old.resolve(permissions('ADMIN'))
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC4 本人切替で旧ADMINを破棄し、新本人のMEMBER応答に従う', async () => {
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(2)
    const next = deferred<ReturnType<typeof permissions>>()
    mocks.api.mockImplementation((url: string) => url.endsWith('/me/permissions') ? next.promise : respond(url))
    auth.user = { id: 3, systemRole: 'MEMBER' }
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
    next.resolve(permissions('MEMBER'))
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC5 権限未解決では表示せず、取得失敗でも表示しない（既存契約）', async () => {
    const pending = deferred<ReturnType<typeof permissions>>()
    mocks.api.mockImplementation((url: string) => url.endsWith('/me/permissions') ? pending.promise : respond(url))
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
    wrapper!.unmount()
    permissionError = true
    mocks.api.mockImplementation(respond)
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
    pending.resolve(permissions('ADMIN'))
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it.each(['ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED'] as const)('AC5 %s済み依頼に表示しない（既存契約）', async (status) => {
    rows = [request(status)]
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC5 空一覧では表示しない（既存契約）', async () => {
    rows = []
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC6 一覧GET失敗は失敗を利用者に見せ（取得エラー表示。旧: 通知）、審査操作を出さない', async () => {
    listError = true
    renderPage()
    await flushPromises()
    expect(wrapper!.find('[data-testid="change-request-load-error"]').exists()).toBe(true)
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC7 一覧の件数によらず初回の権限取得は1本', async () => {
    rows = [request('OPEN', 7), request('OPEN', 8), request('OPEN', 9)]
    renderPage()
    await flushPromises()
    expect(mocks.api.mock.calls.filter(([url]) => String(url).endsWith('/me/permissions')))
      .toEqual([['/api/v1/teams/team-a/me/permissions']])
  })
})

describe('変更依頼ページ: 戻る導線', () => {
  it('戻る導線は PageHeader の1つだけで、シフト一覧へ戻る', async () => {
    renderPage()
    await flushPromises()
    // 手書きの矢印リンクは持たない（戻る導線は PageHeader の back-to に一本化）
    expect(wrapper!.findAll('.pi-arrow-left')).toHaveLength(0)
    expect(wrapper!.findAll('a')).toHaveLength(0)
    expect(wrapper!.findComponent(PageHeader).props('backTo')).toBe('/teams/team-a/shifts')
  })
})

describe('審査の失敗・競合時の扱い', () => {
  const unhandled: unknown[] = []
  const onUnhandled = (e: unknown) => { unhandled.push(e) }
  beforeEach(() => { unhandled.length = 0; process.on('unhandledRejection', onUnhandled) })
  afterEach(() => { process.off('unhandledRejection', onUnhandled) })

  async function openAndSubmit(comment: string) {
    renderPage()
    await flushPromises()
    await reviewButtons()[0]!.trigger('click')
    wrapper!.getComponent(Textarea).vm.$emit('update:modelValue', comment)
    await wrapper!.get('[data-testid="review-dialog"]').findAll('button').at(-1)!.trigger('click')
    await flushPromises()
  }

  it('成功時はダイアログを閉じる', async () => {
    await openAndSubmit('承認します')
    expect(wrapper!.find('[data-testid="review-dialog"]').exists()).toBe(false)
  })

  it('失敗（500）では未処理の例外を出さず、ダイアログと入力コメントを残す', async () => {
    reviewFailure = { statusCode: 500 }
    await openAndSubmit('承認します')
    expect(unhandled).toHaveLength(0)
    expect(mocks.handleApiError).toHaveBeenCalledTimes(1)
    expect(mocks.error).not.toHaveBeenCalled()
    expect(wrapper!.find('[data-testid="review-dialog"]').exists()).toBe(true)
    expect(wrapper!.get('textarea-stub').attributes('modelvalue')).toBe('承認します')
  })

  it('409 で依頼が審査済みになったら、未処理例外なくダイアログを閉じ、競合文言は1回だけ', async () => {
    reviewFailure = { statusCode: 409, data: { error: { code: 'SHIFT_031' } } }
    renderPage()
    await flushPromises()
    await reviewButtons()[0]!.trigger('click')
    rows = [request('ACCEPTED')]
    await wrapper!.get('[data-testid="review-dialog"]').findAll('button').at(-1)!.trigger('click')
    await flushPromises()
    expect(unhandled).toHaveLength(0)
    expect(mocks.error).toHaveBeenCalledWith('shift.changeRequest.reviewConflict')
    expect(mocks.error).toHaveBeenCalledTimes(1)
    expect(wrapper!.find('[data-testid="review-dialog"]').exists()).toBe(false)
  })

  it('SHIFT_018 後は最新の version に差し替わり、再送が成功してダイアログが閉じる', async () => {
    reviewFailure = { statusCode: 409, data: { error: { code: 'SHIFT_018' } } }
    renderPage()
    await flushPromises()
    await reviewButtons()[0]!.trigger('click')
    wrapper!.getComponent(Textarea).vm.$emit('update:modelValue', '再送します')
    rows = [{ ...request(), version: 5 }]
    await wrapper!.get('[data-testid="review-dialog"]').findAll('button').at(-1)!.trigger('click')
    await flushPromises()
    expect(wrapper!.find('[data-testid="review-dialog"]').exists()).toBe(true)
    reviewFailure = undefined
    await wrapper!.get('[data-testid="review-dialog"]').findAll('button').at(-1)!.trigger('click')
    await flushPromises()
    expect(mocks.api).toHaveBeenLastCalledWith('/api/v1/shifts/change-requests/7/review', {
      method: 'PATCH', body: { decision: 'ACCEPTED', reviewComment: '再送します', version: 5 },
    })
    expect(wrapper!.find('[data-testid="review-dialog"]').exists()).toBe(false)
  })

  it('409 でも依頼がまだ OPEN（楽観ロック競合）なら、ダイアログとコメントを残す', async () => {
    reviewFailure = { statusCode: 409, data: { error: { code: 'SHIFT_018' } } }
    await openAndSubmit('保持して')
    expect(unhandled).toHaveLength(0)
    expect(wrapper!.find('[data-testid="review-dialog"]').exists()).toBe(true)
    expect(wrapper!.get('textarea-stub').attributes('modelvalue')).toBe('保持して')
  })
})

describe('申請者への審査結果の表示', () => {
  it('審査コメントと審査日時を行の下に表示する', async () => {
    role = 'MEMBER'
    rows = [{ ...request('REJECTED'), reviewInfo: { status: 'REJECTED', reviewerId: 9, reviewComment: '人数が足りません', reviewedAt: '2026-10-28T09:30:00' } }]
    renderPage()
    await flushPromises()
    const el = wrapper!.get('[data-testid="change-request-review-7"]')
    expect(el.text()).toContain('人数が足りません')
    expect(el.text()).toContain('shift.changeRequest.reviewedAt')
  })
  it('未審査の依頼には審査結果欄を出さない', async () => {
    renderPage()
    await flushPromises()
    expect(wrapper!.find('[data-testid="change-request-review-7"]').exists()).toBe(false)
  })
})
