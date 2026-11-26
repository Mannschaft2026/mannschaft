import { describe, it, expect } from 'vitest'
import { toRollCallEntries } from '~/utils/rollCallEntries'
import type { DailyAttendanceResponse } from '~/types/school'

function rec(id: number, name?: string | null): DailyAttendanceResponse {
  return {
    id,
    teamId: 1,
    studentUserId: id,
    studentDisplayName: name,
    attendanceDate: '2026-11-25',
    status: 'ATTENDING',
    recordedAt: '',
    createdAt: '',
    updatedAt: '',
  }
}

describe('toRollCallEntries', () => {
  it('BE が返した氏名を displayName にする（user id を出さない）', () => {
    const [e] = toRollCallEntries([rec(201, '山田 太郎')], '不明な生徒')
    expect(e!.displayName).toBe('山田 太郎')
  })

  it('氏名が null・空・未定義の生徒は代替ラベルにし、user id を表示名にしない', () => {
    const list = toRollCallEntries([rec(202, null), rec(203, '  '), rec(204)], '不明な生徒')
    expect(list.map((e) => e.displayName)).toEqual(['不明な生徒', '不明な生徒', '不明な生徒'])
  })
})
