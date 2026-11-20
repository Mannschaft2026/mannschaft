import { describe, expect, it } from 'vitest'
import { groupMissingByUser, parseShiftPublishError } from './shiftPublishError'

describe('parseShiftPublishError', () => {
  it('400 / SHIFT_047 は時給未設定の一覧を返す', () => {
    const info = parseShiftPublishError({
      statusCode: 400,
      data: {
        error: {
          code: 'SHIFT_047',
          details: { missingHourlyRates: [{ userId: 7, slotDate: '2026-11-20' }] },
        },
      },
    })
    expect(info).toEqual({
      kind: 'hourlyRateMissing',
      missing: [{ userId: 7, slotDate: '2026-11-20' }],
    })
  })

  it('不正な要素は捨て、details 欠落でも時給ガードとして扱う', () => {
    expect(
      parseShiftPublishError({
        statusCode: 400,
        data: { error: { code: 'SHIFT_047', details: { missingHourlyRates: [{ userId: 'x' }, null] } } },
      }),
    ).toEqual({ kind: 'hourlyRateMissing', missing: [] })
    expect(parseShiftPublishError({ statusCode: 400, data: { error: { code: 'SHIFT_047' } } })).toEqual({
      kind: 'hourlyRateMissing',
      missing: [],
    })
  })

  it('409 / COMMON_003 は競合を返す', () => {
    expect(
      parseShiftPublishError({ statusCode: 409, data: { error: { code: 'COMMON_003' } } }),
    ).toEqual({ kind: 'conflict' })
  })

  it('それ以外は null（通常のエラー処理へ回す）', () => {
    expect(parseShiftPublishError({ statusCode: 400, data: { error: { code: 'COMMON_001' } } })).toBeNull()
    expect(parseShiftPublishError({ statusCode: 409, data: { error: { code: 'SHIFT_001' } } })).toBeNull()
    expect(parseShiftPublishError({ statusCode: 500 })).toBeNull()
    expect(parseShiftPublishError(null)).toBeNull()
    expect(parseShiftPublishError(new Error('x'))).toBeNull()
  })
})

describe('groupMissingByUser', () => {
  it('対象者ごとに勤務日を重複除去・昇順でまとめる', () => {
    expect(
      groupMissingByUser([
        { userId: 9, slotDate: '2026-11-22' },
        { userId: 3, slotDate: '2026-11-21' },
        { userId: 9, slotDate: '2026-11-20' },
        { userId: 9, slotDate: '2026-11-20' },
      ]),
    ).toEqual([
      { userId: 3, dates: ['2026-11-21'] },
      { userId: 9, dates: ['2026-11-20', '2026-11-22'] },
    ])
  })
})
