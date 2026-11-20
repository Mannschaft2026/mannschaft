package com.mannschaft.app.effortrecord.dto;

import java.util.List;

/** F06.4.1 個人一覧の 1 ページ分。nextCursor は opaque（AES-GCM 認証済み、最大 2048 字）。 */
public record EffortRecordPage(
        List<EffortOwnerRecordResponse> items,
        String nextCursor,
        boolean hasNext,
        int limit) {
}
