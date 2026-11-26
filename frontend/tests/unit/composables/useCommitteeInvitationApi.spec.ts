/**
 * CMP-260826-1457 W2 committee: 委員会招集トークンの受諾・辞退は、BE の契約（`InviteByTokenRequest.inviteToken`）どおり
 * body の `inviteToken` に平文を載せる。URL に平文は載せない。
 *
 * これまでは `{ token }` で送っており、BE の `@NotBlank inviteToken` で 400 になる不整合があった。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useCommitteeInvitationApi } from '~/composables/useCommitteeInvitationApi'

const apiMock = vi.fn()
mockNuxtImport('useApi', () => () => apiMock)

const PLAINTEXT = 'Zr3x9QpL0a7_bN2-kW5yT8uE1oI4sD6fG0hJ9lK2mNc'

beforeEach(() => {
  apiMock.mockReset()
  apiMock.mockResolvedValue({ data: {} })
})

describe('useCommitteeInvitationApi: トークンは body の inviteToken で送る', () => {
  it('受諾は POST accept-by-token の body.inviteToken に平文を載せ、URL には載せない', async () => {
    await useCommitteeInvitationApi().acceptByToken(PLAINTEXT)

    const [url, options] = apiMock.mock.calls[0] as [string, { method: string, body: Record<string, unknown> }]
    expect(url).toBe('/api/v1/committee-invitations/accept-by-token')
    expect(url).not.toContain(PLAINTEXT)
    expect(options.method).toBe('POST')
    expect(options.body).toEqual({ inviteToken: PLAINTEXT })
  })

  it('辞退は POST decline-by-token の body.inviteToken に平文を載せ、URL には載せない', async () => {
    await useCommitteeInvitationApi().declineByToken(PLAINTEXT)

    const [url, options] = apiMock.mock.calls[0] as [string, { method: string, body: Record<string, unknown> }]
    expect(url).toBe('/api/v1/committee-invitations/decline-by-token')
    expect(url).not.toContain(PLAINTEXT)
    expect(options.body).toEqual({ inviteToken: PLAINTEXT })
  })
})
