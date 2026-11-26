package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService.SwitchVerdict;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Optional;

/**
 * F10.9 サポート閲覧の利用者ディレクトリ（supportaccess の port を auth ドメインが実装する）。
 *
 * <p>supportaccess が auth の Entity・Repository を越境参照しないための窓口。
 * 退会手続中の利用者は {@code UserEntity} の論理削除条件により取得できない（＝存在しない扱い）ため、
 * 呼び出し側は「見つからない」を拒否として扱う（fail-closed）。</p>
 */
@Component
@RequiredArgsConstructor
public class SupportAccessUserDirectoryAdapter implements SupportAccessUserDirectory {

    private final UserRepository userRepository;
    private final ParentalConsentLinkRepository parentalConsentLinkRepository;
    private final GuardianshipSwitchService guardianshipSwitchService;
    private final AccessControlService accessControlService;

    @Override
    public Optional<SupportAccessUserSnapshot> findUser(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return userRepository.findById(userId)
                .map(user -> new SupportAccessUserSnapshot(
                        user.getId(),
                        user.getDisplayName(),
                        user.getStatus().name(),
                        user.getDeletedAt() != null,
                        accessControlService.isSystemAdmin(user.getId())));
    }

    /**
     * 未成年・後見下の本人に承認が要る保護者の userId。承認済みの保護者リンクのうち、
     * 後見切替の権原（年齢ゲート含む）がいま有効なものを1人選ぶ（userId の昇順で先頭）。
     */
    @Override
    public Optional<Long> findRequiredGuardianUserId(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return parentalConsentLinkRepository.findByChildUserIdAndStatus(userId, ParentalConsentLinkStatus.APPROVED).stream()
                .map(ParentalConsentLinkEntity::getParentUserId)
                .filter(parent -> parent != null)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .filter(parent -> guardianshipSwitchService.evaluateSwitch(parent, userId) == SwitchVerdict.ALLOWED)
                .findFirst();
    }
}
