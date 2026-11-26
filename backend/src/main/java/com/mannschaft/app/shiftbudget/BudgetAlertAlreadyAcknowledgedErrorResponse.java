package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.common.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/** 確認済みの予算警告への再確認 409 応答（既存の確認者・確認時刻付き）。 */
@Getter
@Schema(name = "BudgetAlertAlreadyAcknowledgedErrorResponse", description = "予算警告の確認済み 409 応答")
public class BudgetAlertAlreadyAcknowledgedErrorResponse {

    private final ErrorDetail error;

    public BudgetAlertAlreadyAcknowledgedErrorResponse(String code, String message,
                                                       Long acknowledgedBy, String acknowledgedByName,
                                                       OffsetDateTime acknowledgedAt) {
        this.error = new ErrorDetail(code, message, List.of(), new Details(acknowledgedBy, acknowledgedByName, acknowledgedAt));
    }

    @Getter
    @RequiredArgsConstructor
    @Schema(name = "BudgetAlertAlreadyAcknowledgedErrorDetail")
    public static class ErrorDetail {
        private final String code;
        private final String message;
        private final List<ErrorResponse.FieldError> fieldErrors;
        private final Details details;
    }

    @Getter
    @RequiredArgsConstructor
    @Schema(name = "BudgetAlertAlreadyAcknowledgedErrorDetails")
    public static class Details {
        @Schema(description = "既に確認したユーザー ID")
        private final Long acknowledgedBy;
        @Schema(description = "既に確認したユーザーの氏名（引けない場合は null）", nullable = true)
        private final String acknowledgedByName;
        @Schema(description = "既に確認した時刻")
        private final OffsetDateTime acknowledgedAt;
    }
}
