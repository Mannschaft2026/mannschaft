// CMP-261107-1833 PR2（AC13）: ログイン後の参加待ち招待ダイアログの受け入れテスト（試練）。
// 設計正本 §6・§9-1・§9-4。API は /api/v1/me/pending-invite（GET / POST accept・decline・reissue-request・dismiss）。
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, nextTick, reactive } from 'vue'
import type { components } from '~/types/generated'

type PendingInvite = components['schemas']['PendingInviteResponse']

// 未実装の間も import 解決エラーでファイルごと落とさず、各 AC の期待で red にする。
// 実装後は ./PendingInviteDialog.vue がそのまま読み込まれる。
const loaders = import.meta.glob('./PendingInviteDialog.vue')
const loader = loaders['./PendingInviteDialog.vue']
const PendingInviteDialog = loader
  ? ((await loader()) as { default: ReturnType<typeof defineComponent> }).default
  : defineComponent({ name: 'PendingInviteDialogMissing', render: () => null })

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  navigate: vi.fn(),
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
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useAuthStore', () => () => auth)
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useNotification', () => () => ({
  success: mocks.success,
  error: mocks.error,
  info: mocks.info,
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
// 引数の値（参加先名など）も表示に出るよう、キーと引数を連結して返す
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
        emits: ['update:visible'],
        template:
          '<section v-if="visible" data-testid="pending-invite-dialog"><h2>{{ header }}</h2><slot /><slot name="footer" /></section>',
      }),
      Button: defineComponent({
        props: ['label', 'disabled', 'loading'],
        emits: ['click'],
        template: '<button :disabled="disabled || loading" @click="$emit(\'click\')">{{ label }}</button>',
      }),
      NuxtLink: defineComponent({
        name: 'NuxtLink',
        props: ['to'],
        template: '<a :href="String(to)"><slot /></a>',
      }),
    },
  },
}

const BASE = '/api/v1/me/pending-invite'
const MIN = 60_000
function invite(overrides: Partial<PendingInvite> = {}): PendingInvite {
  return {
    id: '0193f000-0000-7000-8000-000000000001',
    status: 'PENDING',
    scopeType: 'TEAM',
    scopeName: 'FCマンシャフト',
    roleName: 'MEMBER',
    joinable: false,
    publicPath: undefined,
    reissueRequestedAt: undefined,
    retryAvailableAt: undefined,
    ...overrides,
  } as PendingInvite
}
function apiError(code: string, statusCode: number) {
  return Object.assign(new Error(code), { statusCode, data: { error: { code } } })
}

// "METHOD url" ごとの応答。GET は現在値を返す（承認後に取り直す実装も、取り直さない実装も通す）
let routes: Record<string, () => Promise<unknown>>
let current: PendingInvite | null
function route(method: string, path: string, handler: () => Promise<unknown>) {
  routes[method + ' ' + path] = handler
}
function calls(method: string, path: string) {
  return mocks.api.mock.calls.filter(
    ([url, opts]) => url === path && ((opts as { method?: string } | undefined)?.method ?? 'GET') === method,
  )
}
const q = (id: string) => '[data-testid="' + id + '"]'
const VIEWS = ['pending-invite-pending', 'pending-invite-expired', 'pending-invite-accepting', 'pending-invite-unavailable']

async function mountDialog() {
  const wrapper = await mountSuspended(PendingInviteDialog, options)
  await flushPromises()
  return wrapper
}
function shownViews(wrapper: Awaited<ReturnType<typeof mountDialog>>) {
  return VIEWS.filter((id) => wrapper.find(q(id)).exists())
}

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  auth.user = { id: 1 }
  auth.accessToken = 'access-1'
  current = invite()
  routes = {}
  route('GET', BASE, async () => ({ data: current }))
  mocks.api.mockImplementation((url: string, opts?: { method?: string }) => {
    const handler = routes[(opts?.method ?? 'GET') + ' ' + url]
    if (!handler) throw new Error('想定外の API 呼び出し: ' + (opts?.method ?? 'GET') + ' ' + url)
    return handler()
  })
})

describe('PendingInviteDialog の配置', () => {
  it('PID-00: components/auth/PendingInviteDialog.vue が存在する', () => {
    expect(loader, 'app/components/auth/PendingInviteDialog.vue が未実装').toBeDefined()
  })
  it('PID-00b: layouts/default.vue の ClientOnly 内（既存バナー群の横）に PendingInviteDialog を置く', () => {
    const layout = readFileSync(resolve(__dirname, '../../layouts/default.vue'), 'utf8')
    expect(layout).toMatch(/<ClientOnly>[\s\S]*<PendingInviteDialog\b[\s\S]*<\/ClientOnly>/)
  })
})

describe('取得の契機（§9-1）', () => {
  it('PID-01: 未ログイン（authStore.user なし）では取得しない。setUser されたら取得して表示する', async () => {
    auth.user = null
    auth.accessToken = null
    const wrapper = await mountDialog()
    expect(calls('GET', BASE)).toHaveLength(0)
    auth.user = { id: 1 }
    auth.accessToken = 'access-1'
    await nextTick()
    await flushPromises()
    expect(calls('GET', BASE)).toHaveLength(1)
    expect(wrapper.find(q('pending-invite-pending')).exists()).toBe(true)
  })
  it('PID-02: 取得に成功したらアクセストークンが変わっても取り直さない（ログイン後1回）', async () => {
    await mountDialog()
    expect(calls('GET', BASE)).toHaveLength(1)
    auth.accessToken = 'access-2'
    await nextTick()
    await flushPromises()
    expect(calls('GET', BASE)).toHaveLength(1)
  })
  it('PID-03: AUTH_070（保護者同意待ち）は何も表示せずエラー扱いもせず、次のトークン変化で取り直す', async () => {
    route('GET', BASE, async () => {
      throw apiError('AUTH_070', 403)
    })
    const wrapper = await mountDialog()
    expect(calls('GET', BASE)).toHaveLength(1)
    expect(shownViews(wrapper)).toEqual([])
    expect(mocks.handleApiError).not.toHaveBeenCalled()
    expect(mocks.error).not.toHaveBeenCalled()
    // 保護者の承認後にトークンが更新された
    route('GET', BASE, async () => ({ data: current }))
    auth.accessToken = 'access-after-consent'
    await nextTick()
    await flushPromises()
    expect(calls('GET', BASE)).toHaveLength(2)
    expect(wrapper.find(q('pending-invite-pending')).exists()).toBe(true)
  })
  it('PID-04: AUTH_070 以外の取得失敗は握りつぶさず共通エラーハンドラへ渡す', async () => {
    route('GET', BASE, async () => {
      throw apiError('COMMON_001', 500)
    })
    const wrapper = await mountDialog()
    expect(mocks.handleApiError).toHaveBeenCalledTimes(1)
    expect(shownViews(wrapper)).toEqual([])
  })
  it('PID-05: data が null なら何も表示しない', async () => {
    current = null
    const wrapper = await mountDialog()
    expect(calls('GET', BASE)).toHaveLength(1)
    expect(wrapper.find(q('pending-invite-dialog')).exists()).toBe(false)
    expect(shownViews(wrapper)).toEqual([])
  })
  it.each(['JOINED', 'DECLINED', 'DISMISSED', 'DISCARDED'])(
    'PID-06: 終了状態 %s の保留は表示しない（GET は終了行も返す）',
    async (status) => {
      current = invite({ status })
      const wrapper = await mountDialog()
      expect(calls('GET', BASE)).toHaveLength(1)
      expect(shownViews(wrapper)).toEqual([])
    },
  )
})

describe('PENDING: 参加しますか？の3択（§6）', () => {
  it('PID-10: 参加先名を示し「参加する／断る／あとで」の3つを出す', async () => {
    const wrapper = await mountDialog()
    const view = wrapper.find(q('pending-invite-pending'))
    expect(view.exists()).toBe(true)
    expect(wrapper.text()).toContain('FCマンシャフト')
    expect(wrapper.find(q('pending-invite-accept')).exists()).toBe(true)
    expect(wrapper.find(q('pending-invite-decline')).exists()).toBe(true)
    expect(wrapper.find(q('pending-invite-later')).exists()).toBe(true)
  })
  it('PID-11: 参加する → POST accept が JOINED なら成功を伝えてダッシュボードへ遷移し、閉じる', async () => {
    route('POST', BASE + '/accept', async () => {
      current = invite({ status: 'JOINED' })
      return { data: { status: 'JOINED' } }
    })
    const wrapper = await mountDialog()
    await wrapper.find(q('pending-invite-accept')).trigger('click')
    await flushPromises()
    expect(calls('POST', BASE + '/accept')).toHaveLength(1)
    expect(mocks.success).toHaveBeenCalledTimes(1)
    expect(mocks.navigate).toHaveBeenCalledWith('/dashboard')
    expect(shownViews(wrapper)).toEqual([])
  })
  it('PID-12: 参加処理中は参加ボタンを無効にし、連打しても POST accept は1回', async () => {
    let resolveAccept: (v: unknown) => void = () => {}
    route('POST', BASE + '/accept', () => new Promise((r) => (resolveAccept = r)))
    const wrapper = await mountDialog()
    const button = wrapper.find(q('pending-invite-accept'))
    await button.trigger('click')
    await button.trigger('click')
    await flushPromises()
    expect(calls('POST', BASE + '/accept')).toHaveLength(1)
    resolveAccept({ data: { status: 'JOINED' } })
    await flushPromises()
  })
  it('PID-13: 断る → POST decline して閉じる（参加 API は呼ばない）', async () => {
    route('POST', BASE + '/decline', async () => {
      current = invite({ status: 'DECLINED' })
      return { data: null }
    })
    const wrapper = await mountDialog()
    await wrapper.find(q('pending-invite-decline')).trigger('click')
    await flushPromises()
    expect(calls('POST', BASE + '/decline')).toHaveLength(1)
    expect(calls('POST', BASE + '/accept')).toHaveLength(0)
    expect(shownViews(wrapper)).toEqual([])
  })
  it('PID-14: あとで → API を呼ばず閉じ、同じセッション中は再表示しない（sessionStorage に保留 ID を記録）', async () => {
    const wrapper = await mountDialog()
    await wrapper.find(q('pending-invite-later')).trigger('click')
    await flushPromises()
    expect(mocks.api.mock.calls.filter(([, o]) => (o as { method?: string } | undefined)?.method === 'POST')).toEqual([])
    expect(shownViews(wrapper)).toEqual([])
    expect(sessionStorage.getItem('mannschaft.pendingInvite.later')).toBe(invite().id)
    wrapper.unmount()
    const again = await mountDialog()
    expect(shownViews(again)).toEqual([])
  })
  it('PID-15: あとでの記録と別の保留 ID なら表示する（記録は保留ごと）', async () => {
    sessionStorage.setItem('mannschaft.pendingInvite.later', '0193f000-0000-7000-8000-0000000000ff')
    const wrapper = await mountDialog()
    expect(wrapper.find(q('pending-invite-pending')).exists()).toBe(true)
  })
  it('PID-16: 承認の結果が EXPIRED なら、その場で失効案内に切り替える', async () => {
    route('POST', BASE + '/accept', async () => {
      current = invite({ status: 'EXPIRED' })
      return { data: { status: 'EXPIRED' } }
    })
    const wrapper = await mountDialog()
    await wrapper.find(q('pending-invite-accept')).trigger('click')
    await flushPromises()
    expect(wrapper.find(q('pending-invite-expired')).exists()).toBe(true)
    expect(wrapper.find(q('pending-invite-reissue')).exists()).toBe(true)
    expect(mocks.navigate).not.toHaveBeenCalled()
  })
  it('PID-17: 承認の結果が UNAVAILABLE なら参加できない旨を表示し遷移しない', async () => {
    route('POST', BASE + '/accept', async () => {
      current = invite({ status: 'UNAVAILABLE' })
      return { data: { status: 'UNAVAILABLE' } }
    })
    const wrapper = await mountDialog()
    await wrapper.find(q('pending-invite-accept')).trigger('click')
    await flushPromises()
    expect(wrapper.find(q('pending-invite-unavailable')).exists()).toBe(true)
    expect(mocks.navigate).not.toHaveBeenCalled()
  })
  it('PID-18: 承認が AUTH_086（処理中）なら取り直して処理中表示に切り替え、エラー扱いしない', async () => {
    route('POST', BASE + '/accept', async () => {
      current = invite({ status: 'ACCEPTING', retryAvailableAt: new Date(Date.now() + 4 * MIN).toISOString() })
      throw apiError('AUTH_086', 409)
    })
    const wrapper = await mountDialog()
    await wrapper.find(q('pending-invite-accept')).trigger('click')
    await flushPromises()
    expect(calls('GET', BASE)).toHaveLength(2)
    expect(wrapper.find(q('pending-invite-accepting')).exists()).toBe(true)
    expect(mocks.handleApiError).not.toHaveBeenCalled()
  })
  it('PID-19: 承認のその他の失敗（500 等）は共通エラーハンドラへ渡し、参加の確認を出したままにする', async () => {
    route('POST', BASE + '/accept', async () => {
      throw apiError('COMMON_001', 500)
    })
    const wrapper = await mountDialog()
    await wrapper.find(q('pending-invite-accept')).trigger('click')
    await flushPromises()
    expect(mocks.handleApiError).toHaveBeenCalledTimes(1)
    expect(wrapper.find(q('pending-invite-pending')).exists()).toBe(true)
    expect(mocks.navigate).not.toHaveBeenCalled()
  })
})

describe('EXPIRED / REISSUE_REQUESTED: 丁寧な失効案内（Q2・§6）', () => {
  it('PID-20: 公開の招待先なら参加申し込み画面（publicPath）へのリンクを出し、再招待依頼ボタンは出さない', async () => {
    current = invite({ status: 'EXPIRED', joinable: true, publicPath: '/teams/fc-mannschaft' })
    const wrapper = await mountDialog()
    expect(wrapper.find(q('pending-invite-expired')).exists()).toBe(true)
    const link = wrapper.find(q('pending-invite-join-request-link'))
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('/teams/fc-mannschaft')
    expect(wrapper.find(q('pending-invite-reissue')).exists()).toBe(false)
  })
  it('PID-21: 非公開の招待先なら再招待依頼ボタン → POST reissue-request → 依頼済み表示（ボタンは消える）', async () => {
    current = invite({ status: 'EXPIRED', joinable: false })
    route('POST', BASE + '/reissue-request', async () => {
      current = invite({ status: 'REISSUE_REQUESTED', reissueRequestedAt: new Date().toISOString() })
      return { data: { alreadyRequested: false } }
    })
    const wrapper = await mountDialog()
    expect(wrapper.find(q('pending-invite-expired')).exists()).toBe(true)
    expect(wrapper.find(q('pending-invite-join-request-link')).exists()).toBe(false)
    await wrapper.find(q('pending-invite-reissue')).trigger('click')
    await flushPromises()
    expect(calls('POST', BASE + '/reissue-request')).toHaveLength(1)
    expect(wrapper.find(q('pending-invite-reissue-requested')).exists()).toBe(true)
    expect(wrapper.find(q('pending-invite-reissue')).exists()).toBe(false)
  })
  it('PID-22: 7日以内の再押下（alreadyRequested=true）も依頼済み表示にする', async () => {
    current = invite({ status: 'EXPIRED', joinable: false })
    route('POST', BASE + '/reissue-request', async () => ({ data: { alreadyRequested: true } }))
    const wrapper = await mountDialog()
    await wrapper.find(q('pending-invite-reissue')).trigger('click')
    await flushPromises()
    expect(wrapper.find(q('pending-invite-reissue-requested')).exists()).toBe(true)
    expect(wrapper.find(q('pending-invite-reissue')).exists()).toBe(false)
  })
  it('PID-23: REISSUE_REQUESTED で開いたら最初から依頼済み表示（ボタンなし）', async () => {
    current = invite({ status: 'REISSUE_REQUESTED', reissueRequestedAt: '2026-11-20T03:00:00Z' })
    const wrapper = await mountDialog()
    expect(wrapper.find(q('pending-invite-expired')).exists()).toBe(true)
    expect(wrapper.find(q('pending-invite-reissue-requested')).exists()).toBe(true)
    expect(wrapper.find(q('pending-invite-reissue')).exists()).toBe(false)
  })
  it.each(['EXPIRED', 'REISSUE_REQUESTED'])('PID-24: %s の失効案内を閉じる → POST dismiss して閉じる', async (status) => {
    current = invite({ status })
    route('POST', BASE + '/dismiss', async () => {
      current = invite({ status: 'DISMISSED' })
      return { data: null }
    })
    const wrapper = await mountDialog()
    await wrapper.find(q('pending-invite-dismiss')).trigger('click')
    await flushPromises()
    expect(calls('POST', BASE + '/dismiss')).toHaveLength(1)
    expect(shownViews(wrapper)).toEqual([])
  })
  it('PID-25: 失効案内では参加ボタンを出さない', async () => {
    current = invite({ status: 'EXPIRED', joinable: true, publicPath: '/organizations/org-a' })
    const wrapper = await mountDialog()
    expect(wrapper.find(q('pending-invite-expired')).exists()).toBe(true)
    expect(wrapper.find(q('pending-invite-accept')).exists()).toBe(false)
  })
})

describe('ACCEPTING: 参加処理中と再試行（§9-4）', () => {
  it('PID-30: 再読込後も「参加処理中」を表示し、retryAvailableAt 前は再試行ボタンを無効にする', async () => {
    current = invite({ status: 'ACCEPTING', retryAvailableAt: new Date(Date.now() + 4 * MIN).toISOString() })
    const wrapper = await mountDialog()
    expect(wrapper.find(q('pending-invite-accepting')).exists()).toBe(true)
    const retry = wrapper.find(q('pending-invite-retry'))
    expect(retry.exists()).toBe(true)
    expect(retry.attributes('disabled')).toBeDefined()
    await retry.trigger('click')
    await flushPromises()
    expect(calls('POST', BASE + '/accept')).toHaveLength(0)
  })
  it('PID-31: retryAvailableAt（Z 付き ISO）を過ぎていれば再試行ボタンが有効で、押すと POST accept', async () => {
    current = invite({ status: 'ACCEPTING', retryAvailableAt: new Date(Date.now() - MIN).toISOString() })
    route('POST', BASE + '/accept', async () => {
      current = invite({ status: 'JOINED' })
      return { data: { status: 'JOINED' } }
    })
    const wrapper = await mountDialog()
    const retry = wrapper.find(q('pending-invite-retry'))
    expect(retry.attributes('disabled')).toBeUndefined()
    await retry.trigger('click')
    await flushPromises()
    expect(calls('POST', BASE + '/accept')).toHaveLength(1)
    expect(mocks.navigate).toHaveBeenCalledWith('/dashboard')
  })
  it('PID-32: 再試行が AUTH_086 なら取り直して表示を切り替える（別タブで JOINED 済みなら閉じる）', async () => {
    current = invite({ status: 'ACCEPTING', retryAvailableAt: new Date(Date.now() - MIN).toISOString() })
    route('POST', BASE + '/accept', async () => {
      current = invite({ status: 'PENDING' })
      throw apiError('AUTH_086', 409)
    })
    const wrapper = await mountDialog()
    await wrapper.find(q('pending-invite-retry')).trigger('click')
    await flushPromises()
    expect(calls('GET', BASE)).toHaveLength(2)
    expect(wrapper.find(q('pending-invite-pending')).exists()).toBe(true)
    expect(mocks.handleApiError).not.toHaveBeenCalled()
  })
  it('PID-33: ACCEPTING の画面では参加・断るボタンを出さない', async () => {
    current = invite({ status: 'ACCEPTING', retryAvailableAt: new Date(Date.now() + 4 * MIN).toISOString() })
    const wrapper = await mountDialog()
    expect(wrapper.find(q('pending-invite-accepting')).exists()).toBe(true)
    expect(wrapper.find(q('pending-invite-accept')).exists()).toBe(false)
    expect(wrapper.find(q('pending-invite-decline')).exists()).toBe(false)
  })
})

describe('UNAVAILABLE: 参加できない案内（殿の裁定）', () => {
  const SEEN_KEY = 'mannschaft.pendingInvite.unavailableSeen'
  beforeEach(() => {
    localStorage.clear()
    current = invite({ status: 'UNAVAILABLE' })
  })
  it('PID-50: 参加できない案内を出し、閉じると localStorage に保留 ID を記録して API は呼ばない', async () => {
    const wrapper = await mountDialog()
    expect(wrapper.find(q('pending-invite-unavailable')).exists()).toBe(true)
    await wrapper.find(q('pending-invite-dismiss')).trigger('click')
    await flushPromises()
    expect(shownViews(wrapper)).toEqual([])
    expect(localStorage.getItem(SEEN_KEY)).toBe(invite().id)
    expect(calls('POST', BASE + '/dismiss')).toHaveLength(0)
  })
  it('PID-51: 確認済みの保留 ID は次回以降表示しない。別の保留 ID なら表示する', async () => {
    localStorage.setItem(SEEN_KEY, invite().id as string)
    const seen = await mountDialog()
    expect(shownViews(seen)).toEqual([])
    seen.unmount()
    localStorage.setItem(SEEN_KEY, '0193f000-0000-7000-8000-0000000000ff')
    const other = await mountDialog()
    expect(other.find(q('pending-invite-unavailable')).exists()).toBe(true)
  })
})

describe('ACCEPTING: 時間経過での再試行有効化', () => {
  it('PID-34: 画面を開いたまま retryAvailableAt を過ぎたら再試行ボタンが有効になる', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'Date'] })
    try {
      current = invite({ status: 'ACCEPTING', retryAvailableAt: new Date(Date.now() + MIN).toISOString() })
      const wrapper = await mountDialog()
      expect(wrapper.find(q('pending-invite-retry')).attributes('disabled')).toBeDefined()
      await vi.advanceTimersByTimeAsync(MIN + 1000)
      await flushPromises()
      expect(wrapper.find(q('pending-invite-retry')).attributes('disabled')).toBeUndefined()
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('i18n（6言語）と直書き禁止', () => {
  const here = __dirname
  const JA = /[぀-ヿ一-鿿]/
  const KANA = /[぀-ヿ]/
  const COMMENT = /^\s*(\/\/|\*|\/\*|<!--)/
  const srcPath = resolve(here, 'PendingInviteDialog.vue')
  const readSrc = () => readFileSync(srcPath, 'utf8')
  const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
  const dict = (lang: string) =>
    (JSON.parse(readFileSync(resolve(here, '../../locales/' + lang + '/auth.json'), 'utf8')).auth
      ?.pending_invite ?? {}) as Record<string, unknown>
  const usedKeys = () => [
    ...new Set([...readSrc().matchAll(/['"\x60]auth\.pending_invite\.([a-z0-9_]+)['"\x60]/g)].map((m) => m[1]!)),
  ]

  it('PID-40: 文言は auth.pending_invite.* を使い、日本語を直書きしない', () => {
    const src = readSrc()
    expect(usedKeys().length).toBeGreaterThanOrEqual(10)
    const hits = src
      .split(/\r?\n/)
      .filter((l) => JA.test(l) && !COMMENT.test(l))
    expect(hits).toEqual([])
  })
  for (const lang of LANGS) {
    it('PID-41: ' + lang + '/auth.json に使用キーが全て在る', () => {
      const d = dict(lang)
      expect(usedKeys().filter((k) => typeof d[k] !== 'string' || (d[k] as string).length === 0)).toEqual([])
    })
    if (lang !== 'ja') {
      it('PID-42: ' + lang + ' の auth.pending_invite に日本語が残っていない', () => {
        const leaked = Object.entries(dict(lang))
          .filter(([, v]) => typeof v === 'string' && (lang === 'zh' ? KANA : JA).test(v))
          .map(([k]) => k)
        expect(Object.keys(dict(lang)).length).toBeGreaterThan(0)
        expect(leaked).toEqual([])
      })
    }
  }
})
