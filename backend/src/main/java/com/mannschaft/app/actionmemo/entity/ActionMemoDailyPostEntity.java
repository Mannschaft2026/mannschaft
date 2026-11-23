package com.mannschaft.app.actionmemo.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

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

    @Setter
    @Column(nullable = false)
    private Long timelinePostId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
