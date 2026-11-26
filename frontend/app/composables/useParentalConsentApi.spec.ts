import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useParentalConsentApi } from './useParentalConsentApi'

/**
 * CMP-261123-1125 試練 AC5: 保護者招待の送信 body を camelCase の { parentEmail } に固定する。
 * BE の InviteParentRequest も camelCase（Jackson 既定・同機能 DTO と同じ）で受ける。
 * snake_case（parent_email）は送らない。
 */
const api = vi.fn()
mockNuxtImport('useApi', () => () => api)

beforeEach(() => {
  api.mockReset()
  api.mockResolvedValue({ data: { message: 'ok' } })
})

describe('useParentalConsentApi.sendInvitation（AC5）', () => {
  it('AC5-FE: POST /api/v1/parental-consent/invitations に { parentEmail } だけを送る', async () => {
    await useParentalConsentApi().sendInvitation('parent@example.com')

    expect(api).toHaveBeenCalledTimes(1)
    const [url, options] = api.mock.calls[0]!
    expect(url).toBe('/api/v1/parental-consent/invitations')
    expect(options).toEqual({ method: 'POST', body: { parentEmail: 'parent@example.com' } })
    expect(JSON.stringify(options.body)).not.toContain('parent_email')
  })
})
