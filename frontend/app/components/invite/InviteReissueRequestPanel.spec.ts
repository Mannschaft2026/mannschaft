// CMP-261107-1833 PR3（再招待依頼の受け手側）: 招待管理画面の「再招待の依頼」欄（InviteReissueRequestPanel）の受け入れテスト（試練）。
// 陣立て書 .claude/handoffs/cmp1833-pr3-plan-20261123.md の j・l。SectionCard・id=reissue-requests。表示名は補間のみ。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent } from 'vue'

const loaders = import.meta.glob('./InviteReissueRequestPanel.vue')
const loader = loaders['./InviteReissueRequestPanel.vue']
const Panel = loader
  ? ((await loader()) as { default: ReturnType<typeof defineComponent> }).default
  : defineComponent({ name: 'InviteReissueRequestPanelMissing', render: () => null })

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
  handleApiError: vi.fn(),
  confirm: vi.fn(),
}))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useNotification', () => () => ({ success: mocks.success, info: mocks.info, warn: mocks.warn, error: mocks.error }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useConfirm', () => () => ({ require: mocks.confirm }))
mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) => (params ? key + ':' + Object.values(params).map(String).join('|') : key),
  te: () => true,
}))
mockNuxtImport('useDatetime', () => () => ({
  formatDate: (v: string) => v,
  formatDateTime: (v: string) => v,
}))

const stubs = {
  SectionCard: defineComponent({
    props: ['title'],
    template: '<section v-bind="$attrs"><h2>{{ title }}</h2><slot /><slot name="actions" /></section>',
  }),
  Button: defineComponent({
    props: ['label', 'loading', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled || loading" @click="$emit(\'click\')">{{ label }}</button>',
  }),
  LoadingBounce: true,
  Avatar: true,
}

function row(id: string, name = '依頼者' + id) {
  return { id, requesterDisplayName: name, requesterAvatarUrl: null, requestedAt: '2026-11-20T00:00:00Z', roleName: 'MEMBER' }
}
function page(content: ReturnType<typeof row>[], totalElements = content.length) {
  return { data: { content, totalElements, totalPages: Math.ceil(totalElements / 20), number: 0, size: 20 } }
}
function apiError(code: string, statusCode: number) {
  return Object.assign(new Error(code), { statusCode, status: statusCode, data: { error: { code } } })
}
const LIST = '/api/v1/teams/my-team/invite-reissue-requests'
const q = (id: string) => '[data-testid="' + id + '"]'

async function mountPanel() {
  const wrapper = await mountSuspended(Panel, { props: { scopeType: 'team', slug: 'my-team' }, global: { stubs } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.confirm.mockImplementation((opts: { accept?: () => void }) => opts.accept?.())
})

describe('InviteReissueRequestPanel', () => {
  it('IRP-00: components/invite/InviteReissueRequestPanel.vue が存在する', () => {
    expect(loader, 'app/components/invite/InviteReissueRequestPanel.vue が未実装').toBeDefined()
  })

  it('IRP-01: 欄のアンカーは id=reissue-requests（通知・集約の遷移先 …/invites#reissue-requests）', async () => {
    mocks.api.mockResolvedValueOnce(page([]))
    const wrapper = await mountPanel()
    expect(wrapper.find('#reissue-requests').exists()).toBe(true)
    expect(wrapper.text()).toContain('inviteReissue.title')
  })

  it('IRP-02 L1: 0 件なら空の表示', async () => {
    mocks.api.mockResolvedValueOnce(page([]))
    const wrapper = await mountPanel()
    expect(mocks.api).toHaveBeenCalledWith(LIST + '?page=0&size=20')
    expect(wrapper.find(q('invite-reissue-empty')).exists()).toBe(true)
    expect(wrapper.findAll(q('invite-reissue-row'))).toHaveLength(0)
  })

  it('IRP-03: 各行に表示名（補間のみ・HTML として解釈しない）・依頼日時・付与ロールと、再招待・見送りのボタン', async () => {
    mocks.api.mockResolvedValueOnce(page([row('a', '<b>山田</b>')]))
    const wrapper = await mountPanel()
    const rows = wrapper.findAll(q('invite-reissue-row'))
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain('<b>山田</b>')
    expect(rows[0].find('b').exists()).toBe(false)
    expect(rows[0].text()).toContain('2026-11-20T00:00:00Z')
    expect(rows[0].text()).toMatch(/MEMBER|role\.MEMBER/)
    expect(rows[0].find(q('invite-reissue-reissue')).exists()).toBe(true)
    expect(rows[0].find(q('invite-reissue-decline')).exists()).toBe(true)
  })

  it('IRP-04: 「再招待する」で POST …/{id}/reissue', async () => {
    mocks.api.mockResolvedValueOnce(page([row('a')]))
    const wrapper = await mountPanel()
    mocks.api.mockResolvedValueOnce({ data: { status: 'REISSUED' } })
    mocks.api.mockResolvedValueOnce(page([]))
    await wrapper.find(q('invite-reissue-reissue')).trigger('click')
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledWith(LIST + '/a/reissue', expect.objectContaining({ method: 'POST' }))
  })

  it('IRP-05: 「見送る」は確認（useConfirm）を経て POST …/{id}/decline。確認で取り消せば送らない', async () => {
    mocks.api.mockResolvedValueOnce(page([row('a')]))
    const wrapper = await mountPanel()
    mocks.confirm.mockImplementationOnce(() => undefined)
    await wrapper.find(q('invite-reissue-decline')).trigger('click')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledTimes(1)
    expect(mocks.api.mock.calls.some(([url]) => String(url).endsWith('/decline'))).toBe(false)

    mocks.api.mockResolvedValueOnce({ data: { status: 'REISSUE_DECLINED' } })
    mocks.api.mockResolvedValueOnce(page([]))
    await wrapper.find(q('invite-reissue-decline')).trigger('click')
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledWith(LIST + '/a/decline', expect.objectContaining({ method: 'POST' }))
  })

  it('IRP-06: 取得失敗はエラー表示と再試行（空の表示は出さない）', async () => {
    mocks.api.mockRejectedValueOnce(apiError('COMMON_999', 500))
    const wrapper = await mountPanel()
    expect(wrapper.find(q('invite-reissue-empty')).exists()).toBe(false)
    expect(wrapper.find(q('invite-reissue-error')).exists()).toBe(true)
    mocks.api.mockResolvedValueOnce(page([row('a')]))
    await wrapper.find(q('invite-reissue-retry')).trigger('click')
    await flushPromises()
    expect(wrapper.findAll(q('invite-reissue-row'))).toHaveLength(1)
  })

  it('IRP-07 L2: 21 件目以降は「もっと見る」で追加取得する', async () => {
    mocks.api.mockResolvedValueOnce(page(Array.from({ length: 20 }, (_, i) => row('r' + i)), 21))
    const wrapper = await mountPanel()
    expect(wrapper.findAll(q('invite-reissue-row'))).toHaveLength(20)
    mocks.api.mockResolvedValueOnce({ data: { content: [row('r20')], totalElements: 21, totalPages: 2, number: 1, size: 20 } })
    await wrapper.find(q('invite-reissue-load-more')).trigger('click')
    await flushPromises()
    expect(mocks.api).toHaveBeenLastCalledWith(LIST + '?page=1&size=20')
    expect(wrapper.findAll(q('invite-reissue-row'))).toHaveLength(21)
    expect(wrapper.find(q('invite-reissue-load-more')).exists()).toBe(false)
  })

  it('IRP-08 R4: 操作が 403 なら欄を閉じる（行もボタンも消える）', async () => {
    mocks.api.mockResolvedValueOnce(page([row('a')]))
    const wrapper = await mountPanel()
    mocks.api.mockRejectedValueOnce(apiError('COMMON_002', 403))
    await wrapper.find(q('invite-reissue-reissue')).trigger('click')
    await flushPromises()
    expect(wrapper.findAll(q('invite-reissue-row'))).toHaveLength(0)
    expect(wrapper.find(q('invite-reissue-reissue')).exists()).toBe(false)
  })

  it('IRP-09 C3: 処理中の行のボタンは押せない（二重押しを防ぐ）', async () => {
    mocks.api.mockResolvedValueOnce(page([row('a')]))
    const wrapper = await mountPanel()
    mocks.api.mockImplementationOnce(() => new Promise(() => {}))
    await wrapper.find(q('invite-reissue-reissue')).trigger('click')
    await flushPromises()
    expect(wrapper.find(q('invite-reissue-reissue')).attributes('disabled')).toBeDefined()
    expect(wrapper.find(q('invite-reissue-decline')).attributes('disabled')).toBeDefined()
  })

  it('IRP-10 U2: 行は狭い幅で 2 段組み、ボタンは全幅・タップ領域 44px 以上', async () => {
    mocks.api.mockResolvedValueOnce(page([row('a')]))
    const wrapper = await mountPanel()
    const r = wrapper.find(q('invite-reissue-row'))
    expect(r.classes().join(' ')).toMatch(/flex-col/)
    expect(r.classes().join(' ')).toMatch(/sm:flex-row/)
    for (const id of ['invite-reissue-reissue', 'invite-reissue-decline']) {
      const cls = r.find(q(id)).classes().join(' ')
      expect(cls, id).toMatch(/\bw-full\b/)
      expect(cls, id).toMatch(/min-h-(11|12|\[44px\]|\[48px\])/)
    }
  })
})
