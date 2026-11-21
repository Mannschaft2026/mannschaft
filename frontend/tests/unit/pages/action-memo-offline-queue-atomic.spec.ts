import { describe, it, expect, beforeEach, vi } from 'vitest'
import { offlineDb } from '~/composables/useOfflineDb'
import { useOfflineQueue } from '~/composables/useOfflineQueue'

/**
 * CMP-261120-0201: 公開のみ項目は識別子を含めて 1 回の add で保存する（add→update の中間状態を残さない）。
 */
describe('enqueuePublishOnly の原子性', () => {
  beforeEach(async () => {
    await useOfflineQueue().clearAll()
    vi.restoreAllMocks()
  })

  it('add だけで識別子つきの項目が保存され、update は呼ばれない', async () => {
    const addSpy = vi.spyOn(offlineDb.offlineQueue, 'add')
    const updateSpy = vi.spyOn(offlineDb.offlineQueue, 'update')
    const q = useOfflineQueue()
    await q.enqueuePublishOnly(900, 7)
    expect(updateSpy).not.toHaveBeenCalled()
    if (addSpy.mock.calls.length > 0) {
      expect(addSpy).toHaveBeenCalledTimes(1)
      expect((addSpy.mock.calls[0]![0] as { body: Record<string, unknown> }).body._publishOnlyMemoId).toBe(900)
    }
    const items = await q.getAll()
    expect(items).toHaveLength(1)
    expect(items[0]).toEqual(expect.objectContaining({ publishOnlyMemoId: 900, publishTeamId: 7 }))
  })
})
