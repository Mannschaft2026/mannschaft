package com.mannschaft.app.effortrecord.dto;

import java.util.List;

/** F06.4.1 チーム一覧の 1 ページ分（空ページでも走査継続位置の nextCursor を返し得る。API 契約 §6）。 */
public record EffortSharedRecordPage(List<EffortSharedRecord> items, String nextCursor, boolean hasNext, int limit) {
}
