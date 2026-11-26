package com.mannschaft.app.contact.dto;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 招待トークンレスポンス（一覧用）。平文のトークン・招待URL・QR の URL は含めない（発行応答 {@link ContactInviteIssuedResponse} だけが返す）。
 */
@Getter
@SuperBuilder
public class ContactInviteTokenResponse {
    private Long id;
    private String label;
    private Integer maxUses;
    private Integer usedCount;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
