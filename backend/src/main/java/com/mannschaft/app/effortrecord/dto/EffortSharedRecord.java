package com.mannschaft.app.effortrecord.dto;

import com.mannschaft.app.effortrecord.enums.EffortFieldType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * F06.4.1 チームに共有された記録（API 契約 §2 SharedRecord）。allowlist の定義順の項目だけを持ち、
 * 私的 note・未選択の値・原本 UUID・加入期間 ID を含めない。
 */
public record EffortSharedRecord(
        UUID id,
        long version,
        long teamId,
        Author author,
        LocalDate recordedOn,
        String recordTimezone,
        UUID definitionUuid,
        String templateName,
        List<Field> fields,
        Instant createdAt,
        Instant updatedAt,
        Interactions interactions) {

    /** 投稿者の公開プロフィール（既存の表示名・アバターだけ）。 */
    public record Author(Long userId, String displayName, String avatarUrl) {
    }

    /** 共有された1項目。 */
    public record Field(String fieldKey, String label, EffortFieldType type, String semantic, String unit, String value,
                        String displayValue) {
    }

    /** 努力由来の共有には反応・返信・引用・再投稿を許さない（すべて false 固定）。 */
    public record Interactions(boolean canLike, boolean canMitayo, boolean canComment, boolean canReply, boolean canQuote,
                               boolean canRepost) {

        public static final Interactions NONE = new Interactions(false, false, false, false, false, false);
    }
}
