package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.common.BusinessException;
import org.hibernate.exception.ConstraintViolationException;

import java.util.Set;

/**
 * 自動採番した slug が、事前確認から INSERT までの間に別リクエストに確定され、生存行の slug 一意制約
 * （V267: {@code uq_bp_slug_*_active}）に違反したことを表す。
 *
 * <p>{@link BlogSlugConflictRetryAspect} が捕捉して採番からやり直す。再試行できない場合（呼び出し元の
 * トランザクションに参加している・上限到達）にそのまま伝播しても、{@code CMS_005}（409）として応答される。</p>
 */
public class BlogSlugAutoConflictException extends BusinessException {

    /** V267 で張った生存行の slug 一意制約。 */
    static final Set<String> SLUG_UNIQUE_CONSTRAINTS =
            Set.of("uq_bp_slug_team_active", "uq_bp_slug_org_active", "uq_bp_slug_user_active");

    public BlogSlugAutoConflictException(Throwable cause) {
        super(CmsErrorCode.DUPLICATE_SLUG, cause);
    }

    /** 原因連鎖に slug 一意制約の違反があるか（制約名で判定。MySQL は「テーブル名.制約名」で返す）。 */
    static boolean isSlugUniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return name != null && SLUG_UNIQUE_CONSTRAINTS.contains(name.substring(name.lastIndexOf('.') + 1));
            }
        }
        return false;
    }
}
