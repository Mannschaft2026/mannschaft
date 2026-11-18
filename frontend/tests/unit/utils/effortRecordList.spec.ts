import { describe, expect, it } from 'vitest'
import { mergeEffortRecords } from '~/utils/effortRecordList'

describe('mergeEffortRecords（続き読込の重複排除）', () => {
  it('同じ UUID は重複させず、新しい version で置き換える（位置は初出のまま）', () => {
    const merged = mergeEffortRecords(
      [{ id: 'a', version: 1 }, { id: 'b', version: 1 }],
      [{ id: 'b', version: 2 }, { id: 'c', version: 1 }],
    )
    expect(merged.map((r) => r.id)).toEqual(['a', 'b', 'c'])
    expect(merged[1]?.version).toBe(2)
  })

  it('古い version の再取得は既存を上書きしない', () => {
    const merged = mergeEffortRecords([{ id: 'a', version: 3 }], [{ id: 'a', version: 2 }])
    expect(merged).toEqual([{ id: 'a', version: 3 }])
  })

  it('追加分の中の重複も 1 件にする', () => {
    expect(mergeEffortRecords([], [{ id: 'x', version: 1 }, { id: 'x', version: 1 }])).toHaveLength(1)
  })
})
