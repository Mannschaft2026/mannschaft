package com.mannschaft.app.budget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CMP-260909-2152 「一度締めた」事実と、その後の追加・訂正の累計（履歴画面の見出し）。
 *
 * <p>元額・件数・締め日時・操作者区分は締めた時点の値で、訂正しても変わらない。
 * 追加分（added_*）と訂正累計（correction_delta_total）はこれと分けて返す。</p>
 */
public record ShiftBudgetCloseView(
        @JsonProperty("original_amount") BigDecimal originalAmount,
        @JsonProperty("original_line_count") int originalLineCount,
        @JsonProperty("closed_at") LocalDateTime closedAt,
        @JsonProperty("close_actor_kind") String closeActorKind,
        @JsonProperty("close_actor_user_id") Long closeActorUserId,
        @JsonProperty("revision") long revision,
        @JsonProperty("posted_total") BigDecimal postedTotal,
        @JsonProperty("added_count") int addedCount,
        @JsonProperty("added_amount") BigDecimal addedAmount,
        @JsonProperty("correction_delta_total") BigDecimal correctionDeltaTotal
) {
}
