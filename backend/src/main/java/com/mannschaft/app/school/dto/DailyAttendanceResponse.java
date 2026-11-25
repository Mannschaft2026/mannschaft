package com.mannschaft.app.school.dto;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.entity.AbsenceReason;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 日次出欠1件レスポンス。 */
@Getter
@Builder
public class DailyAttendanceResponse {

    private Long id;
    private Long teamId;
    private Long studentUserId;
    /** 生徒の氏名（姓 名）。退会済み等で解決できない場合は null（FE が「不明な生徒」を表示する）。 */
    private String studentDisplayName;
    private LocalDate attendanceDate;
    private AttendanceStatus status;
    private AbsenceReason absenceReason;
    private LocalTime arrivalTime;
    private LocalTime leaveTime;
    private String comment;
    private Long familyNoticeId;
    private Long recordedBy;
    private LocalDateTime recordedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * エンティティから DailyAttendanceResponse を生成するファクトリメソッド。
     *
     * @param entity 日次出欠レコードエンティティ
     * @return DailyAttendanceResponse
     */
    public static DailyAttendanceResponse from(DailyAttendanceRecordEntity entity) {
        return from(entity, null);
    }

    /**
     * 生徒氏名つきでエンティティから生成する（一覧取得用。氏名はバッチ解決済みの値を渡す）。
     *
     * @param entity             日次出欠レコードエンティティ
     * @param studentDisplayName 生徒の氏名。解決できない場合は null
     * @return DailyAttendanceResponse
     */
    public static DailyAttendanceResponse from(DailyAttendanceRecordEntity entity, String studentDisplayName) {
        return DailyAttendanceResponse.builder()
                .id(entity.getId())
                .teamId(entity.getTeamId())
                .studentUserId(entity.getStudentUserId())
                .studentDisplayName(studentDisplayName)
                .attendanceDate(entity.getAttendanceDate())
                .status(entity.getStatus())
                .absenceReason(entity.getAbsenceReason())
                .arrivalTime(entity.getArrivalTime())
                .leaveTime(entity.getLeaveTime())
                .comment(entity.getComment())
                .familyNoticeId(entity.getFamilyNoticeId())
                .recordedBy(entity.getRecordedBy())
                .recordedAt(entity.getRecordedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
