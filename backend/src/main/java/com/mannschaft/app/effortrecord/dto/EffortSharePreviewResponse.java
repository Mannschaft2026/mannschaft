package com.mannschaft.app.effortrecord.dto;

import java.time.Instant;

/**
 * F06.4.1 共有 preview の応答（API 契約 §4）。projection.id は予約した share UUID、version は 0。
 * preview は DB へ共有を作らない。
 */
public record EffortSharePreviewResponse(
        String previewToken,
        Instant expiresAt,
        long recordVersion,
        long contentRevision,
        int definitionRevision,
        EffortSharedRecord projection) {
}
