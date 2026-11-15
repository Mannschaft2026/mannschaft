package com.mannschaft.app.shift;

import com.mannschaft.app.common.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/** 公開時給ガードの 400 応答（欠落した対象者・勤務日の一覧付き）。 */
@Getter
@Schema(name = "ShiftHourlyRateMissingErrorResponse", description = "公開前の時給欠落 400 応答")
public class ShiftHourlyRateMissingErrorResponse {

    private final ErrorDetail error;

    public ShiftHourlyRateMissingErrorResponse(String code, String message,
                                               List<ShiftHourlyRateMissingException.Missing> missing) {
        this.error = new ErrorDetail(code, message, List.of(), new Details(missing));
    }

    @Getter
    @RequiredArgsConstructor
    public static class ErrorDetail {
        private final String code;
        private final String message;
        private final List<ErrorResponse.FieldError> fieldErrors;
        private final Details details;
    }

    @Getter
    @RequiredArgsConstructor
    public static class Details {
        @Schema(description = "時給を確認できなかった割当者と勤務日")
        private final List<ShiftHourlyRateMissingException.Missing> missingHourlyRates;
    }
}
