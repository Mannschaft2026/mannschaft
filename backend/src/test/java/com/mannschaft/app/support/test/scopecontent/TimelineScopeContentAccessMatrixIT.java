package com.mannschaft.app.support.test.scopecontent;

import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CMP-261124-0159 PR-D2: タイムライン（チームフィード・ピン留め・投稿詳細・返信・マイフィード・ユーザー投稿一覧）の
 * 親チーム上限 Matrix IT（F00 §20.9 AC-0159-12）。
 *
 * <p>非公開チームでは ADMIN / DEPUTY_ADMIN / MEMBER だけがチームの投稿を読める。在籍サポーター（是正前データ）・
 * ゲスト・部外者・他チーム・他組織は部外者と同一の拒否応答。マイフィード・ユーザー投稿一覧のように
 * 「所属チームの投稿を集める」経路は、拒否ではなく母集団から外れることを本文で確かめる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 PR-D2 timeline の親チーム上限 Matrix IT（AC-0159-12）")
class TimelineScopeContentAccessMatrixIT extends AbstractScopeContentAccessMatrixIT {

    @Autowired private TimelinePostRepository postRepository;
    @Autowired private PlatformTransactionManager tm;

    private static final String POST_TEXT = "上限試練のチーム投稿";

    private Long postId;

    @Override
    protected Path matrixFile() {
        return Paths.get("src", "test", "resources", "scope_content_matrix", "_timeline.yaml");
    }

    @Override
    protected String resolveParam(String name, String value) {
        return "@post".equals(value) ? String.valueOf(postId) : super.resolveParam(name, value);
    }

    @Override
    protected void seed(ScopeContentFixture fx) {
        postId = postRepository.save(TimelinePostEntity.builder().scopeType(PostScopeType.TEAM).scopeId(fx.teamId)
                .userId(fx.user(Actor.ADMIN)).content(POST_TEXT).status(PostStatus.PUBLISHED)
                .build()).getId();
    }

    /** 作成したテストデータだけを消す（フィクスチャ本体の後始末は基底クラス）。 */
    @AfterEach
    void deleteSeededRows() {
        if (fixture == null) {
            return;
        }
        new TransactionTemplate(tm).executeWithoutResult(t -> em.createNativeQuery(
                        "DELETE FROM timeline_posts WHERE scope_type = 'TEAM' AND scope_id = :id")
                .setParameter("id", fixture.teamId).executeUpdate());
    }

    @Test
    @DisplayName("AC-0159-12: マイフィードは上限を越える在籍者（サポーター）の母集団にチームの投稿を入れず、MEMBER には出す")
    void マイフィードは上限を越える在籍者に出さない() throws Exception {
        assertThat(body("/api/v1/timeline/my", Actor.MEMBER)).as("MEMBER には出る（本戦役前どおり）").contains(POST_TEXT);
        assertThat(body("/api/v1/timeline/my", Actor.SUPPORTER)).as("在籍サポーターには出さない").doesNotContain(POST_TEXT);
    }

    @Test
    @DisplayName("AC-0159-12: ユーザー投稿一覧（プロフィール）も上限を越える在籍者にチームの投稿を出さない")
    void ユーザー投稿一覧は上限を越える在籍者に出さない() throws Exception {
        String path = "/api/v1/timeline/users/" + fixture.user(Actor.ADMIN) + "/posts";
        assertThat(body(path, Actor.MEMBER)).as("MEMBER には出る").contains(POST_TEXT);
        assertThat(body(path, Actor.SUPPORTER)).as("在籍サポーターには出さない").doesNotContain(POST_TEXT);
    }

    /** 一覧本文（レスポンス形状に依存しないよう、本文に投稿の本文が含まれるかで判定する）。 */
    private String body(String path, Actor actor) throws Exception {
        MvcResult result = mvc.perform(get(path).with(user(String.valueOf(fixture.user(actor))))).andReturn();
        assertThat(result.getResponse().getStatus()).as("%s %s は 200（母集団の絞り込み）", actor, path).isEqualTo(200);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
