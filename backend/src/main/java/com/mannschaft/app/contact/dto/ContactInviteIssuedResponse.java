package com.mannschaft.app.contact.dto;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

/**
 * 招待トークン発行応答。平文のトークンと招待URLを載せるのは、この発行応答の1回だけ
 * （DB にはハッシュしか残らないため、後から再取得できない）。一覧用の項目は {@link ContactInviteTokenResponse} を継承する。
 */
@Getter
@SuperBuilder
public class ContactInviteIssuedResponse extends ContactInviteTokenResponse {
    /** 平文トークン（Base64URL 43字）。一度だけ返す。 */
    private String token;
    /** 招待URL（平文トークンを含む）。一度だけ返す。 */
    private String inviteUrl;
}
