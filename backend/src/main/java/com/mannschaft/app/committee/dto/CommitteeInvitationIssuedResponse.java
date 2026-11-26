package com.mannschaft.app.committee.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * 委員会招集状の発行応答。平文 {@code inviteToken} を載せるのは発行時の一度きり。
 *
 * <p>DB にはハッシュしか残らないため、後からは再取得できない。一覧は平文を持たない
 * {@link CommitteeInvitationResponse} を返す。本クラスは平文 {@code inviteToken} と一覧と同じ
 * {@link CommitteeInvitationResponse} を合成し、{@link JsonUnwrapped} で平らに出す
 * （JSON は {@code id, committeeId, inviteToken, expiresAt, ...} の形）。</p>
 *
 * @param inviteToken 平文の招集トークン（Base64URL 43字）。発行時にだけ返す
 * @param invitation  招集状の概要（一覧と同じ項目）
 */
public record CommitteeInvitationIssuedResponse(
        String inviteToken,
        @JsonUnwrapped CommitteeInvitationResponse invitation) {
}
