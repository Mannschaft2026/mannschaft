import type { DailyAttendanceResponse, DailyRollCallEntry } from '~/types/school'

/** 朝の点呼画面の1行（API の出欠 + 表示用の生徒名）。 */
export interface RollCallStudentEntry extends DailyRollCallEntry {
  displayName: string
}

/**
 * 日次出欠の応答を点呼画面の行へ変換する。
 * 生徒名は BE が返す `studentDisplayName` を使う。退会済み等で BE が解決できなかった生徒
 * （null / 空）は user id を見せず、`unknownStudentLabel`（「不明な生徒」）で表示する。
 */
export function toRollCallEntries(
  records: DailyAttendanceResponse[],
  unknownStudentLabel: string,
): RollCallStudentEntry[] {
  return records.map((r) => ({
    studentUserId: r.studentUserId,
    displayName: r.studentDisplayName?.trim() ? r.studentDisplayName : unknownStudentLabel,
    status: r.status,
    absenceReason: r.absenceReason,
    arrivalTime: r.arrivalTime,
    leaveTime: r.leaveTime,
    comment: r.comment,
    familyNoticeId: r.familyNoticeId,
  }))
}
