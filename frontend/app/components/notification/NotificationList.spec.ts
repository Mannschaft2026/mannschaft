// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { NotificationResponse, NotificationListResponse } from '~/types/notification'
import NotificationList from '~/components/notification/NotificationList.vue'

type ConfirmableNotificationFixture = NotificationResponse & { isConfirmed?: boolean | null }

const mocks = vi.hoisted(() => ({
  authStore: { user: { id: 42, timezone: 'Asia/Tokyo' } as { id: number; timezone: string } | null },
  notificationStore: { latestNotification: null as { id: number; userId: number } | null },
  notificationApi: {
    getNotifications: vi.fn(),
    markAsRead: vi.fn(),
    markAsUnread: vi.fn(),
    markAllAsRead: vi.fn(),
    snooze: vi.fn(),
  },
  confirmableApi: {
    confirmNotification: vi.fn(),
    confirmPersonalNotification: vi.fn(),
    getNotificationDetail: vi.fn(),
  },
  showError: vi.fn(),
  confirmClosure: vi.fn(),
  captureQuiet: vi.fn(),
  toastAdd: vi.fn(),
  routerPush: vi.fn(),
}))

vi.mock('~/composables/useNotificationApi', () => ({
  useNotificationApi: () => mocks.notificationApi,
}))
vi.mock('~/composables/useConfirmableNotificationApi', () => ({
  useConfirmableNotificationApi: () => mocks.confirmableApi,
}))
vi.mock('~/composables/useEmergencyClosureApi', () => ({
  useEmergencyClosureApi: () => ({ confirmClosure: mocks.confirmClosure }),
}))
vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({ captureQuiet: mocks.captureQuiet }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ showError: mocks.showError }),
}))
vi.mock('~/composables/useRelativeTime', () => ({
  useRelativeTime: () => ({ relativeTime: () => '今' }),
}))
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => mocks.authStore,
}))
vi.mock('~/stores/useNotificationStore', () => ({ useNotificationStore: () => mocks.notificationStore }))
vi.mock('nuxt/app', async () => {
  const actual = await vi.importActual<typeof import('nuxt/app')>('nuxt/app')
  return {
    ...actual,
    useRouter: () => ({ push: mocks.routerPush }),
    useState: (_key: string, init: () => unknown) => ref(init()),
  }
})
vi.mock('#app/composables/router', async () => {
  const actual =
    await vi.importActual<typeof import('#app/composables/router')>('#app/composables/router')
  return { ...actual, useRouter: () => ({ push: mocks.routerPush }) }
})
vi.mock('#app/composables/state', async () => {
  const actual =
    await vi.importActual<typeof import('#app/composables/state')>('#app/composables/state')
  return { ...actual, useState: (_key: string, init: () => unknown) => ref(init()) }
})
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: mocks.toastAdd }) }))

vi.mock('#imports', () => ({
  ref,
  watch,
  onMounted,
  onUnmounted,
  useState: (_key: string, init: () => unknown) => ref(init()),
  useNotificationApi: () => mocks.notificationApi,
  useConfirmableNotificationApi: () => mocks.confirmableApi,
  useEmergencyClosureApi: () => ({ confirmClosure: mocks.confirmClosure }),
  useErrorReport: () => ({ captureQuiet: mocks.captureQuiet }),
  useNotification: () => ({ showError: mocks.showError }),
  useI18n: () => ({ t: (key: string) => key }),
  useRouter: () => ({ push: mocks.routerPush }),
  useRelativeTime: () => ({ relativeTime: () => '今' }),
  useAuthStore: () => mocks.authStore,
  useNotificationStore: () => mocks.notificationStore,
  useToast: () => ({ add: mocks.toastAdd }),
}))

const confirmButtonLabel = 'confirmable.confirm_button'
const confirmedLabel = 'confirmable.already_confirmed'

const notification = (
  isConfirmed: boolean | null,
  isRead: boolean,
): ConfirmableNotificationFixture => ({
  id: 3255966,
  notificationType: 'RECRUITMENT_PENALTY_APPLIED',
  priority: 'URGENT',
  title: '確認状態のテスト通知',
  body: '確認状態の独立性を検証する',
  sourceType: 'CONFIRMABLE_NOTIFICATION',
  sourceId: 67,
  scopeType: 'TEAM',
  scopeId: 'team-w16-test',
  scopeName: '試験チーム',
  actionUrl: '/notifications',
  actor: null,
  isRead,
  readAt: isRead ? '2026-09-28T10:00:00' : null,
  snoozedUntil: null,
  createdAt: '2026-09-28T09:00:00',
  isConfirmed,
})

const stubs = {
  Button: {
    props: ['label'],
    emits: ['click'],
    template:
      '<button type="button" :data-label="label" :data-testid="label === \'notification.list.markAllRead\' ? \'mark-all-read\' : undefined" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
  SelectButton: { props: ['modelValue'], emits: ['update:modelValue'], template: '<div />' },
  Menu: { template: '<div />' },
  LoadingBounce: { template: '<div />' },
}

const i18n = createI18n({
  legacy: false,
  locale: 'ja',
  messages: { ja: {}, en: {}, zh: {}, ko: {}, es: {}, de: {} },
})
const router = createRouter({ history: createMemoryHistory(), routes: [] })

async function mountList(row: ConfirmableNotificationFixture) {
  mocks.notificationApi.getNotifications.mockResolvedValue({
    data: [row],
    meta: { total: 1, page: 0, size: 20, totalPages: 1 },
  })
  mocks.confirmableApi.getNotificationDetail.mockResolvedValue({
    data: { totalRecipientCount: 1, confirmedCount: 0, unconfirmedVisibility: 'ALL_MEMBERS' },
  })
  return mount(NotificationList, {
    global: {
      stubs,
      plugins: [i18n, router],
      mocks: { $t: (key: string) => key },
    },
  })
}

describe('通知の既読と確認状態', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.authStore = reactive({ user: { id: 42, timezone: 'Asia/Tokyo' } })
    mocks.notificationStore = reactive({ latestNotification: null })
    mocks.notificationApi.markAsRead.mockResolvedValue(undefined)
    mocks.notificationApi.markAsUnread.mockResolvedValue(undefined)
    mocks.notificationApi.markAllAsRead.mockResolvedValue(undefined)
    mocks.confirmableApi.confirmNotification.mockResolvedValue(undefined)
    mocks.confirmableApi.confirmPersonalNotification.mockResolvedValue(undefined)
  })

  it('既読状態にかかわらず未確認だけ確認ボタン、確認済みだけ確認済み表示を出す', async () => {
    const unconfirmedRead = await mountList(notification(false, true))
    await flushPromises()
    expect(unconfirmedRead.findAll(`button[data-label="${confirmButtonLabel}"]`)).toHaveLength(1)
    unconfirmedRead.unmount()

    const confirmedUnread = await mountList(notification(true, false))
    await flushPromises()
    expect(confirmedUnread.text()).toContain(confirmedLabel)
    expect(confirmedUnread.text()).not.toContain(confirmButtonLabel)
    confirmedUnread.unmount()

    const notConfirmable = await mountList(notification(null, false))
    await flushPromises()
    expect(notConfirmable.text()).not.toContain(confirmButtonLabel)
    expect(notConfirmable.text()).not.toContain(confirmedLabel)
    notConfirmable.unmount()
  })

  it('タイトル既読・既読切替・全件既読で確認状態を変えず、確認API成功だけが確認済みにする', async () => {
    const row = notification(false, false)
    const wrapper = await mountList(row)
    await flushPromises()

    const rowElement = wrapper.get('[role="button"]')
    await rowElement.trigger('click')
    await flushPromises()
    expect(mocks.notificationApi.markAsRead).toHaveBeenCalledTimes(1)
    expect(row.isConfirmed).toBe(false)
    expect(wrapper.text()).toContain(confirmButtonLabel)

    await rowElement.get('button[aria-label="notification.list.markUnread"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll(`button[data-label="${confirmButtonLabel}"]`)).toHaveLength(1)
    expect(wrapper.text()).toContain(confirmButtonLabel)

    await wrapper.get('[data-testid="mark-all-read"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll(`button[data-label="${confirmButtonLabel}"]`)).toHaveLength(1)
    expect(wrapper.text()).toContain(confirmButtonLabel)

    await wrapper.get(`button[data-label="${confirmButtonLabel}"]`).trigger('click')
    await flushPromises()
    expect(mocks.confirmableApi.confirmNotification).toHaveBeenCalledWith(
      'TEAM',
      'team-w16-test',
      67,
    )
    expect(wrapper.text()).toContain(confirmedLabel)
    expect(mocks.notificationApi.markAsRead).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('確認済み通知を未読にしても確認済み表示を維持する', async () => {
    const wrapper = await mountList(notification(true, false))
    await flushPromises()
    const rowElement = wrapper.get('[role="button"]')
    expect(wrapper.text()).toContain(confirmedLabel)

    await rowElement.get('button[aria-label="notification.list.markRead"]').trigger('click')
    await flushPromises()
    expect(mocks.notificationApi.markAsRead).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain(confirmedLabel)

    await rowElement.get('button[aria-label="notification.list.markUnread"]').trigger('click')
    await flushPromises()
    expect(mocks.notificationApi.markAsUnread).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain(confirmedLabel)
    expect(wrapper.text()).not.toContain(confirmButtonLabel)
    wrapper.unmount()
  })

  it('追加読み込みした通知の確認後も全ページを保持し、サマリと既読状態を更新する', async () => {
    const firstPageRow = {
      ...notification(null, true),
      id: 3255965,
      sourceType: 'USER',
      sourceId: 3,
    }
    const secondPageRow = { ...notification(false, false), id: 3255966 }
    const thirdPageRow = {
      ...notification(null, true),
      id: 3255967,
      sourceType: 'USER',
      sourceId: 4,
    }
    mocks.notificationApi.getNotifications.mockImplementation(
      async ({ page }: { page: number }) => ({
        data: page === 0 ? [firstPageRow] : page === 1 ? [secondPageRow] : [thirdPageRow],
        meta: { total: 3, page, size: 1, totalPages: 3 },
      }),
    )
    mocks.confirmableApi.getNotificationDetail
      .mockResolvedValueOnce({
        data: { totalRecipientCount: 1, confirmedCount: 0, unconfirmedVisibility: 'ALL_MEMBERS' },
      })
      .mockResolvedValueOnce({
        data: { totalRecipientCount: 1, confirmedCount: 1, unconfirmedVisibility: 'ALL_MEMBERS' },
      })
    const wrapper = mount(NotificationList, {
      global: {
        stubs,
        plugins: [i18n, router],
        mocks: {
          $t: (key: string, params?: Record<string, unknown>) =>
            key === 'confirmable.unconfirmed_count' ? `未確認:${String(params?.count)}` : key,
        },
      },
    })
    await flushPromises()
    await wrapper.get('button[data-label="notification.list.loadMore"]').trigger('click')
    await flushPromises()

    const target = wrapper.get('[data-notification-id="3255966"]')
    expect(target.text()).toContain('未確認:1')
    await target.get(`button[data-label="${confirmButtonLabel}"]`).trigger('click')
    await flushPromises()

    expect(wrapper.findAll('[data-notification-id]')).toHaveLength(2)
    expect(target.text()).toContain(confirmedLabel)
    expect(target.text()).toContain('未確認:0')
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    expect(mocks.confirmableApi.getNotificationDetail).toHaveBeenCalledTimes(2)

    await wrapper.get('button[data-label="notification.list.loadMore"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-notification-id]')).toHaveLength(3)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(3)

    await target.get('button[aria-label="notification.list.markUnread"]').trigger('click')
    await flushPromises()
    expect(mocks.notificationApi.markAsUnread).toHaveBeenCalledTimes(1)
    expect(target.text()).toContain(confirmedLabel)
    expect(wrapper.findAll('[data-notification-id]')).toHaveLength(3)
    wrapper.unmount()
  })

  it('確認後の既読更新に失敗しても確認済み状態を保ち、確認失敗とは表示しない', async () => {
    mocks.notificationApi.markAsRead.mockRejectedValue(new Error('read failed'))
    const wrapper = await mountList(notification(false, false))
    await flushPromises()

    await wrapper.get(`button[data-label="${confirmButtonLabel}"]`).trigger('click')
    await flushPromises()

    expect(mocks.confirmableApi.confirmNotification).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain(confirmedLabel)
    expect(wrapper.text()).not.toContain(confirmButtonLabel)
    expect(mocks.showError).toHaveBeenCalledWith('inbox.action.readFailed')
    expect(mocks.showError).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('確認APIに失敗した場合は未確認のまま確認ボタンを残す', async () => {
    mocks.confirmableApi.confirmNotification.mockRejectedValue(new Error('confirm failed'))
    const wrapper = await mountList(notification(false, true))
    await flushPromises()

    await wrapper.get(`button[data-label="${confirmButtonLabel}"]`).trigger('click')
    await flushPromises()

    expect(wrapper.findAll(`button[data-label="${confirmButtonLabel}"]`)).toHaveLength(1)
    expect(wrapper.text()).not.toContain(confirmedLabel)
    expect(mocks.notificationApi.markAsRead).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('既読 API の失敗と冪等化（CMP-261030-0652）', () => {
  const unhandled: unknown[] = []
  const onUnhandled = (reason: unknown) => {
    unhandled.push(reason)
  }

  beforeEach(() => {
    vi.clearAllMocks()
    unhandled.length = 0
    process.on('unhandledRejection', onUnhandled)
    mocks.notificationApi.markAsRead.mockResolvedValue(undefined)
    mocks.confirmClosure.mockResolvedValue(undefined)
  })

  afterEach(() => {
    process.off('unhandledRejection', onUnhandled)
  })

  const closureRow = (): ConfirmableNotificationFixture => ({
    ...notification(null, false),
    id: 3255970,
    sourceType: 'EMERGENCY_CLOSURE',
    sourceId: 12,
  })
  const unreadDot = (wrapper: Awaited<ReturnType<typeof mountList>>) =>
    wrapper.findAll('div.h-2.w-2.rounded-full.bg-primary')

  it('AC-10 行クリックの既読 API が reject しても未処理の reject を出さず、未読のまま再クリックで再試行できる', async () => {
    mocks.notificationApi.markAsRead.mockRejectedValue(new Error('read failed'))
    const wrapper = await mountList(notification(null, false))
    await flushPromises()
    const rowElement = wrapper.get('[role="button"]')

    await rowElement.trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(unhandled).toHaveLength(0)
    expect(unreadDot(wrapper)).toHaveLength(1)
    expect(mocks.captureQuiet).toHaveBeenCalled()

    await rowElement.trigger('click')
    await flushPromises()
    expect(mocks.notificationApi.markAsRead).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('AC-11 画面は未読・サーバーは既読の緊急休業通知を確認すると、確認失敗にならず成功トーストと再取得が行われる', async () => {
    const wrapper = await mountList(closureRow())
    await flushPromises()
    const callsBefore = mocks.notificationApi.getNotifications.mock.calls.length

    await wrapper.get('button[data-label="emergency_closure.confirm_button"]').trigger('click')
    await flushPromises()

    expect(mocks.confirmClosure).toHaveBeenCalledTimes(1)
    expect(mocks.notificationApi.markAsRead).toHaveBeenCalledTimes(1)
    expect(mocks.showError).not.toHaveBeenCalledWith('emergency_closure.confirm_failed')
    expect(mocks.toastAdd).toHaveBeenCalledWith(
      expect.objectContaining({ summary: 'emergency_closure.confirm_success' }),
    )
    expect(mocks.notificationApi.getNotifications.mock.calls.length).toBeGreaterThan(callsBefore)
    wrapper.unmount()
  })

  it('AC-11b 確認は成功し既読 API だけ失敗した場合、成功トーストを出し、既読の失敗は captureQuiet に記録して未処理の reject を出さない', async () => {
    mocks.notificationApi.markAsRead.mockRejectedValue(new Error('read failed'))
    const wrapper = await mountList(closureRow())
    await flushPromises()

    await wrapper.get('button[data-label="emergency_closure.confirm_button"]').trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(mocks.confirmClosure).toHaveBeenCalledTimes(1)
    expect(mocks.toastAdd).toHaveBeenCalledWith(
      expect.objectContaining({ summary: 'emergency_closure.confirm_success' }),
    )
    expect(mocks.showError).not.toHaveBeenCalledWith('emergency_closure.confirm_failed')
    expect(mocks.captureQuiet).toHaveBeenCalled()
    expect(unhandled).toHaveLength(0)
    wrapper.unmount()
  })
})

function listResponse(rows: NotificationResponse[], page = 0, totalPages = 1): NotificationListResponse {
  return { data: rows, meta: { total: totalPages * 20, page, size: 20, totalPages } }
}
function liveRow(id: number): NotificationResponse {
  return { ...notification(null, false), id, title: `通知 ${id}`, sourceType: 'SYSTEM', sourceId: null }
}
async function receiveNotification(id: number) {
  mocks.notificationStore.latestNotification = { id, userId: mocks.authStore.user?.id ?? 42 }
  await flushPromises()
}

describe('N3–N5: 通知一覧のリアルタイム取得境界', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.notificationApi.getNotifications.mockReset()
    mocks.authStore = reactive({ user: { id: 42, timezone: 'Asia/Tokyo' } })
    mocks.notificationStore = reactive({ latestNotification: null })
    mocks.notificationApi.markAsRead.mockResolvedValue(undefined)
    mocks.confirmableApi.confirmNotification.mockResolvedValue(undefined)
  })

  it('N3: 空一覧でも受信後に正規APIを再取得し、新しい通知を表示する', async () => {
    mocks.notificationApi.getNotifications.mockResolvedValueOnce(listResponse([]))
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    mocks.notificationApi.getNotifications.mockResolvedValue(listResponse([liveRow(2), liveRow(1)]))
    await receiveNotification(2)
    expect(wrapper.text()).toContain('通知 2')
    expect(wrapper.findAll('[data-notification-id="2"]')).toHaveLength(1)
    wrapper.unmount()
  })

  it('N3: 未読filterを保持して受信を取り込み、同idを重複表示しない', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    wrapper.findComponent(stubs.SelectButton).vm.$emit('update:modelValue', 'unread')
    await flushPromises()
    mocks.notificationApi.getNotifications.mockResolvedValue(listResponse([liveRow(2), liveRow(2)]))
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenLastCalledWith({ page: 0, size: 20, isRead: false })
    expect(wrapper.findAll('[data-notification-id="2"]')).toHaveLength(1)
    wrapper.unmount()
  })

  it('N4: 取得済みpage深さを維持し、refresh後のloadMoreでも重複しない', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    mocks.notificationApi.getNotifications.mockResolvedValueOnce(listResponse([liveRow(1)], 0, 3))
    await (wrapper.vm as unknown as { refresh: () => Promise<void> }).refresh()
    mocks.notificationApi.getNotifications.mockResolvedValueOnce(listResponse([liveRow(2)], 1, 3))
    await wrapper.get('button[data-label="notification.list.loadMore"]').trigger('click')
    await flushPromises()
    mocks.notificationApi.getNotifications
      .mockResolvedValueOnce(listResponse([liveRow(3), liveRow(1)], 0, 3))
      .mockResolvedValueOnce(listResponse([liveRow(2)], 1, 3))
    await receiveNotification(3)
    expect(wrapper.text()).toContain('通知 3')
    expect(wrapper.text()).toContain('通知 2')
    mocks.notificationApi.getNotifications.mockResolvedValueOnce(listResponse([liveRow(2), liveRow(4)], 2, 3))
    await wrapper.get('button[data-label="notification.list.loadMore"]').trigger('click')
    await flushPromises()
    expect(mocks.notificationApi.getNotifications).toHaveBeenLastCalledWith({ page: 2, size: 20, isRead: undefined })
    expect(wrapper.findAll('[data-notification-id="2"]')).toHaveLength(1)
    wrapper.unmount()
  })

  it('N5: 取得中の連続通知を次のrefresh一回へ集約する', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    let complete!: (value: NotificationListResponse) => void
    mocks.notificationApi.getNotifications.mockImplementationOnce(() => new Promise<NotificationListResponse>(resolve => { complete = resolve }))
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    for (let id = 3; id <= 12; id++) await receiveNotification(id)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    mocks.notificationApi.getNotifications.mockResolvedValue(listResponse([liveRow(12)]))
    complete(listResponse([liveRow(2)]))
    await flushPromises()
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toContain('通知 12')
    wrapper.unmount()
  })

  it('N5: unmount後の応答からサマリ取得や追加APIを発行しない', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    let complete!: (value: NotificationListResponse) => void
    mocks.notificationApi.getNotifications.mockImplementationOnce(() => new Promise<NotificationListResponse>(resolve => { complete = resolve }))
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    complete(listResponse([notification(false, false)]))
    await flushPromises()
    expect(mocks.confirmableApi.getNotificationDetail).not.toHaveBeenCalled()
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
  })

  it('N3: 確認成功前に開始したGETの遅着で確認状態を巻き戻さない', async () => {
    const wrapper = await mountList(notification(false, false))
    await flushPromises()
    let complete!: (value: NotificationListResponse) => void
    mocks.notificationApi.getNotifications.mockImplementationOnce(() => new Promise<NotificationListResponse>(resolve => { complete = resolve }))
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    await wrapper.get(`button[data-label="${confirmButtonLabel}"]`).trigger('click')
    await flushPromises()
    mocks.notificationApi.getNotifications.mockResolvedValue(listResponse([notification(true, true)]))
    complete(listResponse([notification(false, false)]))
    await flushPromises()
    expect(wrapper.text()).toContain(confirmedLabel)
    expect(wrapper.text()).not.toContain(confirmButtonLabel)
    wrapper.unmount()
  })

  it('N2: A確認の遅着後にB資格で既読POSTを追送しない', async () => {
    const wrapper = await mountList(notification(false, false))
    await flushPromises()
    let complete!: () => void
    mocks.confirmableApi.confirmNotification.mockImplementationOnce(() => new Promise<void>(resolve => { complete = resolve }))
    await wrapper.get(`button[data-label="${confirmButtonLabel}"]`).trigger('click')
    mocks.authStore.user = { id: 43, timezone: 'Asia/Tokyo' }
    complete()
    await flushPromises()
    expect(mocks.notificationApi.markAsRead).not.toHaveBeenCalled()
    expect(mocks.toastAdd).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('N5: refresh取得失敗を既存エラーで表示し、現在の通知を消さない', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    mocks.notificationApi.getNotifications.mockRejectedValueOnce(new Error('fixture network failure'))
    await receiveNotification(2)
    expect(mocks.showError).toHaveBeenCalledWith('notification.list.loadError')
    expect(wrapper.text()).toContain('通知 1')
    wrapper.unmount()
  })

  it('N5: 古いfilterの遅着応答を現在の未読filterへ適用しない', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    let complete!: (value: NotificationListResponse) => void
    mocks.notificationApi.getNotifications.mockImplementationOnce(() => new Promise<NotificationListResponse>(resolve => { complete = resolve }))
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    wrapper.findComponent(stubs.SelectButton).vm.$emit('update:modelValue', 'unread')
    await flushPromises()
    mocks.notificationApi.getNotifications.mockResolvedValue(listResponse([liveRow(3)]))
    complete(listResponse([{ ...liveRow(2), isRead: true }]))
    await flushPromises()
    expect(wrapper.text()).toContain('通知 3')
    expect(wrapper.text()).not.toContain('通知 2')
    expect(mocks.notificationApi.getNotifications).toHaveBeenLastCalledWith({ page: 0, size: 20, isRead: false })
    wrapper.unmount()
  })

  it('N5: Aの遅着応答をB一覧へ適用しない', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    let complete!: (value: NotificationListResponse) => void
    mocks.notificationApi.getNotifications.mockImplementationOnce(() => new Promise<NotificationListResponse>(resolve => { complete = resolve }))
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    mocks.authStore.user = { id: 43, timezone: 'Asia/Tokyo' }
    mocks.notificationApi.getNotifications.mockResolvedValue(listResponse([liveRow(3)]))
    complete(listResponse([liveRow(2)]))
    await flushPromises()
    expect(wrapper.text()).toContain('通知 3')
    expect(wrapper.text()).not.toContain('通知 2')
    wrapper.unmount()
  })
})
