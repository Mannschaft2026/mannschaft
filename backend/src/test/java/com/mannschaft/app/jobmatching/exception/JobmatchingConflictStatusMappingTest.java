package com.mannschaft.app.jobmatching.exception;

import com.mannschaft.app.common.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 求人の採用確定・取り下げ・不採用の競合で返るエラーコードが 409 Conflict に対応することを固定する（CMP-261021-0355）。
 *
 * <p>並行受諾の敗者は {@code JOB_NOT_OPEN} / {@code JOB_APPLICATION_NOT_PENDING} / {@code JOB_CAPACITY_FULL}、
 * 受諾済み応募への取り下げ・不採用は {@code JOB_INVALID_STATE_TRANSITION} を受ける。いずれも状態競合なので、
 * {@link GlobalExceptionHandler} の個別マップが外れて既定の 400 に落ちる退行をここで検出する。</p>
 */
@DisplayName("求人の競合エラーコードは 409 Conflict")
class JobmatchingConflictStatusMappingTest {

    @ParameterizedTest(name = "{0} → 409")
    @EnumSource(value = JobmatchingErrorCode.class, names = {
            "JOB_NOT_OPEN", "JOB_APPLICATION_NOT_PENDING", "JOB_INVALID_STATE_TRANSITION", "JOB_CAPACITY_FULL"})
    @DisplayName("採用確定の競合で返るエラーコードは 409")
    void 競合エラーコードは409(JobmatchingErrorCode code) {
        assertThat(GlobalExceptionHandler.resolveStatus(code)).isEqualTo(HttpStatus.CONFLICT);
    }
}
