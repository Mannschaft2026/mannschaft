package com.mannschaft.app.supportaccess;

import com.mannschaft.app.auth.service.RoleClaimResolver;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.config.AsyncConfig;
import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import com.mannschaft.app.supportaccess.filter.SupportAccessFilter;
import com.mannschaft.app.supportaccess.filter.SupportAccessPathPolicy;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantValidator;
import com.mannschaft.app.supportaccess.service.SupportAccessLogRecorder;
import com.mannschaft.app.supportaccess.web.SupportAccessHandlerInterceptor;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役Z-B 検分差し戻し対応A: 閲覧のみの強制の迂回を塞いだことの回帰テスト。
 *
 * <ul>
 *   <li>致命1: パーセントエンコード（%2D・%65・%61・%70）・大文字小文字・二重スラッシュ・セミコロン・末尾スラッシュで
 *       禁止パスを迂回できない（フィルターの 004 と、Interceptor のルートパターン判定の二重化）</li>
 *   <li>重要2: 資格情報を返す GET（QR トークン・共有リンク・シークレット）のパスは 004</li>
 *   <li>重要3: ダッシュボードの並列集計の実行先へ代理の文脈が伝播する</li>
 *   <li>軽微9: 代理中の session_hash は null（運営者の jti を本人のセッションとして扱わない）</li>
 * </ul>
 */
@DisplayName("戦役Z-B 閲覧のみの強制の迂回対策")
class SupportAccessBypassHardeningTest {

    private static final Long OPERATOR_ID = 9001L;
    private static final Long TARGET_ID = 42L;
    private static final UUID GRANT_ID = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
    private static final SupportAccessContext CONTEXT =
            new SupportAccessContext(GRANT_ID, OPERATOR_ID, TARGET_ID, Instant.parse("2026-11-27T10:00:00Z"));

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        SupportAccessContextHolder.clear();
    }

    @Nested
    @DisplayName("フィルター（第1層）")
    class FilterLayer {

        private SupportAccessGrantValidator validator;
        private SupportAccessLogRecorder logRecorder;
        private RoleClaimResolver roleClaimResolver;
        private SupportAccessFilter filter;

        @BeforeEach
        void setUp() {
            validator = mock(SupportAccessGrantValidator.class);
            logRecorder = mock(SupportAccessLogRecorder.class);
            roleClaimResolver = mock(RoleClaimResolver.class);
            filter = new SupportAccessFilter(validator, logRecorder, roleClaimResolver);
            UsernamePasswordAuthenticationToken operator = new UsernamePasswordAuthenticationToken(
                    String.valueOf(OPERATOR_ID), null,
                    List.of(new SimpleGrantedAuthority("ROLE_MEMBER"), new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")));
            operator.setDetails(Map.of("jti", "operator-jti-0001", "ppc", false));
            SecurityContextHolder.getContext().setAuthentication(operator);
            given(validator.validate(OPERATOR_ID, GRANT_ID)).willReturn(CONTEXT);
            given(roleClaimResolver.resolveRoles(TARGET_ID)).willReturn(List.of("MEMBER"));
            given(logRecorder.recordView(any(), anyString(), anyString())).willReturn(UUID.randomUUID());
        }

        private MockHttpServletRequest request(String rawUri) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", rawUri);
            req.setRequestURI(rawUri);
            req.addHeader(SupportAccessHeaders.GRANT_ID, GRANT_ID.toString());
            return req;
        }

        @ParameterizedTest(name = "004: {0}")
        @ValueSource(strings = {
                // パーセントエンコードによる迂回（検分の指摘例）
                "/api/v1/users/me/login%2Dhistory",
                "/api/v1/my/r%65ceipts",
                "/api/v1/%61uth/sessions",
                "/api/v1/teams/3/members/ex%70ort",
                "/api/v1/me/support%2Daccess/grants",
                // エンコードした区切り・安全な文字のエンコードも一律に拒否する
                "/api/v1/users/me%2Fpassword",
                "/api/v1/teams/3/schedules%3Fx",
                "/api/v1/me/profile%20",
                // 大文字小文字・二重スラッシュ・セミコロン・末尾スラッシュ
                "/API/V1/Users/Me/Login-History",
                "/api//v1//users//me//login-history",
                "/api/v1/users/me;x=1/login-history",
                "/api/v1/users;jsessionid=1/me/login-history",
                "/api/v1/my/receipts/",
                "/api/v1/my/Receipts//",
                // 資格情報を返す GET（重要2）
                "/api/v1/member-cards/12/qr",
                "/api/v1/files/7/links",
                "/api/v1/organizations/5/point-card-providers/3/customer-qr",
                "/api/v1/teams/3/equipment/qr-codes",
                "/api/v1/teams/3/queue/qr-codes/token/abc",
                "/api/v1/me/tickets/4/qr",
                "/api/v1/teams/3/line/webhook-secret",
        })
        @DisplayName("致命1/重要2 エンコード・表記揺れ・資格情報の GET は代理中に 004 で、本人視点にならない")
        void エンコードと表記揺れで迂回できない(String rawUri) throws Exception {
            MockHttpServletResponse res = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean(false);

            filter.doFilter(request(rawUri), res, (rq, rs) -> called.set(true));

            assertThat(res.getStatus()).isEqualTo(403);
            assertThat(res.getContentAsString()).contains("SUPPORT_ACCESS_004");
            assertThat(called).isFalse();
            verify(logRecorder, never()).recordView(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("第2回検分 軽微7 日本語 slug（非 ASCII の UTF-8 だけのエンコード）のブログ記事は代理中も閲覧できる")
        void 日本語slugは通る() throws Exception {
            // 「サポート」をパーセントエンコードした slug
            String rawUri = "/api/v1/teams/3/blog/posts/%E3%82%B5%E3%83%9D%E3%83%BC%E3%83%88-2026";
            MockHttpServletResponse res = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean(false);

            filter.doFilter(request(rawUri), res, (rq, rs) -> called.set(true));

            assertThat(called).isTrue();
            assertThat(res.getContentAsString()).doesNotContain("SUPPORT_ACCESS_004");
            assertThat(SupportAccessFilter.hasDisallowedPercentEncoding(rawUri)).isFalse();
        }

        @ParameterizedTest(name = "許さないエンコード: {0}")
        @ValueSource(strings = {
                "/api/v1/teams/3/blog/posts/%E3%82%B5%2D1",         // 日本語と %2D の混在
                "/api/v1/teams/3/blog/posts/%E3%82%B5%65",          // 日本語と %65（英字）の混在
                "/api/v1/teams/3/blog/posts/a%2Eb",                 // %2E
                "/api/v1/teams/3/blog/posts/a%2fb",                 // %2F（小文字）
                "/api/v1/teams/3/blog/posts/a%5Cb",                 // %5C
                "/api/v1/teams/3/blog/posts/a%3Bb",                 // %3B
                "/api/v1/teams/3/blog/posts/a%00b",                 // %00
                "/api/v1/teams/3/blog/posts/a%7Fb",                 // %7F
                "/api/v1/teams/3/blog/posts/%E3%82",                // UTF-8 の途中切れ
                "/api/v1/teams/3/blog/posts/%C0%AF",                // 過長な '/'
                "/api/v1/teams/3/blog/posts/%ED%A0%80",             // サロゲート
                "/api/v1/teams/3/blog/posts/%E3%82%B5%E3",          // 末尾の途中切れ
                "/api/v1/teams/3/blog/posts/%E3x%82%B5",            // 文字の途中で区切られた
                "/api/v1/teams/3/blog/posts/%G1",                   // 16進でない
                "/api/v1/teams/3/blog/posts/%E",                    // 桁不足
        })
        @DisplayName("第2回検分 軽微7 エンコードされた ASCII を1つでも含む・不正な UTF-8 のエンコードは 004")
        void 許さないエンコードは004(String rawUri) throws Exception {
            MockHttpServletResponse res = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean(false);

            filter.doFilter(request(rawUri), res, (rq, rs) -> called.set(true));

            assertThat(SupportAccessFilter.hasDisallowedPercentEncoding(rawUri)).isTrue();
            assertThat(res.getStatus()).isEqualTo(403);
            assertThat(res.getContentAsString()).contains("SUPPORT_ACCESS_004");
            assertThat(called).isFalse();
        }

        @Test
        @DisplayName("第2回検分 軽微7 非 ASCII のエンコードでも、復号後のパスが禁止パスなら従来どおり 004")
        void 復号後の禁止パスは004() throws Exception {
            String rawUri = "/api/v1/users/me/login-history/%E3%82%B5";
            MockHttpServletResponse res = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean(false);

            filter.doFilter(request(rawUri), res, (rq, rs) -> called.set(true));

            assertThat(SupportAccessFilter.hasDisallowedPercentEncoding(rawUri)).isFalse();
            assertThat(res.getContentAsString()).contains("SUPPORT_ACCESS_004");
            assertThat(called).isFalse();
        }

        @Test
        @DisplayName("致命1 デコード済みの lookup path は Spring MVC と同じ規則（デコード・セミコロン除去）で求める")
        void デコード済みlookupPath() {
            assertThat(SupportAccessFilter.decodedLookupPath(request("/api/v1/users/me/login%2Dhistory")))
                    .isEqualTo("/api/v1/users/me/login-history");
            assertThat(SupportAccessFilter.decodedLookupPath(request("/api/v1/%61uth;x=1/sessions")))
                    .isEqualTo("/api/v1/auth/sessions");
            assertThat(SupportAccessPathPolicy.isForbiddenDuringSupportAccess(
                    SupportAccessFilter.decodedLookupPath(request("/api/v1/my/r%65ceipts")))).isTrue();
        }

        @Test
        @DisplayName("軽微9 代理中は運営者の jti を jti に置かず、session_hash は null（本人のセッション扱いにしない）")
        void 代理中のsessionHashはnull() throws Exception {
            AtomicReference<String> sessionHashInChain = new AtomicReference<>("unset");
            AtomicReference<Authentication> authInChain = new AtomicReference<>();
            FilterChain chain = (rq, rs) -> {
                authInChain.set(SecurityContextHolder.getContext().getAuthentication());
                sessionHashInChain.set(SecurityUtils.getCurrentSessionHash());
            };
            assertThat(SecurityUtils.getCurrentSessionHash()).as("代理でなければ運営者自身の session_hash").isNotNull();

            filter.doFilter(request("/api/v1/me/profile"), new MockHttpServletResponse(), chain);

            assertThat(authInChain.get().getName()).isEqualTo(String.valueOf(TARGET_ID));
            assertThat(sessionHashInChain.get()).isNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) authInChain.get().getDetails();
            assertThat(details)
                    .doesNotContainKey("jti")
                    .containsEntry(SupportAccessFilter.DETAILS_OPERATOR_JTI, "operator-jti-0001");
            assertThat(SecurityUtils.getCurrentSessionHash()).as("chain の後は運営者の認証へ戻る").isNotNull();
        }
    }

    @Nested
    @DisplayName("Interceptor（第2層のルートパターン判定）")
    class InterceptorLayer {

        private final SupportAccessHandlerInterceptor interceptor = new SupportAccessHandlerInterceptor();

        private MockHttpServletRequest resolved(String rawUri, String routePattern) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", rawUri);
            req.setRequestURI(rawUri);
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, routePattern);
            return req;
        }

        @ParameterizedTest(name = "004: {0}")
        @ValueSource(strings = {
                "/api/v1/member-cards/{id}/qr",
                "/api/v1/files/{fileId}/links",
                "/api/v1/users/me/login-history",
                "/api/v1/my/receipts",
                "/api/v1/auth/sessions",
                "/api/v1/teams/{teamId}/members/export",
        })
        @DisplayName("致命1 代理中は、解決されたルートパターンが禁止パスなら URI の見た目によらず 004")
        void 禁止ルートは拒否(String routePattern) {
            SupportAccessContextHolder.set(CONTEXT);
            // URI 側は判定をすり抜ける見た目にしておく（ルートパターンだけで拒否されることを確かめる）
            MockHttpServletRequest req = resolved("/api/v1/me/profile", routePattern);

            assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), new Object()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(((BusinessException) t).getErrorCode().getCode())
                            .isEqualTo("SUPPORT_ACCESS_004"));
        }

        @Test
        @DisplayName("致命1 代理中は、デコード済みの lookup path が禁止パスでも 004")
        void エンコードされたURIはlookupPathで拒否() {
            SupportAccessContextHolder.set(CONTEXT);
            MockHttpServletRequest req = resolved("/api/v1/users/me/login%2Dhistory", "/api/v1/users/{id}/{sub}");

            assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), new Object()))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("致命1 パス変数名（{exportId} 等）で誤って拒否しない。代理でなければ何もしない")
        void 通常の閲覧と非代理は通す() throws Exception {
            SupportAccessContextHolder.set(CONTEXT);
            assertThat(interceptor.preHandle(resolved("/api/v1/teams/3/schedules/9",
                    "/api/v1/teams/{teamId}/schedules/{exportId}"), new MockHttpServletResponse(), new Object())).isTrue();
            SupportAccessContextHolder.clear();
            assertThat(interceptor.preHandle(resolved("/api/v1/member-cards/1/qr", "/api/v1/member-cards/{id}/qr"),
                    new MockHttpServletResponse(), new Object())).isTrue();
        }
    }

    @Nested
    @DisplayName("非同期の文脈伝播（重要3）")
    class AsyncPropagation {

        @Test
        @DisplayName("dashboard-aggregation-pool で実行した集計にも代理の文脈が伝播し、プールのスレッドには残らない")
        void 集計プールへ文脈が伝播する() throws Exception {
            ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) new AsyncConfig().dashboardAggregationPool(50);
            try {
                SupportAccessContextHolder.set(CONTEXT);
                Boolean activeInTask = CompletableFuture
                        .supplyAsync(SupportAccessContextHolder::isActive, pool).get(5, TimeUnit.SECONDS);
                assertThat(activeInTask).isTrue();

                SupportAccessContextHolder.clear();
                Boolean leaked = CompletableFuture
                        .supplyAsync(SupportAccessContextHolder::isActive, pool).get(5, TimeUnit.SECONDS);
                assertThat(leaked).as("前のタスクの文脈がプールのスレッドに残らない").isFalse();
            } finally {
                pool.shutdown();
            }
        }

        @Test
        @DisplayName("第2回検分 軽微9 集計プールの最大は Hikari の上限より十分小さい（既定50→8、CI の5→2、上限16）")
        void 集計プールの最大はHikariより十分小さい() {
            assertThat(AsyncConfig.dashboardAggregationMaxPoolSize(50)).isEqualTo(8);
            assertThat(AsyncConfig.dashboardAggregationMaxPoolSize(5)).isEqualTo(2);
            assertThat(AsyncConfig.dashboardAggregationMaxPoolSize(100)).isEqualTo(16);
            assertThat(AsyncConfig.dashboardAggregationMaxPoolSize(1000)).isEqualTo(16);
            ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) new AsyncConfig().dashboardAggregationPool(50);
            try {
                assertThat(pool.getMaxPoolSize()).isEqualTo(8).isLessThan(50 / 2);
                assertThat(pool.getCorePoolSize()).isLessThanOrEqualTo(pool.getMaxPoolSize());
            } finally {
                pool.shutdown();
            }
        }

        @Test
        @DisplayName("手組みの既定（共有プールを包んだ Executor）でも代理の文脈が伝播する。包まない共有プールには伝播しない")
        void 既定の実行先でも伝播する() throws Exception {
            SupportAccessContextHolder.set(CONTEXT);
            Executor wrapped = AsyncConfig.contextPropagating(ForkJoinPool.commonPool());

            assertThat(CompletableFuture.supplyAsync(SupportAccessContextHolder::isActive, wrapped)
                    .get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(CompletableFuture.supplyAsync(SupportAccessContextHolder::isActive)
                    .get(5, TimeUnit.SECONDS)).as("引数なしの supplyAsync には伝播しない（番人で禁止する理由）").isFalse();
        }
    }
}
