package com.mannschaft.app.social.announcement;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * お知らせ IT 用: お知らせの元になる<b>公開済みのブログ記事</b>を実 DB に作る（N9）。
 *
 * <p>一覧・ID 指定の経路は、{@code BLOG_POST} のお知らせを「公開済み・未削除の元が存在する」ときだけ扱う（fail-closed）。
 * 実在しない偽の sourceId でお知らせ行を作ると不在扱いになるため、元の公開状態が関心外の IT はここで作った記事を指す。
 * 呼び出し側の tx（{@code EntityManager}）で永続化する。後始末が要る IT は戻り値の ID を {@code blog_posts} から消す。</p>
 */
public final class AnnouncementSourceFixtures {

    private AnnouncementSourceFixtures() {
    }

    /** チームの公開済みブログ記事を作り、その ID を返す。 */
    public static long publishedTeamBlog(EntityManager em, Long teamId, Long authorId) {
        return insert(em, "team_id", teamId, authorId);
    }

    /** 組織の公開済みブログ記事を作り、その ID を返す。 */
    public static long publishedOrgBlog(EntityManager em, Long organizationId, Long authorId) {
        return insert(em, "organization_id", organizationId, authorId);
    }

    /** スコープ種別に応じた公開済みブログ記事を作り、その ID を返す（TEAM / ORGANIZATION のみ）。 */
    public static long publishedBlog(EntityManager em, AnnouncementScopeType scopeType, Long scopeId, Long authorId) {
        return switch (scopeType) {
            case TEAM -> publishedTeamBlog(em, scopeId, authorId);
            case ORGANIZATION -> publishedOrgBlog(em, scopeId, authorId);
            default -> throw new IllegalArgumentException("ブログ記事のスコープは TEAM / ORGANIZATION のみ: " + scopeType);
        };
    }

    private static long insert(EntityManager em, String scopeColumn, Long scopeId, Long authorId) {
        BlogPostEntity.BlogPostEntityBuilder builder = BlogPostEntity.builder().authorId(authorId)
                .title("お知らせ元の記事").slug("annsrc-" + UUID.randomUUID()).body("本文")
                .postType(PostType.BLOG).visibility(Visibility.MEMBERS_ONLY).status(PostStatus.PUBLISHED)
                .publishedAt(LocalDateTime.now().minusDays(1));
        BlogPostEntity post = "team_id".equals(scopeColumn)
                ? builder.teamId(scopeId).build() : builder.organizationId(scopeId).build();
        em.persist(post);
        em.flush();
        return post.getId();
    }

    /**
     * 公開済みブログ記事を INSERT ... SELECT でまとめて作るときの列リスト（{@code ddl-auto=create} の試験 DB は
     * 列の既定値を持たないため、NOT NULL 列をすべて明示する）。{@link #BULK_PUBLISHED_BLOG_VALUES} と対で使う。
     */
    public static final String BULK_PUBLISHED_BLOG_COLUMNS = "team_id, author_id, title, slug, body, post_type, "
            + "visibility, priority, status, published_at, pinned, allow_comments, target_type, "
            + "cross_post_to_timeline, view_count, reading_time_minutes, version, public_visible, "
            + "is_ranch_publication_historical, is_ranch_publication_observed, is_publication_history_known, "
            + "created_at, updated_at";

    /**
     * {@link #BULK_PUBLISHED_BLOG_COLUMNS} に対応する SELECT 句の値。{@code %1$s}=team_id 式、{@code %2$s}=author_id 式、
     * {@code %3$s}=title 式、{@code %4$s}=slug 式。
     */
    public static final String BULK_PUBLISHED_BLOG_VALUES = "%1$s, %2$s, %3$s, %4$s, '本文', 'BLOG', "
            + "'MEMBERS_ONLY', 'NORMAL', 'PUBLISHED', NOW() - INTERVAL 1 DAY, 0, 0, 'ALL', "
            + "0, 0, 0, 1, 1, 0, 0, 0, NOW(), NOW()";
}
