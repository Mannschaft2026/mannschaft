package com.mannschaft.app.auth.dto;

/** POST /api/v1/me/pending-invite/reissue-request の応答。7日以内の再押下は alreadyRequested=true。CMP-261107-1833。 */
public record PendingInviteReissueResponse(boolean alreadyRequested) {
}
