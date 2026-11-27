package com.mannschaft.app.supportaccess.port;

/**
 * F10.9 いまのリクエストが後見切替（保護者が子として操作）・代理入力（F14.1）の最中か。
 *
 * <p>許可・拒否・取消は本人（または保護者本人）の意思表示であり、子として切り替えた保護者や代理入力者が
 * 本人に代わって行ってはならない（設計 §1.3「後見切替・代理入力の最中は許可できない」）。
 * 実装は auth ドメインが {@code ProxyInputContext} を見て返す。</p>
 */
public interface SupportAccessActingAsDetector {

    /** 後見切替・代理入力の最中なら true。 */
    boolean isActingAsAnotherUser();
}
