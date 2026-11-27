package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.dto.ActivityCommentResponse;
import com.mannschaft.app.activity.dto.CreateCommentRequest;
import com.mannschaft.app.activity.dto.UpdateCommentRequest;
import com.mannschaft.app.activity.entity.ActivityCommentEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityCommentRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 活動コメントサービス。コメントのCRUDを担当する。
 *
 * <p><b>トランザクションの張り方（CMP-261124-0159）</b>: 読み取り（{@link #listComments}）はクラス単位の読み取り
 * トランザクションを張らない。読み取りの認可は共通の門（{@code ScopeContentAccessGate}）を通り、門は在籍・ロールを
 * membership / role ドメインの表から引くため、活動ドメインのトランザクション入口に含めるとドメインをまたぐ
 * （CLAUDE.md 原則 5・番人 D-3T）。書き込みはメソッド単位の {@code @Transactional} のまま。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityCommentService {

    private final ActivityCommentRepository commentRepository;
    private final ActivityResultRepository resultRepository;
    private final ActivityMapper activityMapper;
    private final ActivityScopeAccessGuard scopeAccessGuard;

    /**
     * コメント一覧を取得する。
     *
     * <p>認可: コメントが紐づく活動記録のスコープ会員のみ閲覧可（非会員は 403 = COMMON_002）。
     * 他スコープ会員によるコメント列挙（IDOR）を封じる。</p>
     */
    public List<ActivityCommentResponse> listComments(Long activityId, Long userId) {
        ActivityResultEntity activity = findActivityOrThrow(activityId);
        checkScopeContentAccess(userId, activity);
        return activityMapper.toCommentResponseList(
                commentRepository.findByActivityResultIdOrderByCreatedAtAsc(activityId));
    }

    /**
     * コメントを作成する。
     *
     * <p>認可: コメント対象の活動記録のスコープ会員のみ投稿可（非会員は 403 = COMMON_002）。</p>
     */
    @Transactional
    public ActivityCommentResponse createComment(Long activityId, Long userId, CreateCommentRequest request) {
        ActivityResultEntity activity = findActivityOrThrow(activityId);
        // 中身を返さない書き込み（応答は投稿した本人のコメントだけ）。CMP-261124-0159 F00 §20.5.1
        scopeAccessGuard.checkMembershipForWrite(userId, activity.getScopeType(), activity.getScopeId());
        ActivityCommentEntity entity = ActivityCommentEntity.builder()
                .activityResultId(activityId)
                .userId(userId)
                .body(request.getBody())
                .build();

        ActivityCommentEntity saved = commentRepository.save(entity);
        log.info("コメント作成: commentId={}, activityId={}", saved.getId(), activityId);
        return activityMapper.toCommentResponse(saved);
    }

    /**
     * コメントを更新する。
     */
    @Transactional
    public ActivityCommentResponse updateComment(Long commentId, Long userId, UpdateCommentRequest request) {
        ActivityCommentEntity entity = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.COMMENT_NOT_FOUND));

        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException(ActivityErrorCode.NOT_AUTHOR);
        }

        entity.update(request.getBody());
        ActivityCommentEntity saved = commentRepository.save(entity);
        log.info("コメント更新: commentId={}", commentId);
        return activityMapper.toCommentResponse(saved);
    }

    /**
     * コメントを論理削除する。
     *
     * <p>認可: 本人または当該スコープの管理者（ADMIN/DEPUTY_ADMIN）のみ削除可。
     * 他スコープ会員・他人のコメントを削除しようとした場合は 403（COMMON_002）。
     * {@code updateComment} が本人限定であるのに対し、削除は運用上「本人 or 管理者」を許容する。</p>
     */
    @Transactional
    public void deleteComment(Long commentId, Long userId) {
        ActivityCommentEntity entity = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.COMMENT_NOT_FOUND));

        // コメント → 活動記録 → スコープ を辿り、本人 or 管理者を検証
        ActivityResultEntity activity = findActivityOrThrow(entity.getActivityResultId());
        scopeAccessGuard.checkOwnerOrAdmin(
                userId, entity.getUserId(), activity.getScopeType(), activity.getScopeId());

        entity.softDelete();
        commentRepository.save(entity);
        log.info("コメント削除: commentId={}", commentId);
    }

    /**
     * 活動記録エンティティを取得する。存在しない場合は例外をスローする。
     */
    private ActivityResultEntity findActivityOrThrow(Long activityId) {
        return resultRepository.findById(activityId)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.ACTIVITY_NOT_FOUND));
    }

    /**
     * 活動記録のスコープの中身を読めることを検証する（非公開チームでは MEMBER 以上。CMP-261124-0159 F00 §20.3）。
     *
     * <p>判定はスコープ種別を網羅的にディスパッチする {@link ActivityScopeAccessGuard} に委譲する。</p>
     */
    private void checkScopeContentAccess(Long userId, ActivityResultEntity activity) {
        scopeAccessGuard.checkScopeContentAccess(userId, activity.getScopeType(), activity.getScopeId());
    }
}
