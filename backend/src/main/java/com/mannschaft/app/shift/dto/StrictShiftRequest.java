package com.mannschaft.app.shift.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * シフト系の更新リクエストが契約にない JSON プロパティを黙殺せず 400 で拒否するための共通部品（CMP-260910-1607）。
 *
 * <p>実装する DTO は {@code implements StrictShiftRequest} と書くだけでよい。未知プロパティを受けると
 * {@link IllegalArgumentException} を投げ、Jackson が {@code HttpMessageNotReadableException} に包んで
 * GlobalExceptionHandler が COMMON_001（400）にする。値は例外メッセージへ載せない。
 * 例外はデシリアライズ中に出るため、Service へ到達せず何も更新されない。</p>
 */
public interface StrictShiftRequest {

    /** 契約にない JSON プロパティは 400（先例: effortrecord の CreateEffortRecordRequest）。 */
    @JsonAnySetter
    default void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("許可されていない入力項目です");
    }
}
