package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import com.mannschaft.app.publicview.dto.PublicPostDetail;
import com.mannschaft.app.publicview.visibility.DisplayIdentity;
import com.mannschaft.app.publicview.visibility.IdentityVisibilityResolver;
import com.mannschaft.app.publicview.visibility.ViewerContext;
import com.mannschaft.app.publicview.visibility.ViewerStatus;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-A PR4（試練）: 公開投稿詳細の有料記事ゲートから SYSTEM_ADMIN の素通しを撤去する受け入れテスト（AC7）。
 *
 * <p>{@code PublicPostQueryService#applyPaywallToPublicDetail} の
 * {@code viewerContext.status() == ViewerStatus.SYSTEM_ADMIN} を撤去し、運営アカウントも有料ゲートに従う。
 * 運営アカウントの本文確認は Z-B（本人承認つき代理ログイン）や運営画面で行う（テナントの公開経路では行わない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-A PR4: 公開投稿の有料ゲートの SYSTEM_ADMIN 素通し撤去（AC7）")
class PublicPostQueryServicePaywallSysAdminBypassRemovalTest {

    @Mock private BlogPostRepository blogPostRepository;
    @Mock private UserRepository userRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private IdentityVisibilityResolver identityVisibilityResolver;
    @Mock private MediaUrlResolver mediaUrlResolver;
    @Mock private PaymentGateService paymentGateService;
    @Mock private BlogBodyMediaResolver blogBodyMediaResolver;
    @InjectMocks private PublicPostQueryService service;

    private static final Long TEAM_ID = 100L;
    private static final Long POST_ID = 500L;
    private static final Long AUTHOR_ID = 88L;
    private static final Long SYSADMIN = 900L;
    private static final String BODY = "有料本文フルテキスト";

    @BeforeEach
    void stubDetail() {
        TeamEntity team = TeamEntity.builder().name("公開チーム").build();
        ReflectionTestUtils.setField(team, "id", TEAM_ID);
        BlogPostEntity post = BlogPostEntity.builder()
                .teamId(TEAM_ID).authorId(AUTHOR_ID).title("タイトル").slug("slug").body(BODY).excerpt("要約")
                .build();
        ReflectionTestUtils.setField(post, "id", POST_ID);
        UserEntity author = UserEntity.builder()
                .email("a@example.com").passwordHash("h").lastName("山田").firstName("太郎").displayName("yamada")
                .build();
        ReflectionTestUtils.setField(author, "id", AUTHOR_ID);

        given(teamRepository.findPublicTeamById(TEAM_ID)).willReturn(Optional.of(team));
        given(blogPostRepository.findPublicPostByTeamIdAndId(TEAM_ID, POST_ID)).willReturn(Optional.of(post));
        given(userRepository.findById(AUTHOR_ID)).willReturn(Optional.of(author));
        given(mediaUrlResolver.resolve(any())).willReturn(null);
        given(identityVisibilityResolver.resolveIdentityForViewer(any(), any(), any(), any()))
                .willReturn(new DisplayIdentity("投稿者", null, false, true));
        given(blogBodyMediaResolver.resolveBody(any(), any(), any(), any())).willAnswer(inv -> inv.getArgument(0));
    }

    private void gate(boolean accessible, boolean titleHidden) {
        given(paymentGateService.checkAccess(eq(ContentGateType.POST), eq(POST_ID), eq(SYSADMIN),
                any(ContentGateTarget.class)))
                .willReturn(new GateCheckResponse(accessible, titleHidden, List.of()));
    }

    /** 立場 SYSTEM_ADMIN の ViewerContext（列挙値が既に削除されていれば素通しは表現できないので本テストは不要）。 */
    private static ViewerContext systemAdminStatusContext() {
        Optional<ViewerStatus> sa = Arrays.stream(ViewerStatus.values())
                .filter(v -> v.name().equals("SYSTEM_ADMIN")).findFirst();
        assumeThat(sa).as("ViewerStatus.SYSTEM_ADMIN が削除済みなら素通しは表現できない").isPresent();
        return new ViewerContext(SYSADMIN, sa.get(), Set.of(), Set.of());
    }

    @Test
    @DisplayName("立場が SYSTEM_ADMIN でも未課金なら本文をマスクし、checkAccess で判定する")
    void systemAdminStatus_unpaid_bodyMasked() {
        gate(false, false);
        PublicPostDetail result = service.findPublicPostDetailByTeam(TEAM_ID, POST_ID, systemAdminStatusContext());
        assertThat(result.bodyHtml()).isNull();
        verify(paymentGateService).checkAccess(eq(ContentGateType.POST), eq(POST_ID), eq(SYSADMIN),
                any(ContentGateTarget.class));
    }

    @Test
    @DisplayName("立場が SYSTEM_ADMIN でも titleHidden・未課金なら 404（PUBLIC_003・存在秘匿）")
    void systemAdminStatus_titleHidden_404() {
        gate(false, true);
        assertThatThrownBy(() -> service.findPublicPostDetailByTeam(TEAM_ID, POST_ID, systemAdminStatusContext()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode()).isEqualTo("PUBLIC_003"));
    }

    @Test
    @DisplayName("回帰: 運営アカウントを nonMember として扱うと未課金で本文マスク（ViewerContextBuilder 撤去後の形）")
    void platformAccountAsNonMember_unpaid_bodyMasked() {
        gate(false, false);
        PublicPostDetail result = service.findPublicPostDetailByTeam(TEAM_ID, POST_ID,
                ViewerContext.nonMember(SYSADMIN));
        assertThat(result.bodyHtml()).isNull();
    }

    @Test
    @DisplayName("回帰: 課金済みなら運営アカウントでも全文")
    void platformAccount_paid_fullBody() {
        gate(true, false);
        PublicPostDetail result = service.findPublicPostDetailByTeam(TEAM_ID, POST_ID,
                ViewerContext.nonMember(SYSADMIN));
        assertThat(result.bodyHtml()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("番人: PublicPostQueryService・ViewerContextBuilder は ViewerStatus.SYSTEM_ADMIN と existsSystemAdminByUserId を参照しない")
    void noSystemAdminBranchInPublicviewSources() throws IOException {
        for (String f : List.of(
                "src/main/java/com/mannschaft/app/publicview/service/PublicPostQueryService.java",
                "src/main/java/com/mannschaft/app/publicview/service/ViewerContextBuilder.java")) {
            String src = com.mannschaft.app.common.architecture.JavaSourceScanningUtils.maskCommentsAndLiterals(
                    Files.readString(Path.of(f), StandardCharsets.UTF_8));
            assertThat(src).as(f).doesNotContain("ViewerStatus.SYSTEM_ADMIN")
                    .doesNotContain("existsSystemAdminByUserId")
                    .doesNotContain("ViewerContext.systemAdmin(");
        }
    }
}
