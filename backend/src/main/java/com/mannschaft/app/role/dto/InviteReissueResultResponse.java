package com.mannschaft.app.role.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 再招待・見送りの結果（CMP-261107-1833 PR3 設計 i）。
 *
 * @param status    REISSUED（新しい招待を発行）/ ALREADY_MEMBER（依頼者は在籍済み）/ REISSUE_DECLINED（見送り）
 * @param expiresAt 新しい招待の有効期限（REISSUED のときだけ）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InviteReissueResultResponse(String status, Instant expiresAt) {

    public static final String REISSUED = "REISSUED";
    public static final String ALREADY_MEMBER = "ALREADY_MEMBER";
    public static final String REISSUE_DECLINED = "REISSUE_DECLINED";
}
