package com.mannschaft.app.supportaccess.port;

import java.util.Optional;

/** F10.9 利用者の状態・保護者の解決（実装役が auth/guardianship の Service へ委譲する）。 */
public interface SupportAccessUserDirectory {

    Optional<SupportAccessUserSnapshot> findUser(Long userId);

    /**
     * 本人に保護者の承認が要るか（年齢・後見の状態から判定）と、承認できる保護者（有効な保護者リンク・
     * 後見の権原が有効・保護者が ACTIVE）。要るのに解決できない場合は {@link SupportAccessGuardianRequirement#unresolvable()}。
     */
    SupportAccessGuardianRequirement resolveGuardianRequirement(Long userId);

    /** 通知メールの宛先（送れる登録アドレスが無ければ空）。 */
    Optional<String> findDeliverableEmail(Long userId);
}
