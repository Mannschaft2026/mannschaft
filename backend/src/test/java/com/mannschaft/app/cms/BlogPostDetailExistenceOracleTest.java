package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostTagRepository;
import com.mannschaft.app.cms.service.BlogMediaAclService;
import com.mannschaft.app.cms.service.BlogMediaCopyService;
import com.mannschaft.app.cms.service.BlogPostRevisionService;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.cms.service.BlogPostShareService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.VisibilityErrorCode;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.ContentAccessState;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import com.mannschaft.app.publicview.service.PostAuthorSnapshotService;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役B「ブログ記事詳細の存在オラクル（CMP-261008-1701）」の Service 単体受け入れテスト（試練）。
 *
 * <p>{@link BlogPostService#getBySlug} は slug→記事の解決後に {@link ContentVisibilityChecker#assertCanView} を
 * 呼ぶが、閲覧不可の {@code VISIBILITY_001}（403）・{@code VISIBILITY_004}（404）を畳まずに伝播させている。
 * そのため「記事は在るが見られない」と「記事が無い」で応答が分かれ、slug の存在が漏れる（存在オラクル）。
 * 型紙 {@code BulletinReadFacade#assertReadable} に倣い、閲覧不可は不在と同じ {@link CmsErrorCode#POST_NOT_FOUND}
 * （CMS_001）に畳むことを固定する。{@code VISIBILITY_002}（不正 reference_type）・{@code VISIBILITY_003}
 * （内部 Resolver エラー）は閲覧可否ではなく基盤の故障なので畳まない（C6）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-261008-1701 ブログ記事詳細の存在オラクル（BlogPostService#getBySlug）")
class BlogPostDetailExistenceOracleTest {

    @Mock
    private BlogMediaAclService mediaAclService;
    @Mock
    private BlogMediaCopyService mediaCopyService;
    @Mock
    private BlogPostRepository postRepository;
    @Mock
    private BlogPostTagRepository postTagRepository;
    @Mock
    private CmsMapper cmsMapper;
    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;
    @Mock
    private BlogPostRevisionService revisionService;
    @Mock
    private BlogPostShareService shareService;
    @Mock
    private PostAuthorSnapshotService postAuthorSnapshotService;
    @Mock
    private TeamRepository teamRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private TeamService teamService;
    @Mock
    private OrganizationService organizationService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private PaymentGateService paymentGateService;
    @Mock
    private BlogBodyMediaResolver blogBodyMediaResolver;

    @InjectMocks
    private BlogPostService service;

    private static final Long TEAM_ID = 1L;
    private static final Long ORG_ID = 2L;
    private static final Long AUTHOR_ID = 100L;
    private static final Long POST_ID = 10L;
    private static final Long VIEWER_ID = 99L;
    private static final String SLUG = "secret-slug";

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUp() {
        // フルシャード実行で先行テストの SecurityContext が残置しても決定論的になるよう閲覧者を固定する。
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
        lenient().when(paymentGateService.checkAccess(any(), any(), any(), any(ContentGateTarget.class)))
                .thenReturn(new GateCheckResponse(true, false, List.of()));
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    // ─── フィクスチャ ───

    private BlogPostEntity teamPost() {
        return post(TEAM_ID, null, null);
    }

    private BlogPostEntity post(Long teamId, Long organizationId, Long userId) {
        BlogPostEntity entity = BlogPostEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .userId(userId)
                .authorId(AUTHOR_ID)
                .title("限定記事")
                .slug(SLUG)
                .body("本文")
                .postType(PostType.BLOG)
                .visibility(Visibility.MEMBERS_ONLY)
                .priority(PostPriority.NORMAL)
                .status(PostStatus.PUBLISHED)
                .readingTimeMinutes((short) 1)
                .build();
        ReflectionTestUtils.setField(entity, "id", POST_ID);
        return entity;
    }

    private BlogPostResponse responseWithBody() {
        return BlogPostResponse.builder()
                .id(POST_ID)
                .scope(new BlogPostResponse.BlogPostScopeDto(TEAM_ID, null, null, AUTHOR_ID))
                .content(new BlogPostResponse.BlogPostContentDto(
                        "限定記事", SLUG, null, "要約", null))
                .stats(new BlogPostResponse.BlogPostStatisticsDto(null, null, false, 0))
                .build();
    }

    private void denyView(VisibilityErrorCode code, Long viewer) {
        willThrow(new BusinessException(code))
                .given(contentVisibilityChecker).assertCanView(ReferenceType.BLOG_POST, POST_ID, viewer);
    }

    private static String codeOf(BusinessException ex) {
        return ex.getErrorCode().getCode();
    }

    // ─── C2 / C7: 閲覧不可は CMS_001 に畳まれ、後段の処理に進まない ───

    @Nested
    @DisplayName("C2 閲覧不可記事は不在と同じ 404 CMS_001 に畳む")
    class FoldInvisible {

        @Test
        @DisplayName("C2: チーム記事で assertCanView が VISIBILITY_001（403）→ CMS_001 になる")
        void C2_チーム記事_VISIBILITY_001はCMS_001() {
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, SLUG)).willReturn(Optional.of(teamPost()));
            denyView(VisibilityErrorCode.VISIBILITY_001, VIEWER_ID);

            BusinessException ex = catchThrowableOfType(
                    () -> service.getBySlug(TEAM_ID, null, null, SLUG), BusinessException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getErrorCode()).isEqualTo(CmsErrorCode.POST_NOT_FOUND);
        }

        @Test
        @DisplayName("C2: チーム記事で assertCanView が VISIBILITY_004（404）→ CMS_001 になる")
        void C2_チーム記事_VISIBILITY_004はCMS_001() {
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, SLUG)).willReturn(Optional.of(teamPost()));
            denyView(VisibilityErrorCode.VISIBILITY_004, VIEWER_ID);

            BusinessException ex = catchThrowableOfType(
                    () -> service.getBySlug(TEAM_ID, null, null, SLUG), BusinessException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getErrorCode()).isEqualTo(CmsErrorCode.POST_NOT_FOUND);
        }

        @Test
        @DisplayName("C2: 組織記事で VISIBILITY_001 → CMS_001 になる")
        void C2_組織記事_VISIBILITY_001はCMS_001() {
            given(postRepository.findByOrganizationIdAndSlug(ORG_ID, SLUG))
                    .willReturn(Optional.of(post(null, ORG_ID, null)));
            denyView(VisibilityErrorCode.VISIBILITY_001, VIEWER_ID);

            BusinessException ex = catchThrowableOfType(
                    () -> service.getBySlug(null, ORG_ID, null, SLUG), BusinessException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getErrorCode()).isEqualTo(CmsErrorCode.POST_NOT_FOUND);
        }

        @Test
        @DisplayName("C2/C5: 個人記事で VISIBILITY_001 → CMS_001 になる（個人経路の getBySlug(null,null,userId)）")
        void C2_個人記事_VISIBILITY_001はCMS_001() {
            given(postRepository.findByUserIdAndSlug(AUTHOR_ID, SLUG))
                    .willReturn(Optional.of(post(null, null, AUTHOR_ID)));
            denyView(VisibilityErrorCode.VISIBILITY_001, VIEWER_ID);

            BusinessException ex = catchThrowableOfType(
                    () -> service.getBySlug(null, null, AUTHOR_ID, SLUG), BusinessException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getErrorCode()).isEqualTo(CmsErrorCode.POST_NOT_FOUND);
        }

        @Test
        @DisplayName("C2: 未ログイン（viewer=null）で MEMBERS_ONLY 記事が VISIBILITY_001 → 403 ではなく CMS_001 になる")
        void C2_未ログイン_VISIBILITY_001はCMS_001() {
            securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(null);
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, SLUG)).willReturn(Optional.of(teamPost()));
            denyView(VisibilityErrorCode.VISIBILITY_001, null);

            BusinessException ex = catchThrowableOfType(
                    () -> service.getBySlug(TEAM_ID, null, null, SLUG), BusinessException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getErrorCode()).isEqualTo(CmsErrorCode.POST_NOT_FOUND);
        }

        @Test
        @DisplayName("C2: プレビュートークン付き取得（getBySlugWithPreviewToken）も VISIBILITY_001 → CMS_001 になる")
        void C2_プレビュートークン経路_VISIBILITY_001はCMS_001() {
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, SLUG)).willReturn(Optional.of(teamPost()));
            denyView(VisibilityErrorCode.VISIBILITY_001, VIEWER_ID);

            BusinessException ex = catchThrowableOfType(
                    () -> service.getBySlugWithPreviewToken(TEAM_ID, null, null, SLUG, "tok-123"),
                    BusinessException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getErrorCode()).isEqualTo(CmsErrorCode.POST_NOT_FOUND);
        }

        @Test
        @DisplayName("C3: 閲覧不可で投げる例外は、不在 slug で投げる例外とエラーコード・メッセージ引数・fieldErrors が等価")
        void C3_閲覧不可と不在の例外が等価() {
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, SLUG)).willReturn(Optional.of(teamPost()));
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, "no-such-slug")).willReturn(Optional.empty());
            denyView(VisibilityErrorCode.VISIBILITY_001, VIEWER_ID);

            BusinessException invisible = catchThrowableOfType(
                    () -> service.getBySlug(TEAM_ID, null, null, SLUG), BusinessException.class);
            BusinessException absent = catchThrowableOfType(
                    () -> service.getBySlug(TEAM_ID, null, null, "no-such-slug"), BusinessException.class);

            assertThat(absent).isNotNull();
            assertThat(invisible).isNotNull();
            assertThat(codeOf(invisible)).isEqualTo(codeOf(absent)).isEqualTo("CMS_001");
            assertThat(invisible.getErrorCode()).isSameAs(absent.getErrorCode());
            assertThat(invisible.getMessageArgs()).isEqualTo(absent.getMessageArgs());
            assertThat(invisible.getFieldErrors()).isEqualTo(absent.getFieldErrors());
            assertThat(invisible.getHttpStatusOverride()).isEqualTo(absent.getHttpStatusOverride());
        }

        @Test
        @DisplayName("C7: 閲覧不可で CMS_001 に畳むとき、応答生成・ペイウォール判定・本文メディア解決に進まない")
        void C7_閲覧不可は後段の副作用より先に例外() {
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, SLUG)).willReturn(Optional.of(teamPost()));
            denyView(VisibilityErrorCode.VISIBILITY_001, VIEWER_ID);

            BusinessException ex = catchThrowableOfType(
                    () -> service.getBySlug(TEAM_ID, null, null, SLUG), BusinessException.class);

            assertThat(ex).isNotNull();
            assertThat(codeOf(ex)).isEqualTo("CMS_001");
            verify(cmsMapper, never()).toBlogPostResponse(any(BlogPostEntity.class));
            verify(paymentGateService, never()).checkAccess(any(), any(), any(), any(ContentGateTarget.class));
            verify(blogBodyMediaResolver, never()).resolveBody(any(), any(), anyLong(), anyLong());
            verify(postRepository, never()).save(any(BlogPostEntity.class));
        }
    }

    // ─── C6: 基盤の故障は畳まない（回帰固定） ───

    @Nested
    @DisplayName("C6 畳むのは VISIBILITY_001/004 のみ（VISIBILITY_002/003 は畳まない）")
    class DoNotFoldOthers {

        @ParameterizedTest(name = "C6: {0} はそのまま伝播し CMS_001 にならない")
        @EnumSource(value = VisibilityErrorCode.class, names = {"VISIBILITY_002", "VISIBILITY_003"})
        @DisplayName("C6: VISIBILITY_002/003 はそのまま伝播し CMS_001 にならない")
        void C6_VISIBILITY_002と003は畳まない(VisibilityErrorCode code) {
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, SLUG)).willReturn(Optional.of(teamPost()));
            denyView(code, VIEWER_ID);

            BusinessException ex = catchThrowableOfType(
                    () -> service.getBySlug(TEAM_ID, null, null, SLUG), BusinessException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getErrorCode()).isEqualTo(code);
        }
    }

    // ─── C1 / C8: 権限者は従来どおり取得でき、ペイウォールの挙動も変わらない（回帰固定） ───

    @Nested
    @DisplayName("C1/C8 権限者の取得とペイウォール LOCKED は従来どおり（回帰）")
    class Regression {

        @Test
        @DisplayName("C1: assertCanView を通過した閲覧者（作者・管理者・メンバー）は記事を取得できる")
        void C1_権限者は取得できる() {
            BlogPostEntity entity = teamPost();
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, SLUG)).willReturn(Optional.of(entity));
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(responseWithBody());

            BlogPostResponse result = service.getBySlug(TEAM_ID, null, null, SLUG);

            assertThat(result.getId()).isEqualTo(POST_ID);
            verify(contentVisibilityChecker).assertCanView(ReferenceType.BLOG_POST, POST_ID, VIEWER_ID);
        }

        @Test
        @DisplayName("C8: ペイウォール未課金（LOCKED）は従来どおりタイトルのみ（本文 null・accessState=LOCKED）で 200 相当")
        void C8_ペイウォールLOCKEDは従来どおり() {
            BlogPostEntity entity = teamPost();
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, SLUG)).willReturn(Optional.of(entity));
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(BlogPostResponse.builder()
                    .id(POST_ID)
                    .scope(new BlogPostResponse.BlogPostScopeDto(TEAM_ID, null, null, AUTHOR_ID))
                    .content(new BlogPostResponse.BlogPostContentDto(
                            "有料記事タイトル", SLUG, "有料本文", "要約", "cover.png"))
                    .stats(new BlogPostResponse.BlogPostStatisticsDto(null, null, false, 0))
                    .build());
            given(accessControlService.isSystemAdmin(VIEWER_ID)).willReturn(false);
            given(paymentGateService.checkAccess(eq(ContentGateType.POST), eq(POST_ID), eq(VIEWER_ID),
                    any(ContentGateTarget.class)))
                    .willReturn(new GateCheckResponse(false, false, List.of()));

            BlogPostResponse result = service.getBySlug(TEAM_ID, null, null, SLUG);

            assertThat(result.getContent().title()).isEqualTo("有料記事タイトル");
            assertThat(result.getContent().body()).isNull();
            assertThat(result.getAccessState()).isEqualTo(ContentAccessState.LOCKED.name());
        }
    }
}
