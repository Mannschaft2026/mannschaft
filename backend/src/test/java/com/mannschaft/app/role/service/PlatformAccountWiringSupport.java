package com.mannschaft.app.role.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配線試験の共通部品（戦役 Z-A PR2 再検分指摘 1a）。
 *
 * <p>目的: 「運営アカウントのテナント所属禁止」の判定が、各入口の Controller で<b>実際に置かれている</b>ことを
 * CI で確かめる。取引内の拒否は DB を読まず、判定が無ければ {@link IllegalStateException}（500）で止まるため、
 * Service を丸ごとモックして判定が素通りする従来の Controller 試験では、判定の置き忘れを検出できない。</p>
 *
 * <p>方式: <b>本物の {@link PlatformAccountAffiliationPolicy}</b> に UserRoleRepository のモック
 * （運営ロールの存在判定だけを差し替え）を差し、Controller を本物のまま呼ぶ。Service は、本番の取引内の拒否と同じく
 * {@link PlatformAccountAffiliationPolicy#assertJudgedAffiliatable} を<b>本番 Service が判定する利用者 ID</b>に対して
 * 呼ぶスタブ（{@link #servicePassingThrough}）に差し替える。Controller が判定を置いていなければ
 * {@link IllegalStateException}、別の ID を判定していれば（例: 申請者でなく操作者）運営アカウント宛ての拒否が出ない。</p>
 */
public final class PlatformAccountWiringSupport {

    /** 一般利用者（運営ロールを持たない）の操作者。 */
    public static final Long GENERAL_USER = 100L;
    /** もう一人の一般利用者（申請者・招集先など）。 */
    public static final Long OTHER_GENERAL_USER = 101L;
    /** 運営アカウント（プラットフォームレベルの SYSTEM_ADMIN）。 */
    public static final Long PLATFORM_USER = 900L;

    private PlatformAccountWiringSupport() {
    }

    /**
     * 本物の Policy に、{@link #PLATFORM_USER} だけを運営アカウントと答える UserRoleRepository のモックを差して返す。
     * 単一照会・一括照会の両方に答える（{@code judge} は件数により使い分ける）。
     */
    public static PlatformAccountAffiliationPolicy realPolicy() {
        UserRoleRepository repository = mock(UserRoleRepository.class);
        Set<Long> platform = Set.of(PLATFORM_USER);
        org.mockito.Mockito.lenient().when(repository.existsPlatformAdminRoleByUserId(any()))
                .thenAnswer(inv -> platform.contains((Long) inv.getArgument(0)) ? 1L : 0L);
        org.mockito.Mockito.lenient().when(repository.findPlatformAdminUserIdsIn(any()))
                .thenAnswer(inv -> ((Collection<Long>) inv.getArgument(0)).stream()
                        .filter(platform::contains).toList());
        return new PlatformAccountAffiliationPolicy(repository);
    }

    /** 認証済みユーザーを設定する（{@code SecurityUtils.getCurrentUserId()} が返す値）。 */
    public static void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    public static void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 本番 Service の取引内の拒否と同じ振る舞いをするスタブの応答。{@code judgedUserIds} の各 ID について
     * {@link PlatformAccountAffiliationPolicy#assertJudgedAffiliatable} を呼び（運営なら 403、判定が無ければ
     * {@link IllegalStateException}）、通れば {@code result} を返す。
     */
    public static org.mockito.stubbing.Answer<Object> servicePassingThrough(Object result, Long... judgedUserIds) {
        return inv -> {
            for (Long id : judgedUserIds) {
                PlatformAccountAffiliationPolicy.assertJudgedAffiliatable(id);
            }
            return result;
        };
    }

    /** 判定の対象を呼び出し時に決めたいときの版（申請者の ID を Service が行から引く経路など）。 */
    public static org.mockito.stubbing.Answer<Object> servicePassingThrough(
            Object result, java.util.function.Supplier<Collection<Long>> judgedUserIds) {
        return inv -> {
            for (Long id : judgedUserIds.get()) {
                PlatformAccountAffiliationPolicy.assertJudgedAffiliatable(id);
            }
            return result;
        };
    }

    /**
     * 運営アカウントの所属禁止（MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN）で拒否され、HTTP では 403 になることを確かめる。
     */
    public static void assertForbiddenAsPlatformAccount(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
                    assertThat(new GlobalExceptionHandler(new StaticMessageSource())
                            .handleBusinessException(be, new MockHttpServletRequest())
                            .getStatusCode().value()).isEqualTo(403);
                });
    }
}
