package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.common.BusinessException;
import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Set;

/**
 * 自動採番した slug が、事前確認から INSERT までの間に別リクエストに確定され、生存行の slug 一意制約
 * （V267: {@code uq_bp_slug_*_active}）に違反したことを表す。
 *
 * <p>{@link BlogSlugConflictRetryAspect} が捕捉して採番からやり直す。再試行できない場合（呼び出し元の
 * トランザクションに参加している）にそのまま伝播しても、{@code CMS_027}（409。利用者は slug を指定していないため
 * 明示指定の重複 {@code CMS_005} とは文言を分ける）として応答される。</p>
 */
public class BlogSlugAutoConflictException extends BusinessException {

    /** V267 で張った生存行の slug 一意制約。 */
    static final Set<String> SLUG_UNIQUE_CONSTRAINTS =
            Set.of("uq_bp_slug_team_active", "uq_bp_slug_org_active", "uq_bp_slug_user_active");

    public BlogSlugAutoConflictException(Throwable cause) {
        super(CmsErrorCode.SLUG_AUTO_ASSIGN_CONFLICT, cause);
    }

    /**
     * 原因連鎖に slug 一意制約の違反があるか。
     *
     * <p>Hibernate が抽出した制約名で判定する（MySQL は「テーブル名.制約名」で返す）。制約名を抽出できなかった
     * 場合（{@code getConstraintName()} が {@code null}、または Hibernate の例外を経ず JDBC の
     * {@link SQLIntegrityConstraintViolationException} だけが連鎖にある場合）は、原因連鎖のメッセージ
     * （MySQL の {@code Duplicate entry '…' for key 'blog_posts.uq_bp_slug_team_active'}）に制約名が含まれるかで判定する。</p>
     */
    static boolean isSlugUniqueViolation(Throwable failure) {
        boolean constraintViolation = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                if (name != null) {
                    return SLUG_UNIQUE_CONSTRAINTS.contains(name.substring(name.lastIndexOf('.') + 1));
                }
                constraintViolation = true;
            } else if (cause instanceof SQLIntegrityConstraintViolationException) {
                constraintViolation = true;
            }
        }
        return constraintViolation && messagesMentionSlugConstraint(failure);
    }

    private static boolean messagesMentionSlugConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && SLUG_UNIQUE_CONSTRAINTS.stream()
                    .anyMatch(constraint -> message.contains("'" + constraint + "'")
                            || message.contains("." + constraint + "'"))) {
                return true;
            }
        }
        return false;
    }
}
