import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import Page from '~/pages/teams/[slug]/tickets.vue'
import { useScopeNotFound } from '~/composables/useScopeNotFound'

/**
 * CMP-261125-1108 子ページ: pages/teams/[slug]/tickets.vue（主 API: getProducts/getBooks）。
 *   403/404 → notFound を立て、エラー通知を出さない
 *   500     → 従来どおりエラー通知を出し、notFound は立てない
 */

const { mainApi, notifyError, handleApiError } = vi.hoisted(() => ({
  mainApi: vi.fn(),
  notifyError: vi.fn(),
  handleApiError: vi.fn(),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-a' }, query: {}, path: '/teams/team-a/tickets' }))
mockNuxtImport('useTicketApi', () => () => ({ getProducts: mainApi, getBooks: vi.fn().mockResolvedValue({ data: [] }), createProduct: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: notifyError, warn: vi.fn(), info: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({
  handleApiError,
  handleError: handleApiError,
  resolveMessage: vi.fn(),
  getFieldErrors: vi.fn(),
}))

async function flush() {
  for (let i = 0; i < 6; i++) await Promise.resolve()
  await nextTick()
}

afterEach(() => {
  useScopeNotFound().reset()
})

beforeEach(() => {
  mainApi.mockReset()
  notifyError.mockReset()
  handleApiError.mockReset()
})

describe('pages/teams/[slug]/tickets.vue — 非所属・権限なし（CMP-261125-1108）', () => {
  for (const status of [403, 404] as const) {
    it(`主 API が ${status} なら notFound を立て、エラー通知を出さない`, async () => {
      mainApi.mockRejectedValueOnce({ statusCode: status, response: { status } })
      const wrapper = await mountSuspended(Page)
      await flush()
      expect(notifyError).not.toHaveBeenCalled()
      expect(handleApiError).not.toHaveBeenCalled()
      expect(useScopeNotFound().notFound.value).toBe(true)
      wrapper.unmount()
    })
  }

  it('主 API が 500 なら従来どおりエラー通知を出し、notFound は立てない', async () => {
    mainApi.mockRejectedValueOnce({ statusCode: 500, response: { status: 500 } })
    const wrapper = await mountSuspended(Page)
    await flush()
    expect(notifyError.mock.calls.length + handleApiError.mock.calls.length).toBe(1)
    expect(useScopeNotFound().notFound.value).toBe(false)
    wrapper.unmount()
  })
})
