package com.mannschaft.app.actionmemo.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.time.LocalDate;

/**
 * F02.5 行動メモの日ごとの公開記録。
 *
 * <p>(user_id, memo_date) ごとに、publish-daily が作った生存中の個人（PERSONAL）投稿の ID を1件保持する。
 * メモの日付変更・論理削除に影響されず旧投稿を追えるようにするための記録で、メモ本文は持たない。
 * クロスドメインFKは持たない（user_id / timeline_post_id は ID 参照）。</p>
 */
@Entity
@Table(name = "action_memo_daily_posts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ActionMemoDailyPostEntity extends UuidV7Entity {

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private LocalDate memoDate;

    @Column(nullable = false)
    private Long timelinePostId;

    /** 作成した瞬間（UTC の瞬間。datetime_policy_utc_instant_vs_wallclock.md）。呼び出し側が Clock から渡す。 */
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** 最後に投稿 ID を更新した瞬間。 */
    @Column(nullable = false)
    private Instant updatedAt;

    /** 再公開で個人投稿 ID を差し替える。 */
    public void replacePost(Long newTimelinePostId, Instant now) {
        this.timelinePostId = newTimelinePostId;
        this.updatedAt = now;
    }
}
