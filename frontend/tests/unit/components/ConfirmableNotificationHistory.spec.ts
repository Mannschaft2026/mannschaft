import { getCurrentInstance } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ConfirmableNotificationHistory from '~/components/confirmable/ConfirmableNotificationHistory.vue'

/**
 * CMP-261115-2057: 確認通知履歴のキャンセル。
 * await の後で useToast() を呼ぶと setup 外の呼び出しで例外になり、API 成功でも
 * 「キャンセルに失敗しました」が出ていた。成功時は成功トーストのみ、失敗時のみ失敗トーストを出す。
 */

const toastAdd = vi.fn()
vi.mock('primevue/usetoast', async (importOriginal) => ({
  ...(await importOriginal<typeof import('primevue/usetoast')>()),
  useToast: () => {
    // 本物の inject 同様、setup の同期部分以外で呼ぶと失敗する状況を再現する
    if (!getCurrentInstance()) throw new Error('useToast() must be called inside setup')
    return { add: toastAdd }
  },
}))

const listNotifications = vi.fn()
const cancelNotification = vi.fn()
vi.mock('~/composables/useConfirmableNotificationApi', () => ({
  useConfirmableNotificationApi: () => ({ listNotifications, cancelNotification }),
}))

const activeNotif = {
  id: 1,
  title: 'テスト通知',
  status: 'ACTIVE',
  priority: 'NORMAL',
  totalRecipientCount: 2,
  confirmedCount: 0,
  createdAt: '2026-11-15T00:00:00Z',
}

async function mountAndCancel() {
  const wrapper = await mountSuspended(ConfirmableNotificationHistory, {
    props: { scopeType: 'TEAM', scopeId: '1' },
    global: { mocks: { $t: (k: string) => k } },
  })
  await flushPromises()
  const btn = wrapper.findAll('button').find(b => b.text() === 'button.cancel')
  expect(btn).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  toastAdd.mockReset()
  listNotifications.mockReset().mockResolvedValue({ data: [activeNotif] })
  cancelNotification.mockReset()
})

describe('ConfirmableNotificationHistory キャンセル', () => {
  it('API 成功時は成功トーストのみで失敗トーストは出ない', async () => {
    cancelNotification.mockResolvedValue(undefined)
    await mountAndCancel()
    const severities = toastAdd.mock.calls.map(c => c[0].severity)
    expect(severities).toContain('success')
    expect(severities).not.toContain('error')
  })

  it('API 失敗時は失敗トーストが出る', async () => {
    cancelNotification.mockRejectedValue(new Error('boom'))
    await mountAndCancel()
    const severities = toastAdd.mock.calls.map(c => c[0].severity)
    expect(severities).toContain('error')
    expect(severities).not.toContain('success')
  })
})
