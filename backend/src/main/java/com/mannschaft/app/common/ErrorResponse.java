package com.mannschaft.app.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * エラーレスポンス。
 * <pre>
 * { "error": { "code": "AUTH_001", "message": "...", "fieldErrors": [...] } }
 * </pre>
 */
@Getter
@RequiredArgsConstructor
public class ErrorResponse {

    private final ErrorDetail error;

    /**
     * ErrorCode からフィールドエラーなしの ErrorResponse を生成する。
     */
    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(
                new ErrorDetail(errorCode.getCode(), errorCode.getMessage(), List.of()));
    }

    /**
     * ErrorCode とフィールドエラー一覧から ErrorResponse を生成する。
     */
    public static ErrorResponse of(ErrorCode errorCode, List<FieldError> fieldErrors) {
        return new ErrorResponse(
                new ErrorDetail(errorCode.getCode(), errorCode.getMessage(), fieldErrors));
    }

    /**
     * エラー詳細。
     */
    @Getter
    @RequiredArgsConstructor
    @Schema(name = "CommonErrorDetail")
    public static class ErrorDetail {
        private final String code;
        private final String message;
        private final List<FieldError> fieldErrors;
    }

    /**
     * フィールド単位のバリデーションエラー。
     */
    @Getter
    public static class FieldError {
        private final String field;
        private final String message;

        /**
         * 訳キー。{@code GlobalExceptionHandler} が利用者の言語で message を解決するための内部情報で、
         * レスポンスには出さない。無い場合は message をそのまま返す。
         */
        @JsonIgnore
        private final String messageKey;

        public FieldError(String field, String message) {
            this(field, message, null);
        }

        private FieldError(String field, String message, String messageKey) {
            this.field = field;
            this.message = message;
            this.messageKey = messageKey;
        }

        /**
         * 利用者の言語へ訳せるフィールドエラーを作る。
         *
         * @param field      フィールド名
         * @param messageKey 訳キー（messages_{lang}.properties）
         * @param jaMessage  ja の正本文言（ja・未対応言語・キー欠落時に返る）
         */
        public static FieldError ofKey(String field, String messageKey, String jaMessage) {
            return new FieldError(field, jaMessage, messageKey);
        }

        /** 同じフィールドで message だけを差し替えた複製（訳キーは持ち越さない）。 */
        public FieldError withMessage(String resolved) {
            return new FieldError(field, resolved, null);
        }
    }
}
