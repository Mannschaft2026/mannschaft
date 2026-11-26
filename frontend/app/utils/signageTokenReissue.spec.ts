import { describe, expect, it, vi } from 'vitest'
import { carryOverExpiry, reissueSignageToken } from './signageTokenReissue'
import type { SignageToken, SignageTokenIssued } from '~/types/signage'

const NOW = new Date('2026-11-25T00:00:00Z')
const OLD: SignageToken = {
  id: 1, screenId: 1, name: 'エントランス', isActive: true, allowedIps: ['10.0.0.1'],
  expiredAt: '2026-12-25T00:00:00Z', createdAt: '2026-01-01T00:00:00Z',
}
const issued = { ...OLD, id: 2, token: 'NEW_PLAINTEXT' } as SignageTokenIssued

describe('サイネージトークンの再発行', () => {
  it('期限が未来なら引き継ぎ、過去・無期限なら null', () => {
    expect(carryOverExpiry('2026-12-25T00:00:00Z', NOW)).toBe('2026-12-25T00:00:00Z')
    expect(carryOverExpiry('2026-01-01T00:00:00Z', NOW)).toBeNull()
    expect(carryOverExpiry(null, NOW)).toBeNull()
  })

  it('成功: 新規発行のあと旧トークンを無効化する', async () => {
    const issue = vi.fn().mockResolvedValue({ data: issued })
    const revoke = vi.fn().mockResolvedValue(undefined)
    const result = await reissueSignageToken(OLD, issue, revoke, NOW)
    expect(result).toEqual({ plaintext: 'NEW_PLAINTEXT', revokeFailed: false })
    expect(issue).toHaveBeenCalledWith({ name: 'エントランス', allowedIps: ['10.0.0.1'], expiredAt: OLD.expiredAt })
    expect(revoke).toHaveBeenCalledWith(1)
  })

  it('旧の期限が過去なら null で発行する', async () => {
    const issue = vi.fn().mockResolvedValue({ data: issued })
    await reissueSignageToken({ ...OLD, expiredAt: '2026-01-01T00:00:00Z' }, issue, vi.fn().mockResolvedValue(undefined), NOW)
    expect(issue).toHaveBeenCalledWith(expect.objectContaining({ expiredAt: null }))
  })

  it('無効化に失敗しても新しい平文は返し、失敗を明示する', async () => {
    const result = await reissueSignageToken(
      OLD, vi.fn().mockResolvedValue({ data: issued }), vi.fn().mockRejectedValue(new Error('x')), NOW)
    expect(result).toEqual({ plaintext: 'NEW_PLAINTEXT', revokeFailed: true })
  })

  it('発行に失敗したら例外で、旧トークンは無効化しない', async () => {
    const revoke = vi.fn()
    await expect(reissueSignageToken(OLD, vi.fn().mockRejectedValue(new Error('x')), revoke, NOW)).rejects.toThrow()
    expect(revoke).not.toHaveBeenCalled()
  })
})
