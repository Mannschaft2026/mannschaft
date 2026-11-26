import { describe, expect, it, vi } from 'vitest'
import { fetchAllSpaces, isApplicableSpace } from './useParkingSpaceList'
import type { ParkingSpaceResponse } from '~/types/parking'

function space(id: number, extra: Partial<ParkingSpaceResponse> = {}): ParkingSpaceResponse {
  return {
    id,
    spaceNumber: `A-${id}`,
    spaceType: 'INDOOR',
    status: 'VACANT',
    applicationStatus: 'ACCEPTING',
    ...extra,
  }
}

describe('fetchAllSpaces', () => {
  it('meta.totalPages の最終ページまで取り切る（21 区画目以降が漏れない）', async () => {
    const fetchPage = vi
      .fn()
      .mockResolvedValueOnce({ data: [space(1), space(2)], meta: { totalPages: 3 } })
      .mockResolvedValueOnce({ data: [space(3)], meta: { totalPages: 3 } })
      .mockResolvedValueOnce({ data: [space(4)], meta: { totalPages: 3 } })

    const all = await fetchAllSpaces(fetchPage)

    expect(all.map((s) => s.id)).toEqual([1, 2, 3, 4])
    expect(fetchPage.mock.calls.map((c) => c[0])).toEqual([0, 1, 2])
    expect(fetchPage).toHaveBeenCalledWith(0, 100)
  })

  it('meta が無ければ 1 ページで終える', async () => {
    const fetchPage = vi.fn().mockResolvedValueOnce({ data: [space(1)] })
    expect(await fetchAllSpaces(fetchPage)).toHaveLength(1)
    expect(fetchPage).toHaveBeenCalledTimes(1)
  })

  it('途中のページが失敗したら握りつぶさず例外を投げる', async () => {
    const fetchPage = vi
      .fn()
      .mockResolvedValueOnce({ data: [space(1)], meta: { totalPages: 2 } })
      .mockRejectedValueOnce(new Error('boom'))
    await expect(fetchAllSpaces(fetchPage)).rejects.toThrow('boom')
  })
})

describe('isApplicableSpace', () => {
  it('受付中・空き・来場者用以外だけ申請対象', () => {
    expect(isApplicableSpace(space(1))).toBe(true)
    expect(isApplicableSpace(space(2, { spaceType: 'VISITOR' }))).toBe(false)
    expect(isApplicableSpace(space(3, { applicationStatus: 'NOT_ACCEPTING' }))).toBe(false)
    expect(isApplicableSpace(space(4, { applicationStatus: 'LOTTERY_CLOSED' }))).toBe(false)
    expect(isApplicableSpace(space(5, { status: 'OCCUPIED' }))).toBe(false)
  })
})
