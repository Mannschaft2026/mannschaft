package com.mannschaft.app.supportaccess;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.port.SupportAccessOperatorAffiliationPort;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 戦役Z-B 試練: 代理ヘッダーの毎リクエスト検証 {@link SupportAccessGrantValidator}。
 *
 * <p>S1（承認なし・PENDING・DENIED・期限切れ・取消・撤回は 002、キャッシュなし）、S2（閲覧未開始・終了済みは 002）、
 * S5（本人の状態ゲート: ACTIVE 以外・退会手続中は 002）、S6（他の運営者は 001）、S7（所属する運営者は 010）、
 * S9（保護者の承認が揃っていない許可は無効）。</p>
 */
@DisplayName("戦役Z-B SupportAccessGrantValidator 単体テスト")
class SupportAccessGrantValidatorTest {

    private static final Long OPERATOR_ID = 9001L;
    private static final Long TARGET_ID = 42L;
    private static final UUID GRANT_ID = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

    private SupportAccessGrantRepository grantRepository;
    private SupportAccessUserDirectory userDirectory;
    private SupportAccessOperatorAffiliationPort affiliationPort;
    private SupportAccessGrantValidator validator;

    @BeforeEach
    void setUp() {
        grantRepository = mock(SupportAccessGrantRepository.class);
        userDirectory = mock(SupportAccessUserDirectory.class);
        affiliationPort = mock(SupportAccessOperatorAffiliationPort.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        validator = new SupportAccessGrantValidator(grantRepository, userDirectory, affiliationPort, clock);
        lenient().when(userDirectory.findUser(TARGET_ID)).thenReturn(Optional.of(activeTarget()));
        lenient().when(affiliationPort.isAffiliatedWithAnyTenant(OPERATOR_ID)).thenReturn(false);
    }

    private static SupportAccessUserSnapshot activeTarget() {
        return new SupportAccessUserSnapshot(TARGET_ID, "本人", "ACTIVE", false, false);
    }

    /** 承認済み・閲覧開始済み・期限内の有効な grant。 */
    private static SupportAccessGrantEntity.SupportAccessGrantEntityBuilder<?, ?> validGrant() {
        return SupportAccessGrantEntity.builder()
                .id(GRANT_ID)
                .operatorUserId(OPERATOR_ID)
                .targetUserId(TARGET_ID)
                .status(SupportAccessStatus.APPROVED)
                .requestReason("問い合わせ #123 の表示崩れを確認するため")
                .requestedAt(NOW.minus(java.time.Duration.ofHours(2)))
                .requestExpiresAt(NOW.plus(java.time.Duration.ofHours(70)))
                .decidedAt(NOW.minus(java.time.Duration.ofHours(1)))
                .expiresAt(NOW.plus(java.time.Duration.ofHours(23)))
                .activeSessionStartedAt(NOW.minus(java.time.Duration.ofMinutes(5)))
                .guardianApprovalRequired(false);
    }

    private static String codeOf(Throwable t) {
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).getErrorCode().getCode();
    }

    private void givenGrant(SupportAccessGrantEntity grant) {
        given(grantRepository.findById(GRANT_ID)).willReturn(Optional.of(grant));
    }

    @Test
    @DisplayName("S1 承認済み・閲覧開始済み・期限内なら運営者・本人・期限を持つ文脈を返す")
    void S1_有効なgrantは文脈を返す() {
        givenGrant(validGrant().build());

        SupportAccessContext context = validator.validate(OPERATOR_ID, GRANT_ID);

        assertThat(context.grantId()).isEqualTo(GRANT_ID);
        assertThat(context.operatorUserId()).isEqualTo(OPERATOR_ID);
        assertThat(context.targetUserId()).isEqualTo(TARGET_ID);
        assertThat(context.expiresAt()).isEqualTo(NOW.plus(java.time.Duration.ofHours(23)));
    }

    @Test
    @DisplayName("S1/S6 grant が無ければ 001")
    void S1_存在しないgrantは001() {
        given(grantRepository.findById(GRANT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_001"));
    }

    @ParameterizedTest(name = "S1 状態 {0} は 002")
    @EnumSource(value = SupportAccessStatus.class, names = {"PENDING", "DENIED", "REVOKED", "EXPIRED", "CANCELLED"})
    @DisplayName("S1 APPROVED 以外（PENDING・DENIED・取消・期限切れ・撤回）は 002")
    void S1_承認済み以外は002(SupportAccessStatus status) {
        givenGrant(validGrant().status(status).build());

        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));
    }

    @Test
    @DisplayName("S1 状態が APPROVED のままでも期限（expires_at）を過ぎていれば 002（バッチを待たず時刻で即時判定）")
    void S1_期限切れは時刻で即時002() {
        givenGrant(validGrant().expiresAt(NOW.minus(java.time.Duration.ofSeconds(1))).build());

        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));
    }

    @Test
    @DisplayName("S1 期限ちょうど（now == expires_at）は無効（now < expires_at のときだけ有効）")
    void S1_期限ちょうどは無効() {
        givenGrant(validGrant().expiresAt(NOW).build());

        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));
    }

    @Test
    @DisplayName("S1 revoked_at が入っていれば APPROVED でも 002")
    void S1_取消時刻があれば002() {
        givenGrant(validGrant().revokedAt(NOW.minus(java.time.Duration.ofMinutes(1))).build());

        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));
    }

    @Test
    @DisplayName("S1 キャッシュしない: 毎回 DB を読み、取消された直後の呼出しは 002")
    void S1_キャッシュしない() {
        given(grantRepository.findById(GRANT_ID))
                .willReturn(Optional.of(validGrant().build()))
                .willReturn(Optional.of(validGrant().status(SupportAccessStatus.REVOKED).revokedAt(NOW).build()));

        validator.validate(OPERATOR_ID, GRANT_ID);
        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));
        verify(grantRepository, times(2)).findById(GRANT_ID);
    }

    @Test
    @DisplayName("S2 閲覧を開始していない（active_session_started_at が null）grant は 002")
    void S2_閲覧未開始は002() {
        givenGrant(validGrant().activeSessionStartedAt(null).build());

        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));
    }

    @Test
    @DisplayName("S2 閲覧を終了した（active_session_ended_at が開始より後）grant は 002")
    void S2_閲覧終了後は002() {
        givenGrant(validGrant().activeSessionEndedAt(NOW.minus(java.time.Duration.ofMinutes(1))).build());

        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));
    }

    @ParameterizedTest(name = "S5 本人の状態 {0} は 002")
    @ValueSource(strings = {"FROZEN", "PENDING_PARENTAL_CONSENT", "PENDING_VERIFICATION", "ARCHIVED", "DECEASED"})
    @DisplayName("S5 本人の状態ゲート: ACTIVE 以外（凍結・保護者同意待ち 等）は毎回 002")
    void S5_本人の状態がACTIVE以外は002(String status) {
        givenGrant(validGrant().build());
        given(userDirectory.findUser(TARGET_ID))
                .willReturn(Optional.of(new SupportAccessUserSnapshot(TARGET_ID, "本人", status, false, false)));

        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));
    }

    @Test
    @DisplayName("S5/S15 本人が退会手続中なら 002")
    void S5_退会手続中は002() {
        givenGrant(validGrant().build());
        given(userDirectory.findUser(TARGET_ID))
                .willReturn(Optional.of(new SupportAccessUserSnapshot(TARGET_ID, "本人", "ACTIVE", true, false)));

        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));
    }

    @Test
    @DisplayName("S5 本人が存在しなければ 002（運営者の視点にも本人の視点にもならない）")
    void S5_本人が存在しなければ002() {
        givenGrant(validGrant().build());
        given(userDirectory.findUser(TARGET_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isIn("SUPPORT_ACCESS_001", "SUPPORT_ACCESS_002"));
    }

    @Test
    @DisplayName("S6 他の運営者の grant は存在を明かさず 001")
    void S6_他の運営者のgrantは001() {
        givenGrant(validGrant().operatorUserId(8888L).build());

        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_001"));
    }

    @Test
    @DisplayName("S7 テナントに所属する運営アカウントは有効な grant でも 010")
    void S7_所属する運営者は010() {
        givenGrant(validGrant().build());
        given(affiliationPort.isAffiliatedWithAnyTenant(OPERATOR_ID)).willReturn(true);

        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_010"));
    }

    @Test
    @DisplayName("S9 保護者の承認が要る grant で保護者がまだ承認していなければ 002（本人と保護者の両方が揃って初めて有効）")
    void S9_保護者未承認は002() {
        givenGrant(validGrant().guardianApprovalRequired(true).guardianUserId(55L).guardianDecidedAt(null).build());

        assertThatThrownBy(() -> validator.validate(OPERATOR_ID, GRANT_ID))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));
    }
}
