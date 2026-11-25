import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import TicketsPage from './tickets.vue'

const mocks = vi.hoisted(() => ({
  getProducts: vi.fn(),
  getBooks: vi.fn(),
  createProduct: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-a' }, query: {} }))
mockNuxtImport('useNotification', () => () => ({
  success: mocks.success,
  error: mocks.error,
  info: vi.fn(),
  warn: vi.fn(),
}))
mockNuxtImport('useTicketApi', () => () => ({
  getProducts: mocks.getProducts,
  getBooks: mocks.getBooks,
  createProduct: mocks.createProduct,
}))
mockNuxtImport('useErrorReport', () => () => ({ captureQuiet: vi.fn(), capture: vi.fn() }))

const JAPANESE = /[぀-ヿ㐀-鿿]/
interface TicketsVm {
  form: { name: string, price: number }
  handleCreate: () => Promise<void>
}
// 403/404 は共通の「見つかりません」へ振り分ける（CMP-261125-1108）ため、BE message 表示の検証には 422 を使う
const forbidden = (msg: string) => ({ statusCode: 422, data: { error: { code: 'TEAM_999', message: msg } } })
const ok = { data: [] }
function tr(key: string): string {
  return useNuxtApp().$i18n.t(key)
}
async function mountPage() {
  const wrapper = await mountSuspended(TicketsPage)
  await flushPromises()
  return wrapper
}
async function create(wrapper: Awaited<ReturnType<typeof mountPage>>) {
  const vm = wrapper.vm as unknown as TicketsVm
  vm.form.name = '10-visit pass'
  await vm.handleCreate()
  await flushPromises()
}
function lastDetail(): string {
  const call = mocks.error.mock.calls.at(-1) ?? []
  return String(call[1] ?? '')
}

describe('CMP-261117-2159 回数券ページのトースト多言語化', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    mocks.getProducts.mockResolvedValue(ok)
    mocks.getBooks.mockResolvedValue(ok)
    mocks.createProduct.mockResolvedValue({})
    await useNuxtApp().$i18n.setLocale('en')
  })

  it('AC-9a: 商品取得が code+英語message の422で失敗したらそのmessageを出す', async () => {
    mocks.getProducts.mockRejectedValue(forbidden('Forbidden products (BE)'))
    await mountPage()
    expect(lastDetail()).toBe('Forbidden products (BE)')
  })

  it('AC-9a: 発行済み取得が code+英語message の422で失敗したらそのmessageを出す', async () => {
    mocks.getBooks.mockRejectedValue(forbidden('Forbidden books (BE)'))
    await mountPage()
    expect(lastDetail()).toBe('Forbidden books (BE)')
  })

  it('AC-9b: 商品追加が code+message で失敗したらBEのmessageを出す', async () => {
    const wrapper = await mountPage()
    mocks.createProduct.mockRejectedValue(forbidden('Cannot create (BE)'))
    await create(wrapper)
    expect(lastDetail()).toBe('Cannot create (BE)')
  })

  it('AC-9c: 取得が通信断・空本文で失敗しても日本語直書きは出ず翻訳済み文言が出る', async () => {
    for (const err of [new Error('network'), { data: {} }]) {
      mocks.error.mockClear()
      mocks.getProducts.mockRejectedValue(err)
      await mountPage()
      expect(mocks.error).toHaveBeenCalled()
      expect(lastDetail()).toBe(tr('error.unknown'))
      expect(JSON.stringify(mocks.error.mock.calls)).not.toMatch(JAPANESE)
    }
  })

  it('AC-9c: 追加が通信断・空本文で失敗しても日本語直書きは出ず翻訳済み文言が出る', async () => {
    const wrapper = await mountPage()
    for (const err of [new Error('network'), { data: {} }]) {
      mocks.error.mockClear()
      mocks.createProduct.mockRejectedValue(err)
      await create(wrapper)
      expect(lastDetail()).toBe(tr('error.unknown'))
      expect(JSON.stringify(mocks.error.mock.calls)).not.toMatch(JAPANESE)
    }
  })

  it('AC-9d: 追加成功のトーストがi18nで出て、再取得が失敗したら取得失敗として通知される', async () => {
    const wrapper = await mountPage()
    mocks.getProducts.mockRejectedValue(forbidden('Reload forbidden (BE)'))
    await create(wrapper)
    const msg = tr('payment.ticket.create_success')
    expect(msg).not.toBe('payment.ticket.create_success')
    expect(msg).not.toMatch(JAPANESE)
    expect(mocks.success).toHaveBeenCalledWith(msg)
    expect(lastDetail()).toBe('Reload forbidden (BE)')
  })
})
