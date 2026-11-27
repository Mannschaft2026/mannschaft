package com.mannschaft.app.supportaccess.port;

import java.util.List;

/**
 * F10.9 本人のサポート閲覧に保護者の承認が要るか、要るなら承認できる保護者は誰か。
 *
 * <p>判定は fail-closed: 保護者が要る（年齢・後見の状態から）のに承認できる保護者が1人もいない
 * （リンク無し・保護者の停止・退会手続中・後見の権原切れ）なら {@code required=true} かつ
 * {@code eligibleGuardianUserIds} が空になり、呼び出し側は依頼・許可を拒否する（SUPPORT_ACCESS_005）。</p>
 *
 * @param required                保護者の承認が要るか
 * @param eligibleGuardianUserIds 承認できる保護者（userId 昇順）。既存の保護者同意と同じく、いずれか1人の承認で足りる
 */
public record SupportAccessGuardianRequirement(boolean required, List<Long> eligibleGuardianUserIds) {

    public SupportAccessGuardianRequirement {
        eligibleGuardianUserIds = eligibleGuardianUserIds == null ? List.of() : List.copyOf(eligibleGuardianUserIds);
    }

    public static SupportAccessGuardianRequirement notRequired() {
        return new SupportAccessGuardianRequirement(false, List.of());
    }

    public static SupportAccessGuardianRequirement requiredBy(List<Long> eligibleGuardianUserIds) {
        return new SupportAccessGuardianRequirement(true, eligibleGuardianUserIds);
    }

    /** 保護者が要るのに、承認できる保護者がいない（fail-closed で拒否すべき）。 */
    public boolean unresolvable() {
        return required && eligibleGuardianUserIds.isEmpty();
    }
}
