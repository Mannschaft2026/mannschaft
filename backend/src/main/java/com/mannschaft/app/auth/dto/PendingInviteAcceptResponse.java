package com.mannschaft.app.auth.dto;

/** POST /api/v1/me/pending-invite/accept の応答（承認後の状態）。CMP-261107-1833。 */
public record PendingInviteAcceptResponse(String status) {
}
