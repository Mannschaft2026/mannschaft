package com.mannschaft.app.role.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261107-1833 PR3 I2: 再招待で付与するロール（設計 d）の単体試験。
 *
 * <p>非特権（MEMBER/SUPPORTER/GUEST）は引き継ぐ。ADMIN/DEPUTY_ADMIN（宛先付き招待では ROLE_009 になる特権）や、
 * 元の招待・ロールが引けない場合は MEMBER。一覧の roleName と発行で同じ判定を使う。</p>
 */
@DisplayName("InviteReissueRequestService#resolveGrantRole 単体テスト（CMP-261107-1833 PR3 I2）")
class InviteReissueGrantRoleTest {

    @ParameterizedTest
    @CsvSource({"MEMBER,MEMBER", "SUPPORTER,SUPPORTER", "GUEST,GUEST", "ADMIN,MEMBER", "DEPUTY_ADMIN,MEMBER",
            "SYSTEM_ADMIN,MEMBER", "UNKNOWN_ROLE,MEMBER"})
    @DisplayName("I2: 非特権は引き継ぎ、特権・未知のロールは MEMBER")
    void mapsRole(String original, String expected) {
        assertThat(InviteReissueRequestService.resolveGrantRole(original)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("I2: 元の招待・ロールが引けない（null）なら MEMBER")
    void missingIsMember(String original) {
        assertThat(InviteReissueRequestService.resolveGrantRole(original)).isEqualTo("MEMBER");
    }
}
