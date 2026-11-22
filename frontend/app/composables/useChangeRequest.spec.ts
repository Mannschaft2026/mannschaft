import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import { useChangeRequest } from './useChangeRequest'

const mocks = vi.hoisted(() => ({
  api: {
    createChangeRequest: vi.fn(),
    reviewChangeRequest: vi.fn(),
    withdrawChangeRequest: vi.fn(),
    listChangeRequests: vi.fn(),
  },
  error: vi.fn(),
  success: vi.fn(),
}))
mockNuxtImport('useShiftApi', () => () => mocks.api)
mockNuxtImport('useNotification', () => () => ({ error: mocks.error, success: mocks.success }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const created = {
  id: 7, scheduleId: 1, slotId: null,
  requestInfo: { requestedBy: 2, requestType: 'PRE_CONFIRM_EDIT', reason: null },
  reviewInfo: { status: 'OPEN', reviewerId: null, reviewComment: null, reviewedAt: null },
  timing: { createdAt: '2026-10-27T12:00:00', expiresAt: null },
  version: 2,
}

beforeEach(() => { vi.clearAllMocks() })

describe('useChangeRequest: 完了・失敗のトースト文言', () => {
  it('作成成功は完了文言、ボタン名を流用しない', async () => {
    mocks.api.createChangeRequest.mockResolvedValue(created)
    await useChangeRequest(ref(1)).createRequest({ scheduleId: 1, requestType: 'PRE_CONFIRM_EDIT' })
    expect(mocks.success).toHaveBeenCalledWith('shift.changeRequest.submitted')
  })

  it.each([
    ['ACCEPTED', 'shift.changeRequest.approved'],
    ['REJECTED', 'shift.changeRequest.rejected'],
  ] as const)('審査成功 %s は完了文言', async (decision, key) => {
    mocks.api.reviewChangeRequest.mockResolvedValue(created)
    await useChangeRequest(ref(1)).review(7, decision, 'c', 2)
    expect(mocks.success).toHaveBeenCalledWith(key)
  })

  it('取り下げ成功は完了文言', async () => {
    mocks.api.withdrawChangeRequest.mockResolvedValue(undefined)
    await useChangeRequest(ref(1)).withdraw(7)
    expect(mocks.success).toHaveBeenCalledWith('shift.changeRequest.withdrawn')
  })

  it('作成・審査・取り下げの失敗は取得失敗でなく各操作の失敗文言', async () => {
    const c = useChangeRequest(ref(1))
    mocks.api.createChangeRequest.mockRejectedValue(new Error('x'))
    mocks.api.reviewChangeRequest.mockRejectedValue(new Error('x'))
    mocks.api.withdrawChangeRequest.mockRejectedValue(new Error('x'))
    await expect(c.createRequest({ scheduleId: 1, requestType: 'PRE_CONFIRM_EDIT' })).rejects.toThrow()
    await expect(c.review(7, 'ACCEPTED', undefined, 2)).rejects.toThrow()
    await expect(c.withdraw(7)).rejects.toThrow()
    expect(mocks.error.mock.calls.map(([k]) => k)).toEqual([
      'shift.changeRequest.submitError',
      'shift.changeRequest.reviewError',
      'shift.changeRequest.withdrawError',
    ])
  })
})

describe('useChangeRequest: 審査の 409（楽観ロック競合・審査済み）', () => {
  it.each(['SHIFT_018', 'SHIFT_031'])('409 %s は競合専用の文言を出し、一覧を再取得して、エラーを再送出する', async (code) => {
    mocks.api.reviewChangeRequest.mockRejectedValue({ statusCode: 409, data: { error: { code } } })
    mocks.api.listChangeRequests.mockResolvedValue([created])
    const c = useChangeRequest(ref(1))
    await expect(c.review(7, 'ACCEPTED', undefined, 2)).rejects.toThrow()
    expect(mocks.error).toHaveBeenCalledWith('shift.changeRequest.reviewConflict')
    expect(mocks.error).not.toHaveBeenCalledWith('shift.changeRequest.reviewError')
    expect(mocks.api.listChangeRequests).toHaveBeenCalledWith(1)
    expect(c.requests.value).toHaveLength(1)
  })

  it('409 以外（500）は従来の汎用文言で、一覧は再取得しない', async () => {
    mocks.api.reviewChangeRequest.mockRejectedValue({ statusCode: 500 })
    const c = useChangeRequest(ref(1))
    await expect(c.review(7, 'ACCEPTED', undefined, 2)).rejects.toThrow()
    expect(mocks.error).toHaveBeenCalledWith('shift.changeRequest.reviewError')
    expect(mocks.api.listChangeRequests).not.toHaveBeenCalled()
  })
})
