package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.VisibilityErrorCode;

/**
 * ブログ記事の存在オラクル対策（CMP-261124-2156）。
 *
 * <p>記事を ID・slug で引く経路で、閲覧不可（他人の下書き等）を {@code 403 VISIBILITY_001} のまま返すと
 * 「その ID・slug の記事が存在する」ことが判ってしまう。{@link ContentVisibilityChecker#assertCanView} の
 * 閲覧不可（{@link VisibilityErrorCode#VISIBILITY_001}）と対象不在（{@link VisibilityErrorCode#VISIBILITY_004}）を、
 * 記事の不存在と同一の {@link CmsErrorCode#POST_NOT_FOUND}（404 CMS_001）に畳む。
 * それ以外の {@link BusinessException}（{@code VISIBILITY_002}/{@code 003} 等）はそのまま伝播させる。</p>
 *
 * <p>{@code @Transactional(readOnly = true)} の中から呼ばれるため、捕捉した例外は握りつぶさず、
 * 直ちに新しい {@link BusinessException} を投げ直す（処理を継続しない）。</p>
 *
 * <p>名前の {@code *AccessGuard} は認可クラスの命名規約（{@code AuthzControllerGuardArchTest} が認可シグナルとして
 * 認識する）に従う。本クラスは F00 可視性判定そのものを呼ぶ認可の入口である。</p>
 *
 * <p>編集・削除など「見える記事に対し作者以外は 403」と定めた経路には使わない（本クラスは
 * 不可視と不存在の区別だけを消す）。</p>
 */
final class BlogPostViewAccessGuard {

    private BlogPostViewAccessGuard() {
    }

    /**
     * 記事の閲覧可否を判定し、閲覧不可・不在なら {@link CmsErrorCode#POST_NOT_FOUND} を投げる。
     *
     * @param checker      F00 可視性判定
     * @param postId       記事ID
     * @param viewerUserId 閲覧者ID（未認証は null）
     */
    static void assertCanViewOrNotFound(ContentVisibilityChecker checker, Long postId, Long viewerUserId) {
        try {
            checker.assertCanView(ReferenceType.BLOG_POST, postId, viewerUserId);
        } catch (BusinessException e) {
            if (isNotViewable(e)) {
                throw new BusinessException(CmsErrorCode.POST_NOT_FOUND);
            }
            throw e;
        }
    }

    private static boolean isNotViewable(BusinessException e) {
        return e.getErrorCode() == VisibilityErrorCode.VISIBILITY_001
                || e.getErrorCode() == VisibilityErrorCode.VISIBILITY_004;
    }
}
