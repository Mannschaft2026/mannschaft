// CMP-261107-1833 PR3（再招待依頼の受け手側）: 依頼者側の参加待ちダイアログの追補（F1・F4・D2・k）。試練。
// 陣立て書 .claude/handoffs/cmp1833-pr3-plan-20261123.md の j「usePendingInvite と Dialog」。
//  - REISSUE_DECLINED（見送られた）の案内と「閉じる」（POST dismiss → 以後は出ない）
//  - 通知の遷移先 /dashboard?pendingInvite=open: 取り直し、「あとで」を無視してダイアログを出し、その後クエリを消す
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, nextTick, reactive } from 'vue'
import type { components } from '~/types/generated'

type PendingInvite = components['schemas']['PendingInviteResponse']

const loaders = import.meta.glob('./PendingInviteDialog.vue')
const loader = loaders['./PendingInviteDialog.vue']
const PendingInviteDialog = loader
  ? ((await loader()) as { default: ReturnType<typeof defineComponent> }).default
  : defineComponent({ name: 'PendingInviteDialogMissing', render: () => null })

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  navigate: vi.fn(),
  replace: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
  handleApiError: vi.fn(),
}))
const auth = reactive({
  user: null as { id: number } | null,
  accessToken: null as string | null,
  get isAuthenticated() {
    return !!this.user
  },
})
const route = reactive({ path: '/dashboard', query: {} as Record<string, string>, fullPath: '/dashboard' })
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useAuthStore', () => () => auth)
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useRoute', () => () => route)
// Nuxt 内部も useRouter を使うため、フックの登録口は何もしない関数で埋める
mockNuxtImport('useRouter', () => () => ({
  replace: mocks.replace,
  push: vi.fn(),
  afterEach: () => () => {},
  beforeEach: () => () => {},
  beforeResolve: () => () => {},
  onError: () => () => {},
  isReady: () => Promise.resolve(),
  resolve: (to: unknown) => ({ href: String(to) }),
  currentRoute: { value: route },
  options: {},
}))
mockNuxtImport('useNotification', () => () => ({ success: mocks.success, error: mocks.error, info: mocks.info }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) =>
    params ? [key, ...Object.values(params).map(String)].join('|') : key,
  te: () => true,
}))

const options = {
  global: {
    stubs: {
      Dialog: defineComponent({
        props: ['visible', 'header'],
        template:
          '<section v-if="visible" data-testid="pending-invite-dialog"><h2>{{ header }}</h2><slot /><slot name="footer" /></section>',
      }),
      Button: defineComponent({
        props: ['label', 'disabled', 'loading'],
        emits: ['click'],
        template: '<button :disabled="disabled || loading" @click="$emit(\'click\')">{{ label }}</button>',
      }),
      NuxtLink: defineComponent({ props: ['to'], template: '<a :href="String(to)"><slot /></a>' }),
    },
  },
}

const BASE = '/api/v1/me/pending-invite'
const LATER_KEY = 'mannschaft.pendingInvite.later'
const ID = '0193f000-0000-7000-8000-000000000001'
function invite(overrides: Partial<PendingInvite> = {}): PendingInvite {
  return {
    id: ID,
    status: 'PENDING',
    scopeType: 'TEAM',
    scopeName: 'FCマンシャフト',
    roleName: 'MEMBER',
    joinable: false,
    ...overrides,
  } as PendingInvite
}
let current: PendingInvite | null
const q = (id: string) => '[data-testid="' + id + '"]'
function gets() {
  return mocks.api.mock.calls.filter(([url, opts]) => url === BASE && !(opts as { method?: string } | undefined)?.method)
}

async function mountDialog() {
  const wrapper = await mountSuspended(PendingInviteDialog, options)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  localStorage.clear()
  auth.user = { id: 1 }
  auth.accessToken = 'access-1'
  route.query = {}
  route.fullPath = '/dashboard'
  current = invite()
  mocks.api.mockImplementation(async (url: string, opts?: { method?: string }) => {
    const method = opts?.method ?? 'GET'
    if (method === 'GET' && url === BASE) return { data: current }
    if (method === 'POST' && url === BASE + '/dismiss') {
      current = current ? { ...current, status: 'DISMISSED' } as PendingInvite : null
      return { data: null }
    }
    throw new Error('想定外の API 呼び出し: ' + method + ' ' + url)
  })
})

describe('見送られた再招待（REISSUE_DECLINED）の案内（D2・F4）', () => {
  it('PIR-01: REISSUE_DECLINED は専用の案内（見出し・説明）を出し、再招待の依頼ボタンは出さない', async () => {
    current = invite({ status: 'REISSUE_DECLINED' as PendingInvite['status'] })
    const wrapper = await mountDialog()
    expect(wrapper.find(q('pending-invite-dialog')).exists()).toBe(true)
    expect(wrapper.find(q('pending-invite-reissue-declined')).exists()).toBe(true)
    expect(wrapper.text()).toContain('auth.pending_invite.title_reissue_declined')
    expect(wrapper.text()).toContain('auth.pending_invite.reissue_declined_desc')
    expect(wrapper.find(q('pending-invite-reissue')).exists()).toBe(false)
  })

  it('PIR-02 F4: 見送りの案内を閉じると POST dismiss し、以後（次の取得でも）出ない', async () => {
    current = invite({ status: 'REISSUE_DECLINED' as PendingInvite['status'] })
    const wrapper = await mountDialog()
    await wrapper.find(q('pending-invite-dismiss')).trigger('click')
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledWith(BASE + '/dismiss', expect.objectContaining({ method: 'POST' }))
    expect(wrapper.find(q('pending-invite-dialog')).exists()).toBe(false)

    const again = await mountDialog()
    expect(again.find(q('pending-invite-dialog')).exists()).toBe(false)
  })
})

describe('通知からの遷移 ?pendingInvite=open（F1）', () => {
  it('PIR-10: 「あとで」の後でも ?pendingInvite=open なら取り直してダイアログを出し、クエリを消す', async () => {
    sessionStorage.setItem(LATER_KEY, ID)
    route.query = { pendingInvite: 'open' }
    route.fullPath = '/dashboard?pendingInvite=open'
    const wrapper = await mountDialog()
    expect(gets().length).toBeGreaterThanOrEqual(1)
    expect(wrapper.find(q('pending-invite-dialog')).exists()).toBe(true)
    expect(wrapper.find(q('pending-invite-pending')).exists()).toBe(true)
    // クエリを消す: router.replace でも navigateTo(…, { replace: true }) でもよい
    const cleared = [...mocks.replace.mock.calls, ...mocks.navigate.mock.calls]
    expect(cleared.length, 'クエリ pendingInvite を消していない').toBeGreaterThan(0)
    const arg = cleared.at(-1)?.[0] as { query?: Record<string, unknown> } | string
    const rest = typeof arg === 'string' ? arg : JSON.stringify(arg?.query ?? {})
    expect(rest).not.toContain('pendingInvite')
  })

  it('PIR-11: 表示中（取得済み）に ?pendingInvite=open へ遷移したら取り直す（再招待後の新しい PENDING を出す）', async () => {
    current = invite({ status: 'REISSUE_REQUESTED' as PendingInvite['status'] })
    const wrapper = await mountDialog()
    const before = gets().length
    current = invite({ status: 'PENDING' })
    route.query = { pendingInvite: 'open' }
    route.fullPath = '/dashboard?pendingInvite=open'
    await nextTick()
    await flushPromises()
    expect(gets().length).toBeGreaterThan(before)
    expect(wrapper.find(q('pending-invite-pending')).exists()).toBe(true)
  })

  it('PIR-13 P2-4: 取得中に通知（?pendingInvite=open）を押しても、その取得の完了後に必ず取り直し、取り直しの後でクエリを消す', async () => {
    let release!: () => void
    mocks.api.mockImplementationOnce(() => new Promise((resolve) => {
      release = () => resolve({ data: invite({ status: 'REISSUE_REQUESTED' as PendingInvite['status'] }) })
    }))
    const wrapper = await mountDialog()
    current = invite({ status: 'PENDING' })
    route.query = { pendingInvite: 'open' }
    route.fullPath = '/dashboard?pendingInvite=open'
    await nextTick()
    await flushPromises()
    // 取得が終わるまでクエリは消さない
    expect(mocks.replace.mock.calls.filter(([to]) => typeof to !== 'string')).toEqual([])
    release()
    await flushPromises()
    expect(gets().length).toBe(2)
    expect(wrapper.find(q('pending-invite-pending')).exists()).toBe(true)
    expect(wrapper.find(q('pending-invite-reissue-requested')).exists()).toBe(false)
    expect(mocks.replace.mock.calls.filter(([to]) => typeof to !== 'string').length).toBeGreaterThan(0)
  })

  it('PIR-14 P3: 取得中に user が更新されて再入しても、取り直しは 1 回・クエリ消去は 1 回で、取り直しの完了後', async () => {
    let release!: () => void
    mocks.api.mockImplementationOnce(() => new Promise((resolve) => {
      release = () => resolve({ data: invite({ status: 'REISSUE_REQUESTED' as PendingInvite['status'] }) })
    }))
    const wrapper = await mountDialog()
    current = invite({ status: 'PENDING' })
    route.query = { pendingInvite: 'open' }
    route.fullPath = '/dashboard?pendingInvite=open'
    await nextTick()
    auth.user = { id: 1 }
    await nextTick()
    await flushPromises()
    expect(mocks.replace.mock.calls.filter(([to]) => typeof to !== 'string')).toEqual([])
    release()
    await flushPromises()
    expect(gets().length).toBe(2)
    expect(wrapper.find(q('pending-invite-pending')).exists()).toBe(true)
    expect(mocks.replace.mock.calls.filter(([to]) => typeof to !== 'string')).toHaveLength(1)
  })

  it('PIR-12: クエリが無ければ「あとで」の記録どおり出さない（従来どおり）', async () => {
    sessionStorage.setItem(LATER_KEY, ID)
    const wrapper = await mountDialog()
    expect(wrapper.find(q('pending-invite-dialog')).exists()).toBe(false)
    // mountSuspended 自身が router.replace('/')（文字列）を呼ぶため、クエリを消す呼び出し（オブジェクト）だけを見る
    expect(mocks.replace.mock.calls.filter(([to]) => typeof to !== 'string')).toEqual([])
  })
})

describe('依頼済みの文言（k）', () => {
  it('PIR-20: REISSUE_REQUESTED は依頼済みの表示（auth.pending_invite.reissue_requested）', async () => {
    current = invite({ status: 'REISSUE_REQUESTED' as PendingInvite['status'] })
    const wrapper = await mountDialog()
    expect(wrapper.find(q('pending-invite-reissue-requested')).text()).toContain('auth.pending_invite.reissue_requested')
  })
})
