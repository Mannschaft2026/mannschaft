package com.mannschaft.app.filesharing.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * 共有リンクの発行応答。平文 {@code token} を載せるのは発行時の一度きり。
 *
 * <p>DB にはハッシュしか残らないため、後からは再取得できない。一覧は平文を持たない {@link LinkResponse} を返す。
 * 本クラスは平文 {@code token} と一覧と同じ {@link LinkResponse} を合成し、{@link JsonUnwrapped} で平らに出す
 * （JSON は {@code id, fileId, token, expiresAt, ...} の形）。</p>
 *
 * @param token 平文トークン（Base64URL 43字）。発行時にだけ返す
 * @param link  リンクの概要（一覧と同じ項目）
 */
public record LinkIssuedResponse(
        String token,
        @JsonUnwrapped LinkResponse link) {
}
