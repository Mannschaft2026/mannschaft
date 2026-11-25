import { describe, expect, it } from 'vitest'
import { parseAlertAlreadyAcknowledged } from './shiftBudgetAlertError'

describe('parseAlertAlreadyAcknowledged', () => {
  it('409 + SHIFT_BUDGET_032 は確認者・時刻を取り出す', () => {
    const info = parseAlertAlreadyAcknowledged({
      statusCode: 409,
      data: { error: { code: 'SHIFT_BUDGET_032', details: { acknowledgedBy: 7, acknowledgedByName: '山田 太郎', acknowledgedAt: '2026-11-25T10:00:00' } } },
    })
    expect(info).toEqual({ acknowledgedBy: 7, acknowledgedByName: '山田 太郎', acknowledgedAt: '2026-11-25T10:00:00' })
  })

  it('氏名・時刻が無い応答は null で埋める（握りつぶさず画面側が代替表示する）', () => {
    const info = parseAlertAlreadyAcknowledged({ status: 409, data: { error: { code: 'SHIFT_BUDGET_032' } } })
    expect(info).toEqual({ acknowledgedBy: null, acknowledgedByName: null, acknowledgedAt: null })
  })

  it.each([
    ['別のコード', { statusCode: 409, data: { error: { code: 'COMMON_003' } } }],
    ['別のステータス', { statusCode: 500, data: { error: { code: 'SHIFT_BUDGET_032' } } }],
    ['null', null],
  ])('%s は null（通常のエラー処理へ回す）', (_label, error) => {
    expect(parseAlertAlreadyAcknowledged(error)).toBeNull()
  })
})
