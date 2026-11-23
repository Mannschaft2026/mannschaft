import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'

/**
 * CMP-261118-0900 参加者向け連絡スペース画面（設計書 F08.7.1 01_communication.md §10.5）
 * 新ページ: pages/teams/[slug]/tournaments/[tId]/communication.vue
 *
 * - 読み込む API は新 EP 1 本のみ（GET /api/v1/teams/{teamSlug}/tournaments/{tournamentId}/contact-spaces）。
 *   主催者向け一覧・部門別一覧・組織配下の部門一覧 API は呼ばない（X13）。
 * - 出し分けはスペースごとの accessLevel（VIEW / POST）。can-manage は渡さない（AC-P36）。
 *
 * API 呼び出しは useApi の層でモックし、URL で応答を返す（composable の名前に依存しない）。
 */

const TEAM_SLUG = 'team-a'
const T_ID = 5
const PARTICIPANT_URL = `/api/v1/teams/${TEAM_SLUG}/tournaments/${T_ID}/contact-spaces`

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  getDivisions: vi.fn(),
  getChannel: vi.fn(),
  notify: {
    success: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn(),
    showSuccess: vi.fn(), showError: vi.fn(), showInfo: vi.fn(), showWarn: vi.fn(),
  },
  route: { params: { slug: 'team-a', tId: '5' }, query: {}, path: '/teams/team-a/tournaments/5/communication' },
}))

mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useRoute', () => () => mocks.route)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key, locale: { value: 'ja' } }))
mockNuxtImport('useNotification', () => () => mocks.notify)
mockNuxtImport('useTournamentApi', () => () => ({ getDivisions: mocks.getDivisions }))
mockNuxtImport('useChatApi', () => () => ({ getChannel: mocks.getChannel }))

const stubs = {
  PageHeader: { props: ['title', 'backTo'], template: '<header>{{ title }}</header>' },
  PageLoading: { template: '<div data-testid="page-loading" />' },
  LoadingBounce: true,
  ToggleSwitch: { name: 'ToggleSwitch', template: '<div data-testid="visibility-toggle" />' },
  DashboardEmptyState: { props: ['message', 'icon'], template: '<div data-testid="empty-state">{{ message }}<slot /></div>' },
  Button: {
    props: ['label', 'disabled', 'loading', 'icon', 'outlined'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}<slot /></button>',
  },
  BulletinThreadList: {
    name: 'BulletinThreadList',
    props: ['scopeType', 'scopeId', 'canManage', 'canCreate'],
    emits: ['select', 'create'],
    template: '<div data-testid="bulletin-list" />',
  },
  BulletinThreadDetail: {
    name: 'BulletinThreadDetail',
    props: ['threadId', 'canManage', 'canReply'],
    emits: ['back'],
    template: '<div data-testid="bulletin-detail" />',
  },
  BulletinThreadForm: {
    name: 'BulletinThreadForm',
    props: ['visible', 'scopeType', 'scopeId'],
    emits: ['saved', 'update:visible'],
    template: '<div data-testid="bulletin-form" />',
  },
  ChatMessagePanel: {
    name: 'ChatMessagePanel',
    props: ['channel', 'canPin', 'canDelete', 'canSend', 'teamId', 'organizationId'],
    template: '<div data-testid="chat-panel" />',
  },
}

type AccessLevel = 'VIEW' | 'POST'

function space(scopeType: 'TOURNAMENT' | 'TOURNAMENT_DIVISION', scopeId: number, spaceKind: 'BULLETIN' | 'CHAT',
  refId: number, accessLevel: AccessLevel, divisionName: string | null = null) {
  return {
    id: `0190a000-0000-7000-8000-${String(refId).padStart(12, '0')}`,
    scopeType, scopeId, divisionName, spaceKind, refId, isPublic: false, accessLevel,
  }
}

/** 設計書 §10.3.3 の応答（ApiResponse 包み） */
function participantResponse(level: AccessLevel = 'POST', options: { twoDivisions?: boolean, noSpaces?: boolean } = {}) {
  const divisions = [{ divisionId: 11, name: 'D1部' }]
  if (options.twoDivisions) divisions.push({ divisionId: 12, name: 'D2部' })
  const spaces = options.noSpaces
    ? []
    : [
        space('TOURNAMENT', T_ID, 'BULLETIN', 101, level),
        space('TOURNAMENT', T_ID, 'CHAT', 201, level),
        space('TOURNAMENT_DIVISION', 11, 'BULLETIN', 111, level, 'D1部'),
        space('TOURNAMENT_DIVISION', 11, 'CHAT', 211, level, 'D1部'),
        ...(options.twoDivisions
          ? [
              space('TOURNAMENT_DIVISION', 12, 'BULLETIN', 112, level, 'D2部'),
              space('TOURNAMENT_DIVISION', 12, 'CHAT', 212, level, 'D2部'),
            ]
          : []),
      ]
  return { data: { tournamentId: T_ID, tournamentName: '春季大会', divisions, spaces } }
}

/** ofetch の FetchError 相当（status 系・data の両方を持たせ、実装の判定方法に依存しない） */
function httpError(status: number, code: string) {
  const body = { error: { code, message: 'error' } }
  return Object.assign(new Error(`[GET] ${PARTICIPANT_URL}: ${status}`), {
    name: 'FetchError', status, statusCode: status, statusMessage: 'error',
    data: body, response: { status, _data: body },
  })
}

/** 通信断（応答なし） */
function networkError() {
  return Object.assign(new TypeError('fetch failed'), { name: 'FetchError', status: undefined, statusCode: undefined, response: undefined, data: undefined })
}

function channel(id: number) {
  return {
    id,
    identity: { channelType: 'TOURNAMENT', teamId: null, organizationId: null },
    meta: { name: `ch-${id}`, iconKey: null, description: null },
    settings: { isPrivate: true, isInquiryChannel: false, isArchived: false, version: 1 },
    lastMessage: { lastMessageAt: null, lastMessagePreview: null },
    source: { sourceType: 'TOURNAMENT', sourceId: T_ID },
    audit: { createdBy: 1, createdAt: null, updatedAt: null },
    memberCount: 0, dmPartner: null, derivedName: null, viewer: null,
  }
}

async function open() {
  const page = await import('~/pages/teams/[slug]/tournaments/[tId]/communication.vue')
  const wrapper = await mountSuspended(page.default, { global: { stubs, mocks: { $t: (key: string) => key } } })
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof open>>

function clickables(wrapper: Wrapper) {
  return wrapper.findAll('button, [role="tab"], a')
}
async function clickByText(wrapper: Wrapper, text: string) {
  const target = clickables(wrapper).find(el => el.text().includes(text))
  expect(target, `「${text}」を押せる要素が見つからない`).toBeTruthy()
  await target!.trigger('click')
  await flushPromises()
}
async function openChatTab(wrapper: Wrapper) {
  await clickByText(wrapper, 'tournament.communication.chat')
}
function participantCalls() {
  return mocks.api.mock.calls.filter(([url]) => url === PARTICIPANT_URL)
}
function anyErrorNotified() {
  const n = mocks.notify
  return [n.error, n.showError, n.warn, n.showWarn].some(fn => fn.mock.calls.length > 0)
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.api.mockImplementation(async (url: string) => {
    if (url === PARTICIPANT_URL) return participantResponse('POST')
    throw new Error(`想定外の API 呼び出し: ${url}`)
  })
  mocks.getChannel.mockImplementation(async (id: number) => ({ data: channel(id) }))
  mocks.getDivisions.mockRejectedValue(new Error('参加者画面は組織配下の部門一覧を呼ばない'))
})

describe('参加者向け連絡スペース画面（/teams/[slug]/tournaments/[tId]/communication）', () => {
  describe('AC-P24 権限による出し分け（新規スレッド作成は POST のみ）', () => {
    it('AC-P24: POST では掲示板一覧に作成ボタンを有効にして渡し、作成要求でフォームが開く', async () => {
      const wrapper = await open()
      const list = wrapper.findComponent({ name: 'BulletinThreadList' })
      expect(list.exists()).toBe(true)
      expect(list.props('scopeType')).toBe('TOURNAMENT')
      expect(list.props('scopeId')).toBe(T_ID)
      expect(list.props('canCreate')).toBe(true)

      list.vm.$emit('create')
      await flushPromises()
      const form = wrapper.findComponent({ name: 'BulletinThreadForm' })
      expect(form.exists()).toBe(true)
      expect(form.props('visible')).toBe(true)
      expect(form.props('scopeType')).toBe('TOURNAMENT')
      expect(form.props('scopeId')).toBe(T_ID)
      expect(wrapper.text()).not.toContain('tournament.communication.participant.view_only_notice')
    })

    it('AC-P24: VIEW では作成ボタンを出さず、閲覧のみの補足を出す', async () => {
      mocks.api.mockResolvedValue(participantResponse('VIEW'))
      const wrapper = await open()
      const list = wrapper.findComponent({ name: 'BulletinThreadList' })
      expect(list.exists()).toBe(true)
      expect(list.props('canCreate')).not.toBe(true)
      const form = wrapper.findComponent({ name: 'BulletinThreadForm' })
      expect(form.exists() && form.props('visible') === true).toBe(false)
      expect(wrapper.text()).toContain('tournament.communication.participant.view_only_notice')
    })

    it('AC-P24: 大会名を表示する', async () => {
      const wrapper = await open()
      expect(wrapper.text()).toContain('春季大会')
    })
  })

  describe('AC-P25 / AC-P28 accessLevel を返信・送信の可否に渡す', () => {
    it.each([
      ['POST', true],
      ['VIEW', false],
    ] as const)('AC-P25: %s ではスレッド詳細に canReply=%s を渡す', async (level, expected) => {
      mocks.api.mockResolvedValue(participantResponse(level))
      const wrapper = await open()
      wrapper.findComponent({ name: 'BulletinThreadList' }).vm.$emit('select', { id: 77 })
      await flushPromises()
      const detail = wrapper.findComponent({ name: 'BulletinThreadDetail' })
      expect(detail.exists()).toBe(true)
      expect(detail.props('threadId')).toBe(77)
      expect(detail.props('canReply')).toBe(expected)
    })

    it.each([
      ['POST', true],
      ['VIEW', false],
    ] as const)('AC-P28: %s ではチャットパネルに canSend=%s を渡す', async (level, expected) => {
      mocks.api.mockResolvedValue(participantResponse(level))
      const wrapper = await open()
      await openChatTab(wrapper)
      expect(mocks.getChannel).toHaveBeenCalledWith(201)
      const chat = wrapper.findComponent({ name: 'ChatMessagePanel' })
      expect(chat.exists()).toBe(true)
      expect(chat.props('channel')).toMatchObject({ id: 201 })
      expect(chat.props('canSend')).toBe(expected)
    })
  })

  describe('AC-P36 管理操作（ピン・削除・ロック・公開設定）を出さない', () => {
    it('AC-P36: POST（チーム代表）でも掲示板一覧・スレッド詳細に can-manage を渡さない', async () => {
      const wrapper = await open()
      const list = wrapper.findComponent({ name: 'BulletinThreadList' })
      expect(list.props('canManage')).toBeFalsy()
      list.vm.$emit('select', { id: 77 })
      await flushPromises()
      expect(wrapper.findComponent({ name: 'BulletinThreadDetail' }).props('canManage')).toBeFalsy()
    })

    it('AC-P36: チャットパネルにピン・他人投稿削除の権限を渡さない', async () => {
      const wrapper = await open()
      await openChatTab(wrapper)
      const chat = wrapper.findComponent({ name: 'ChatMessagePanel' })
      expect(chat.exists()).toBe(true)
      expect(chat.props('canPin')).toBeFalsy()
      expect(chat.props('canDelete')).toBeFalsy()
    })

    it('AC-P36: 主催者用の公開設定トグルを出さない', async () => {
      const wrapper = await open()
      expect(wrapper.findComponent({ name: 'ToggleSwitch' }).exists()).toBe(false)
      expect(wrapper.text()).not.toContain('tournament.communication.visibility_toggle')
    })
  })

  describe('AC-P30 読み込み失敗（握りつぶし禁止・空状態を偽装しない）', () => {
    it.each([
      ['5xx', () => httpError(500, 'COMMON_500')],
      ['通信断', () => networkError()],
    ])('AC-P30: %s ではエラー通知と再試行導線を出し、再試行で再取得して表示する', async (_label, makeError) => {
      let first = true
      mocks.api.mockImplementation(async (url: string) => {
        if (url !== PARTICIPANT_URL) throw new Error(`想定外の API 呼び出し: ${url}`)
        if (first) {
          first = false
          throw makeError()
        }
        return participantResponse('POST')
      })
      const wrapper = await open()

      expect(anyErrorNotified()).toBe(true)
      // 空状態・利用不可状態に偽装しない
      expect(wrapper.text()).not.toContain('tournament.communication.empty')
      expect(wrapper.text()).not.toContain('tournament.communication.participant.unavailable')
      expect(wrapper.findComponent({ name: 'BulletinThreadList' }).exists()).toBe(false)

      await clickByText(wrapper, 'tournament.communication.participant.load_retry')
      expect(participantCalls()).toHaveLength(2)
      expect(wrapper.findComponent({ name: 'BulletinThreadList' }).exists()).toBe(true)
      expect(wrapper.text()).not.toContain('tournament.communication.participant.load_retry')
    })

    it('AC-P30 / AC-P26: 404 TOUR_029 は「利用できない」状態表示のみで、通知トーストを出さない', async () => {
      mocks.api.mockRejectedValue(httpError(404, 'TOUR_029'))
      const wrapper = await open()

      expect(wrapper.text()).toContain('tournament.communication.participant.unavailable')
      expect(anyErrorNotified()).toBe(false)
      expect(wrapper.text()).not.toContain('tournament.communication.empty')
      expect(wrapper.findComponent({ name: 'BulletinThreadList' }).exists()).toBe(false)
      expect(wrapper.findComponent({ name: 'ChatMessagePanel' }).exists()).toBe(false)
    })
  })

  describe('X4 部門切替で開いていたスレッド・作成ダイアログを持ち越さない', () => {
    it('X4: 作成ダイアログを開いたまま部門を切り替えると閉じ、部門スコープの一覧になる', async () => {
      const wrapper = await open()
      wrapper.findComponent({ name: 'BulletinThreadList' }).vm.$emit('create')
      await flushPromises()
      expect(wrapper.findComponent({ name: 'BulletinThreadForm' }).props('visible')).toBe(true)

      await clickByText(wrapper, 'D1部')
      const form = wrapper.findComponent({ name: 'BulletinThreadForm' })
      expect(form.exists() && form.props('visible') === true).toBe(false)
      const list = wrapper.findComponent({ name: 'BulletinThreadList' })
      expect(list.props('scopeType')).toBe('TOURNAMENT_DIVISION')
      expect(list.props('scopeId')).toBe(11)
    })

    it('X4: スレッドを開いたまま部門を切り替えると閉じて一覧に戻る', async () => {
      const wrapper = await open()
      wrapper.findComponent({ name: 'BulletinThreadList' }).vm.$emit('select', { id: 77 })
      await flushPromises()
      expect(wrapper.findComponent({ name: 'BulletinThreadDetail' }).exists()).toBe(true)

      await clickByText(wrapper, 'D1部')
      expect(wrapper.findComponent({ name: 'BulletinThreadDetail' }).exists()).toBe(false)
      expect(wrapper.findComponent({ name: 'BulletinThreadList' }).props('scopeId')).toBe(11)

      wrapper.findComponent({ name: 'BulletinThreadList' }).vm.$emit('select', { id: 88 })
      await flushPromises()
      await clickByText(wrapper, 'tournament.communication.tournament_space')
      expect(wrapper.findComponent({ name: 'BulletinThreadDetail' }).exists()).toBe(false)
      expect(wrapper.findComponent({ name: 'BulletinThreadList' }).props('scopeType')).toBe('TOURNAMENT')
    })
  })

  describe('X11 スペース 0 件', () => {
    it('X11: spaces=[] は既存キー tournament.communication.empty を表示し、エラー通知を出さない', async () => {
      mocks.api.mockResolvedValue(participantResponse('POST', { noSpaces: true }))
      const wrapper = await open()
      expect(wrapper.text()).toContain('tournament.communication.empty')
      expect(anyErrorNotified()).toBe(false)
      expect(wrapper.text()).not.toContain('tournament.communication.participant.unavailable')
    })
  })

  describe('X13 一覧 API は新 EP を 1 回だけ呼ぶ', () => {
    it('X13: 部門・タブを切り替えても新 EP は 1 回だけで、主催者向け一覧・部門別一覧・組織の部門一覧を呼ばない', async () => {
      mocks.api.mockResolvedValue(participantResponse('POST', { twoDivisions: true }))
      const wrapper = await open()
      await clickByText(wrapper, 'D1部')
      await clickByText(wrapper, 'D2部')
      await openChatTab(wrapper)
      await clickByText(wrapper, 'tournament.communication.tournament_space')

      expect(participantCalls()).toHaveLength(1)
      const otherUrls = mocks.api.mock.calls.map(([url]) => String(url)).filter(url => url !== PARTICIPANT_URL)
      expect(otherUrls).toEqual([])
      expect(mocks.getDivisions).not.toHaveBeenCalled()
      // D2 のスコープは応答の部門名から描画されている
      expect(wrapper.findComponent({ name: 'BulletinThreadList' }).exists() || wrapper.findComponent({ name: 'ChatMessagePanel' }).exists()).toBe(true)
    })

    it('X13: 部門名は応答の divisions から描画する（部門一覧 API を使わない）', async () => {
      mocks.api.mockResolvedValue(participantResponse('POST', { twoDivisions: true }))
      const wrapper = await open()
      expect(wrapper.text()).toContain('D1部')
      expect(wrapper.text()).toContain('D2部')
      expect(mocks.getDivisions).not.toHaveBeenCalled()
    })
  })
})
