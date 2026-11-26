package com.mannschaft.app.supportaccess.port;

import java.util.Optional;

/** F10.9 利用者の状態・保護者の解決（実装役が auth/guardianship の Service へ委譲する）。 */
public interface SupportAccessUserDirectory {

    Optional<SupportAccessUserSnapshot> findUser(Long userId);

    /** 未成年・後見下なら承認が必要な保護者の userId。成人で後見なしなら空。 */
    Optional<Long> findRequiredGuardianUserId(Long userId);
}
