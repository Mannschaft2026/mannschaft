import { describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useSignageApi } from './useSignageApi'

const api = vi.fn()
mockNuxtImport('useApi', () => () => api)

describe('useSignageApi トークン発行契約', () => {
  it('管理UIの既定値を必須リクエスト本文として送る', async () => {
    api.mockResolvedValue({ data: { id: 1 } })

    await useSignageApi().issueToken(42)

    expect(api).toHaveBeenCalledExactlyOnceWith('/api/v1/signage/screens/42/tokens', {
      method: 'POST',
      body: { name: null, allowedIps: null, expiredAt: null },
    })
  })
})
