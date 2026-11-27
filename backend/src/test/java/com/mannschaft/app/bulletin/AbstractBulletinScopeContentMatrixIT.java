package com.mannschaft.app.bulletin;

import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinCategoryRepository;
import com.mannschaft.app.bulletin.repository.BulletinReplyRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.support.test.scopecontent.AbstractScopeContentAccessMatrixIT;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CMP-261124-0159 PR-D1: 掲示板の Matrix IT の共通基底（非公開チームに、カテゴリ・スレッド・返信を 1 件ずつ置く）。
 */
abstract class AbstractBulletinScopeContentMatrixIT extends AbstractScopeContentAccessMatrixIT {

    @Autowired private BulletinCategoryRepository categoryRepository;
    @Autowired private BulletinThreadRepository threadRepository;
    @Autowired private BulletinReplyRepository replyRepository;
    @Autowired private PlatformTransactionManager bulletinTransactionManager;

    private Long categoryId;
    private Long threadId;

    @Override
    protected void seed(ScopeContentFixture fixture) {
        categoryId = categoryRepository.save(BulletinCategoryEntity.builder()
                .scopeType(ScopeType.TEAM).scopeId(fixture.teamId).name("PRD1 カテゴリ").description("PR-D1")
                .displayOrder(0).color("#3B82F6").postMinRole("MEMBER")
                .createdBy(fixture.user(ScopeContentFixture.Actor.ADMIN)).build()).getId();
        threadId = threadRepository.save(BulletinThreadEntity.builder()
                .categoryId(categoryId).scopeType(ScopeType.TEAM).scopeId(fixture.teamId)
                .authorId(fixture.user(ScopeContentFixture.Actor.MEMBER)).title("PRD1 スレッド")
                .body("PRD1 本文（権限なしに見せてはならない）").priority(Priority.INFO)
                .readTrackingMode(ReadTrackingMode.INDIVIDUAL).isArchived(false).build()).getId();
        replyRepository.save(BulletinReplyEntity.builder().threadId(threadId).depth(0)
                .authorId(fixture.user(ScopeContentFixture.Actor.MEMBER)).body("PRD1 返信").build());
    }

    @Override
    protected String resolveParam(String name, String value) {
        return switch (value) {
            case "@category" -> String.valueOf(categoryId);
            case "@thread" -> String.valueOf(threadId);
            default -> super.resolveParam(name, value);
        };
    }

    @AfterEach
    void deleteBulletinRows() {
        if (fixture == null) {
            return;
        }
        new TransactionTemplate(bulletinTransactionManager).executeWithoutResult(t -> {
            for (String table : new String[]{"bulletin_replies"}) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE thread_id = :id")
                        .setParameter("id", threadId).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM bulletin_threads WHERE scope_type = 'TEAM' AND scope_id = :id")
                    .setParameter("id", fixture.teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM bulletin_categories WHERE scope_type = 'TEAM' AND scope_id = :id")
                    .setParameter("id", fixture.teamId).executeUpdate();
        });
    }
}
