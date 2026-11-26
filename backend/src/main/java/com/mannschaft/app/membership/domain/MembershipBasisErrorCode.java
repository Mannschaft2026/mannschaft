package com.mannschaft.app.membership.domain;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F00.5 メンバーシップ基盤再設計のエラーコード定義。
 *
 * <p>設計書 §6.4 のエラーコード表に対応する。コード値は外部 API レスポンス互換のため
 * 設計書の値（{@code MEMBERSHIP_*}）をそのまま採用する。</p>
 *
 * <p>クラス名に "Basis" を含めているのは、既存の F02.1 QR 会員証機能の
 * {@link com.mannschaft.app.membership.MembershipErrorCode}（コード MEMBERSHIP_001〜022）
 * との単純名衝突を避けるため。両者はクラス名で区別し、外部に出るエラーコード文字列は
 * "MEMBERSHIP_*" を共有しているが衝突する値は存在しない（F02.1 は連番、本クラスは説明的キー）。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §6.4</p>
 */
@Getter
@RequiredArgsConstructor
public enum MembershipBasisErrorCode implements ErrorCode {

    /** scope_type と scope_id の組み合わせが不整合。 */
    MEMBERSHIP_INVALID_SCOPE("MEMBERSHIP_INVALID_SCOPE", "スコープ指定が不正です", Severity.WARN),

    /** role_kind が ENUM 範囲外。 */
    MEMBERSHIP_INVALID_ROLE_KIND("MEMBERSHIP_INVALID_ROLE_KIND", "メンバー区分が不正です", Severity.WARN),

    /** started_at > ended_at など期間が逆転。 */
    MEMBERSHIP_PERIOD_INVERTED("MEMBERSHIP_PERIOD_INVERTED", "期間の指定が逆転しています", Severity.WARN),

    /** 権限不足。 */
    MEMBERSHIP_NO_PERMISSION("MEMBERSHIP_NO_PERMISSION", "権限がありません", Severity.WARN),

    /** 最後の ADMIN を兼任しているため退会不可。 */
    MEMBERSHIP_LAST_ADMIN_BLOCKED("MEMBERSHIP_LAST_ADMIN_BLOCKED", "最後の管理者は退会できません", Severity.WARN),

    /** メンバーシップが存在しない。 */
    MEMBERSHIP_NOT_FOUND("MEMBERSHIP_NOT_FOUND", "メンバーシップが見つかりません", Severity.WARN),

    /** アクティブなメンバーシップが既に存在。 */
    MEMBERSHIP_ACTIVE_EXISTS("MEMBERSHIP_ACTIVE_EXISTS", "既にこのスコープに参加しています", Severity.WARN),

    /** 既に退会済。 */
    MEMBERSHIP_ALREADY_LEFT("MEMBERSHIP_ALREADY_LEFT", "既に退会済です", Severity.WARN),

    /** supporter_enabled が FALSE のスコープに SUPPORTER 自己登録試行。 */
    MEMBERSHIP_SUPPORTER_DISABLED("MEMBERSHIP_SUPPORTER_DISABLED",
            "このスコープはサポーター機能を有効化していません", Severity.WARN),

    /** ブロックリストに登録あり。 */
    MEMBERSHIP_BLOCKED("MEMBERSHIP_BLOCKED", "ブロックされているため参加できません", Severity.WARN),

    /** 同一 (membership, position) で現役の役職割当が既に存在。 */
    MEMBERSHIP_POSITION_ACTIVE_EXISTS("MEMBERSHIP_POSITION_ACTIVE_EXISTS",
            "既にこの役職が割り当てられています", Severity.WARN),

    /** 役職割当が存在しない。 */
    MEMBERSHIP_POSITION_NOT_FOUND("MEMBERSHIP_POSITION_NOT_FOUND",
            "役職割当が見つかりません", Severity.WARN),

    /** 役職カタログが存在しない。 */
    MEMBERSHIP_POSITION_CATALOG_NOT_FOUND("MEMBERSHIP_POSITION_CATALOG_NOT_FOUND",
            "役職カタログが見つかりません", Severity.WARN),

    /**
     * 運営アカウント（SYSTEM_ADMIN）はテナント（チーム・組織・委員会・村・サポーター）に所属できない（403）。
     *
     * <p>戦役 Z-A PR2（docs/security/03_role_authority_model.md §3.6、マスター決定 2026-11-26
     * 「運営は運営専用アカウント。個人アカウントとの併用禁止」）。判定は
     * {@code PlatformAccountAffiliationPolicy#assertAffiliatable} に一元化する。</p>
     *
     * <p>試練（テスト先行）で定数のみ先置きしたスタブ。403 の宣言に合わせた
     * {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} への登録は実装（出陣）で行う。</p>
     */
    MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN("MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN",
            "運営アカウントはチーム・組織・委員会に所属できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
