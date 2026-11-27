package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.guardianship.GuardianshipAgePolicyRegistry;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService.SwitchVerdict;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.supportaccess.port.SupportAccessGuardianRequirement;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * F10.9 サポート閲覧の利用者ディレクトリ（supportaccess の port を auth ドメインが実装する）。
 *
 * <p>supportaccess が auth の Entity・Repository を越境参照しないための窓口。
 * 退会手続中の利用者は {@code UserEntity} の論理削除条件により取得できない（＝存在しない扱い）ため、
 * 呼び出し側は「見つからない」を拒否として扱う（fail-closed）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportAccessUserDirectoryAdapter implements SupportAccessUserDirectory {

    /** 未成年の境界（この年齢未満は保護者の承認が要る）。 */
    private static final int MINOR_AGE_YEARS = 18;

    private final UserRepository userRepository;
    private final ParentalConsentLinkRepository parentalConsentLinkRepository;
    private final GuardianshipSwitchService guardianshipSwitchService;
    private final GuardianshipAgePolicyRegistry agePolicyRegistry;
    private final AccessControlService accessControlService;
    private final Clock clock;

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
     * 保護者の承認の要否と、承認できる保護者（fail-closed。検分差し戻し対応）。
     *
     * <p><b>要否</b>は保護者リンクの有無ではなく<b>本人の年齢・後見の状態</b>から決める。後見切替の年齢ポリシー
     * （{@link GuardianshipAgePolicyRegistry}・国別）で「保護者が子に代わって操作できる段階」（{@code switchAllowed}）の
     * 子は保護者の承認が要る。<b>18歳未満の未成年は、後見の封印段階（自立扱いの年齢）でも一律で要る</b>
     * （マスター決定 2026-11-27）。生年月日が無い・読めない本人は、承認済みの保護者リンクがあれば安全側で要るとする
     * （リンクも無ければ成人の通常アカウントとして扱う）。以前は「リンクがあり後見の権原が有効な保護者がいれば要る」
     * だったため、リンクの無い（または保護者が停止した）子は保護者の承認なしで依頼・許可できた（fail-open）。</p>
     *
     * <p><b>承認できる保護者</b>は、承認済みの保護者リンクがあり、後見の権原（{@code evaluateSwitch}）が ALLOWED で、
     * 保護者自身が ACTIVE・退会手続中でないこと。既存の保護者同意（{@code ParentalConsentService}・いずれか1人の承認で
     * 子が ACTIVE になる）と同じく、いずれか1人の承認で足りる。</p>
     */
    @Override
    public SupportAccessGuardianRequirement resolveGuardianRequirement(Long userId) {
        if (userId == null) {
            return SupportAccessGuardianRequirement.requiredBy(List.of());
        }
        UserEntity child = userRepository.findById(userId).orElse(null);
        if (child == null) {
            // 本人が見つからない（退会手続中等）。依頼・許可は別の判定で拒否されるが、ここでも安全側に倒す
            return SupportAccessGuardianRequirement.requiredBy(List.of());
        }
        List<Long> linkedGuardians = parentalConsentLinkRepository
                .findByChildUserIdAndStatus(userId, ParentalConsentLinkStatus.APPROVED).stream()
                .map(ParentalConsentLinkEntity::getParentUserId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (!isGuardianshipRequired(child, !linkedGuardians.isEmpty())) {
            return SupportAccessGuardianRequirement.notRequired();
        }
        List<Long> eligible = linkedGuardians.stream()
                .filter(this::isActiveUser)
                .filter(parent -> guardianshipSwitchService.evaluateSwitch(parent, userId) == SwitchVerdict.ALLOWED)
                .toList();
        if (eligible.isEmpty()) {
            log.warn("サポート閲覧: 保護者の承認が要る本人に承認できる保護者がいない（fail-closed で拒否）userId={}, linked={}",
                    userId, linkedGuardians.size());
        }
        return SupportAccessGuardianRequirement.requiredBy(eligible);
    }

    @Override
    public Optional<String> findDeliverableEmail(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return userRepository.findById(userId)
                .map(UserEntity::getEmail)
                .filter(MfaMethodPolicy::isEmailDeliverable);
    }

    private boolean isGuardianshipRequired(UserEntity child, boolean hasApprovedGuardianLink) {
        LocalDate birthDate = parseBirthDate(child);
        if (birthDate == null) {
            return hasApprovedGuardianLink;
        }
        // マスター決定（2026-11-27）: 未成年（18歳未満）は後見の封印段階（自立扱いの年齢）でも一律で保護者の承認を求める
        if (birthDate.plusYears(MINOR_AGE_YEARS).isAfter(LocalDate.now(clock))) {
            return true;
        }
        return agePolicyRegistry.forCountry(child.getCountryCode()).resolve(birthDate, clock).switchAllowed();
    }

    private boolean isActiveUser(Long userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null && u.getStatus() == UserEntity.UserStatus.ACTIVE)
                .isPresent();
    }

    private static LocalDate parseBirthDate(UserEntity user) {
        String raw = user.getBirthDate();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
