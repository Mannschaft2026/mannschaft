package com.mannschaft.app.effortrecord.dto;

import com.mannschaft.app.effortrecord.enums.EffortShareState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** F06.4.1 本人向けの共有状態（API 契約 §2 OwnerShare）。PR-1 では常に null で返る想定。 */
public record EffortOwnerShare(
        UUID id,
        long teamId,
        EffortShareState state,
        long version,
        long sourceVersion,
        long grantMembershipId,
        List<String> selectedFieldKeys,
        String lastErrorCode,
        Instant updatedAt) {
}
