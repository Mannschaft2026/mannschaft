package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BlogSlugAutoConflictException#isSlugUniqueViolation}: slug 一意制約の違反を、制約名で（抽出できなければ
 * 原因連鎖のメッセージで）判定することを固定する。
 */
@DisplayName("BlogSlugAutoConflictException（slug 一意制約違反の判定）")
class BlogSlugAutoConflictExceptionTest {

    private static DataIntegrityViolationException hibernate(String constraintName, String sqlMessage) {
        SQLIntegrityConstraintViolationException sql =
                new SQLIntegrityConstraintViolationException(sqlMessage, "23000", 1062);
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement [" + sqlMessage + "]", sql,
                        constraintName));
    }

    private static String duplicate(String key) {
        return "Duplicate entry '1-114-1' for key '" + key + "'";
    }

    @ParameterizedTest
    @ValueSource(strings = {"uq_bp_slug_team_active", "blog_posts.uq_bp_slug_org_active", "uq_bp_slug_user_active"})
    @DisplayName("制約名が抽出できていれば制約名で判定する（「テーブル名.制約名」も可）")
    void 制約名で判定(String name) {
        assertThat(BlogSlugAutoConflictException.isSlugUniqueViolation(hibernate(name, duplicate(name)))).isTrue();
    }

    @Test
    @DisplayName("制約名が slug 以外なら、メッセージに関係なく slug 違反としない")
    void 制約名がslug以外() {
        assertThat(BlogSlugAutoConflictException.isSlugUniqueViolation(
                hibernate("blog_posts.fk_blog_posts_series", duplicate("blog_posts.fk_blog_posts_series")))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"blog_posts.uq_bp_slug_team_active", "blog_posts.uq_bp_slug_org_active",
            "uq_bp_slug_user_active"})
    @DisplayName("制約名が null のときは原因連鎖のメッセージに slug 制約名があるかで判定する")
    void 制約名nullはメッセージで判定(String key) {
        assertThat(BlogSlugAutoConflictException.isSlugUniqueViolation(hibernate(null, duplicate(key)))).isTrue();
    }

    @Test
    @DisplayName("制約名が null でメッセージが別の一意キーなら slug 違反としない")
    void 制約名nullで別キー() {
        assertThat(BlogSlugAutoConflictException.isSlugUniqueViolation(
                hibernate(null, duplicate("blog_posts.uq_bp_preview_token")))).isFalse();
    }

    @Test
    @DisplayName("制約名の前方一致（uq_bp_slug_team_active_x）は slug 違反としない")
    void 前方一致は除外() {
        assertThat(BlogSlugAutoConflictException.isSlugUniqueViolation(
                hibernate(null, duplicate("blog_posts.uq_bp_slug_team_active_x")))).isFalse();
    }

    @Test
    @DisplayName("Hibernate の例外を経ず JDBC の一意制約違反だけが連鎖にあってもメッセージで判定する")
    void JDBC例外だけでも判定() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("could not execute statement",
                new SQLIntegrityConstraintViolationException(duplicate("blog_posts.uq_bp_slug_team_active"),
                        "23000", 1062));
        assertThat(BlogSlugAutoConflictException.isSlugUniqueViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("制約違反の例外が連鎖に無ければ、メッセージに制約名があっても slug 違反としない")
    void 制約違反でない例外は除外() {
        RuntimeException failure = new RuntimeException(duplicate("blog_posts.uq_bp_slug_team_active"));
        assertThat(BlogSlugAutoConflictException.isSlugUniqueViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("応答は CMS_027（自動割り当ての混雑）で、明示指定の重複 CMS_005 とは文言を分ける")
    void 応答コード() {
        BlogSlugAutoConflictException e = new BlogSlugAutoConflictException(new RuntimeException("x"));
        assertThat(e.getErrorCode()).isEqualTo(CmsErrorCode.SLUG_AUTO_ASSIGN_CONFLICT);
        assertThat(e.getErrorCode().getCode()).isEqualTo("CMS_027");
        assertThat(e.getErrorCode().getMessage()).isNotEqualTo(CmsErrorCode.DUPLICATE_SLUG.getMessage())
                .contains("自動割り当て");
    }
}
