import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { useChatMessages } from '~/composables/chat/useChatMessages'

const api = vi.fn()

mockNuxtImport('useApi', () => () => api)

// CMP-261116-0721: BE の検索 API は `keyword`（必須）・`limit`（任意）のみ受ける。
// FE が `q` / `cursor` を送ると常に 400 COMMON_001 になっていた。
describe('useChatMessages.searchMessages の BE 窓口整合', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.mockReset()
    api.mockResolvedValue({ data: [] })
  })

  it('keyword で送り q / cursor を送らない（日本語・空白も URL エンコード）', async () => {
    await useChatMessages().searchMessages(7, '会議 資料')
    const url = api.mock.calls[0]![0] as string
    const [path, qs] = url.split('?')
    expect(path).toBe('/api/v1/chat/channels/7/messages/search')
    const params = new URLSearchParams(qs)
    expect(params.get('keyword')).toBe('会議 資料')
    expect(params.has('q')).toBe(false)
    expect(params.has('cursor')).toBe(false)
    expect(params.has('limit')).toBe(false)
    expect(qs).not.toContain('会議')
  })

  it('limit は指定時のみ送る', async () => {
    await useChatMessages().searchMessages(7, 'abc', 20)
    const params = new URLSearchParams((api.mock.calls[0]![0] as string).split('?')[1])
    expect(params.get('keyword')).toBe('abc')
    expect(params.get('limit')).toBe('20')
  })
})
