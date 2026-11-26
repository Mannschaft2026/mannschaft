package com.mannschaft.app.supportaccess;

import com.mannschaft.app.auth.service.RoleClaimResolver;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import com.mannschaft.app.supportaccess.filter.SupportAccessFilter;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantValidator;
import com.mannschaft.app.supportaccess.service.SupportAccessLogRecorder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 戦役Z-B 試練: {@link SupportAccessFilter}（AdminImpersonationFilter の置き換え）の単体テスト。
 *
 * <p>受け入れ条件 S1/S2（検証の委譲と拒否時に本人視点にならない）、S3（書込メソッドの 003）、S4（禁止パスの 004）、
 * S5（authorities は本人のロールのみ）、S6（他の運営者の grant は 404 と DENIED 記録）、S10（閲覧記録・記録失敗は 503）、
 * S13（旧ヘッダーは 400）、S15（WebSocket は代理にならない）と 009（代理入力・後見ヘッダーとの併用）を固定する。</p>
 */
@DisplayName("戦役Z-B SupportAccessFilter 単体テスト")
class SupportAccessFilterTest {

    private static final Long OPERATOR_ID = 9001L;
    private static final Long TARGET_ID = 42L;
    private static final UUID GRANT_ID = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");

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
        authenticateAsOperator();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAsOperator() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(OPERATOR_ID), null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER"), new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRequestURI(path);
        req.addHeader(SupportAccessHeaders.GRANT_ID, GRANT_ID.toString());
        return req;
    }

    private static final SupportAccessContext CONTEXT =
            new SupportAccessContext(GRANT_ID, OPERATOR_ID, TARGET_ID, LocalDateTime.of(2026, 11, 27, 10, 0));

    private static SupportAccessContext validContext() {
        return CONTEXT;
    }

    /** 試験用の ErrorCode（本番の SupportAccessErrorCode は実装役が作る。コード文字列だけで照合する）。 */
    private record TestCode(String code) implements ErrorCode {
        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getMessage() {
            return code;
        }

        @Override
        public Severity getSeverity() {
            return Severity.WARN;
        }
    }

    private static BusinessException businessException(String code) {
        return new BusinessException(new TestCode(code));
    }

    /** chain が呼ばれたか・chain 内の認証と文脈を記録する。 */
    private static final class RecordingChain implements FilterChain {
        final AtomicBoolean called = new AtomicBoolean(false);
        final AtomicReference<Authentication> authInChain = new AtomicReference<>();
        final AtomicBoolean contextActiveInChain = new AtomicBoolean(false);

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            called.set(true);
            authInChain.set(SecurityContextHolder.getContext().getAuthentication());
            contextActiveInChain.set(SupportAccessContextHolder.isActive());
        }
    }

    @Test
    @DisplayName("S1 ヘッダーなしの通常リクエストは何もせず通し、検証も記録もしない")
    void S1_ヘッダーなしは素通し() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/me/profile");
        req.setRequestURI("/api/v1/me/profile");
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(req, res, chain);

        assertThat(chain.called).isTrue();
        assertThat(chain.authInChain.get().getName()).isEqualTo(String.valueOf(OPERATOR_ID));
        verifyNoInteractions(validator, logRecorder, roleClaimResolver);
    }

    @Test
    @DisplayName("S1 検証が無効（002）を返したら 403 で chain に進まず、本人の視点にならない")
    void S1_無効なgrantは403で本人視点にならない() throws Exception {
        given(validator.validate(OPERATOR_ID, GRANT_ID)).willThrow(businessException("SUPPORT_ACCESS_002"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request("GET", "/api/v1/me/profile"), res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("SUPPORT_ACCESS_002");
        assertThat(chain.called).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo(String.valueOf(OPERATOR_ID));
        verify(logRecorder).recordDenied(eq(OPERATOR_ID), eq(GRANT_ID.toString()), eq("GET"),
                eq("/api/v1/me/profile"), eq("SUPPORT_ACCESS_002"));
    }

    @Test
    @DisplayName("S1 検証はリクエストごとに毎回行う（キャッシュしない）。2回目に取消されていれば 403")
    void S1_毎回検証しキャッシュしない() throws Exception {
        given(validator.validate(OPERATOR_ID, GRANT_ID))
                .willReturn(validContext())
                .willThrow(businessException("SUPPORT_ACCESS_002"));
        given(roleClaimResolver.resolveRoles(TARGET_ID)).willReturn(List.of("MEMBER"));

        RecordingChain first = new RecordingChain();
        filter.doFilter(request("GET", "/api/v1/me/profile"), new MockHttpServletResponse(), first);
        authenticateAsOperator();
        RecordingChain second = new RecordingChain();
        MockHttpServletResponse secondRes = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/v1/me/profile"), secondRes, second);

        assertThat(first.called).isTrue();
        assertThat(second.called).isFalse();
        assertThat(secondRes.getStatus()).isEqualTo(403);
        verify(validator, org.mockito.Mockito.times(2)).validate(OPERATOR_ID, GRANT_ID);
    }

    @Test
    @DisplayName("S1 ROLE_SYSTEM_ADMIN を持たない利用者のヘッダーは 403 で、検証にも進まない")
    void S1_運営者以外のヘッダーは403() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "77", null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request("GET", "/api/v1/me/profile"), res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.called).isFalse();
        verify(validator, never()).validate(anyLong(), any());
    }

    @Test
    @DisplayName("S1 未認証でヘッダーだけ付いたリクエストは本人の視点にならない（chain に進んでも匿名のまま、または拒否）")
    void S1_未認証のヘッダーは無効() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request("GET", "/api/v1/me/profile"), res, chain);

        if (chain.called.get()) {
            assertThat(chain.authInChain.get()).isNull();
        } else {
            assertThat(res.getStatus()).isIn(401, 403);
        }
        verify(validator, never()).validate(anyLong(), any());
    }

    @Test
    @DisplayName("S1 grant ID が UUID でなければ 404 SUPPORT_ACCESS_001")
    void S1_UUIDでないヘッダーは404() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/me/profile");
        req.setRequestURI("/api/v1/me/profile");
        req.addHeader(SupportAccessHeaders.GRANT_ID, "42");
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentAsString()).contains("SUPPORT_ACCESS_001");
        assertThat(chain.called).isFalse();
    }

    @Test
    @DisplayName("S2 閲覧未開始（検証が 002）なら 403 で chain に進まない")
    void S2_閲覧未開始は403() throws Exception {
        given(validator.validate(OPERATOR_ID, GRANT_ID)).willThrow(businessException("SUPPORT_ACCESS_002"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request("GET", "/api/v1/teams/3/schedules"), res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.called).isFalse();
    }

    @ParameterizedTest(name = "S3 {0} は 403 SUPPORT_ACCESS_003")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("S3 代理中の書込メソッドは 403 SUPPORT_ACCESS_003 で chain に進まない")
    void S3_書込メソッドは003(String method) throws Exception {
        given(validator.validate(OPERATOR_ID, GRANT_ID)).willReturn(validContext());
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request(method, "/api/v1/teams/3/posts"), res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("SUPPORT_ACCESS_003");
        assertThat(chain.called).isFalse();
        verify(logRecorder).recordDenied(eq(OPERATOR_ID), eq(GRANT_ID.toString()), eq(method),
                eq("/api/v1/teams/3/posts"), eq("SUPPORT_ACCESS_003"));
    }

    @ParameterizedTest(name = "S4 GET {0} は 403 SUPPORT_ACCESS_004")
    @ValueSource(strings = {
            "/api/v1/auth/refresh",
            "/api/v1/auth/2fa/setup",
            "/api/v1/auth/webauthn/credentials",
            "/api/v1/me/support-access/grants",
            "/api/v1/me/support-access/logs",
            "/api/v1/system-admin/support-access/grants",
            "/api/v1/system-admin/feedbacks",
            "/api/v1/admin/users/42",
            "/api/v1/admin/stripe/events",
            "/api/v1/admin/reports/1",
            "/api/v1/admin/moderation/queue",
            "/api/v1/admin/age-group-settings",
            "/api/v1/admin/seals",
            "/api/v1/admin/notifications/stats",
            "/api/v1/admin/form-presets",
            "/api/v1/teams/3/files/7/download-url",
            "/api/v1/teams/3/members/export",
            "/api/v1/organizations/5/members.csv",
            "/api/v1/teams/3/shifts/schedules/9/pdf",
            "/api/v1/me/receipts/12/pdf",
            "/api/v1/gdpr/export",
            "/billing/portal",
    })
    @DisplayName("S4 認証系・決済・本人承認・運営・SYSTEM_ADMIN 専用・書出しのパスは代理中 GET でも 004")
    void S4_禁止パスはGETでも004(String path) throws Exception {
        given(validator.validate(OPERATOR_ID, GRANT_ID)).willReturn(validContext());
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request("GET", path), res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("SUPPORT_ACCESS_004");
        assertThat(chain.called).isFalse();
    }

    @Test
    @DisplayName("S5 代理中の authorities は本人の RoleClaimResolver.resolveRoles と同一で ROLE_SYSTEM_ADMIN を含まない")
    void S5_権限は本人のロールのみ() throws Exception {
        given(validator.validate(OPERATOR_ID, GRANT_ID)).willReturn(validContext());
        given(roleClaimResolver.resolveRoles(TARGET_ID)).willReturn(List.of("MEMBER"));
        given(logRecorder.recordView(any(), anyString(), anyString())).willReturn(UUID.randomUUID());
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request("GET", "/api/v1/me/profile"), res, chain);

        assertThat(chain.called).isTrue();
        Authentication inChain = chain.authInChain.get();
        assertThat(inChain.getName()).isEqualTo(String.valueOf(TARGET_ID));
        assertThat(inChain.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_MEMBER")
                .doesNotContain("ROLE_SYSTEM_ADMIN");
        verify(roleClaimResolver).resolveRoles(TARGET_ID);
        verify(roleClaimResolver, never()).resolveRoles(OPERATOR_ID);
    }

    @Test
    @DisplayName("S5 本人が SYSTEM_ADMIN と解決されても代理中の authorities に ROLE_SYSTEM_ADMIN は載らない（多重防御）")
    void S5_本人ロールにSYSTEM_ADMINがあっても載せない() throws Exception {
        given(validator.validate(OPERATOR_ID, GRANT_ID)).willReturn(validContext());
        given(roleClaimResolver.resolveRoles(TARGET_ID)).willReturn(List.of("MEMBER", "SYSTEM_ADMIN"));
        given(logRecorder.recordView(any(), anyString(), anyString())).willReturn(UUID.randomUUID());
        RecordingChain chain = new RecordingChain();
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/api/v1/me/profile"), res, chain);

        if (chain.called.get()) {
            assertThat(chain.authInChain.get().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .doesNotContain("ROLE_SYSTEM_ADMIN");
        } else {
            assertThat(res.getStatus()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("S5/S10 chain の中だけ代理の文脈が有効で、終わったら必ず消える（例外時も）")
    void S5_文脈はchain内だけ有効() throws Exception {
        given(validator.validate(OPERATOR_ID, GRANT_ID)).willReturn(validContext());
        given(roleClaimResolver.resolveRoles(TARGET_ID)).willReturn(List.of("MEMBER"));
        given(logRecorder.recordView(any(), anyString(), anyString())).willReturn(UUID.randomUUID());
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request("GET", "/api/v1/me/profile"), new MockHttpServletResponse(), chain);

        assertThat(chain.contextActiveInChain).isTrue();
        assertThat(SupportAccessContextHolder.isActive()).isFalse();

        FilterChain throwing = (req, res) -> {
            throw new IllegalStateException("業務の失敗");
        };
        authenticateAsOperator();
        try {
            filter.doFilter(request("GET", "/api/v1/me/profile"), new MockHttpServletResponse(), throwing);
        } catch (Exception ignored) {
            // 例外の伝播は問わない。文脈が残らないことだけを見る
        }
        assertThat(SupportAccessContextHolder.isActive()).isFalse();
    }

    @Test
    @DisplayName("S6 他の運営者の grant（検証が 001）は 404 で、記録に DENIED が残る")
    void S6_他の運営者のgrantは404とDENIED記録() throws Exception {
        given(validator.validate(OPERATOR_ID, GRANT_ID)).willThrow(businessException("SUPPORT_ACCESS_001"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request("GET", "/api/v1/me/profile"), res, chain);

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentAsString()).contains("SUPPORT_ACCESS_001");
        assertThat(chain.called).isFalse();
        verify(logRecorder).recordDenied(eq(OPERATOR_ID), eq(GRANT_ID.toString()), eq("GET"),
                eq("/api/v1/me/profile"), eq("SUPPORT_ACCESS_001"));
    }

    @Test
    @DisplayName("S7 運営アカウントがテナント所属（検証が 010）なら 403 SUPPORT_ACCESS_010")
    void S7_所属する運営者は010() throws Exception {
        given(validator.validate(OPERATOR_ID, GRANT_ID)).willThrow(businessException("SUPPORT_ACCESS_010"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request("GET", "/api/v1/me/profile"), res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("SUPPORT_ACCESS_010");
        assertThat(chain.called).isFalse();
    }

    @Test
    @DisplayName("S10 閲覧ごとに chain の前に VIEW を記録し、後で応答状態を書き足す")
    void S10_閲覧を記録する() throws Exception {
        UUID logId = UUID.randomUUID();
        given(validator.validate(OPERATOR_ID, GRANT_ID)).willReturn(validContext());
        given(roleClaimResolver.resolveRoles(TARGET_ID)).willReturn(List.of("MEMBER"));
        given(logRecorder.recordView(any(), eq("GET"), eq("/api/v1/teams/3/schedules"))).willReturn(logId);
        MockHttpServletRequest req = request("GET", "/api/v1/teams/3/schedules");
        req.setQueryString("q=secret");

        filter.doFilter(req, new MockHttpServletResponse(), new RecordingChain());

        verify(logRecorder).recordView(eq(CONTEXT), eq("GET"), eq("/api/v1/teams/3/schedules"));
        verify(logRecorder).completeView(eq(logId), anyInt(), any());
    }

    @Test
    @DisplayName("S10 閲覧記録が書けなければ 503 で fail-closed（chain に進まない）")
    void S10_記録失敗は503() throws Exception {
        given(validator.validate(OPERATOR_ID, GRANT_ID)).willReturn(validContext());
        given(roleClaimResolver.resolveRoles(TARGET_ID)).willReturn(List.of("MEMBER"));
        given(logRecorder.recordView(any(), anyString(), anyString())).willThrow(new IllegalStateException("DB 停止"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request("GET", "/api/v1/me/profile"), res, chain);

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(chain.called).isFalse();
    }

    @Test
    @DisplayName("S13 旧ヘッダー X-Admin-Impersonate-User-Id は運営者・一般とも 400 SUPPORT_ACCESS_012")
    void S13_旧ヘッダーは400() throws Exception {
        for (String userId : new String[]{String.valueOf(OPERATOR_ID), "77"}) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    userId, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/me/profile");
            req.setRequestURI("/api/v1/me/profile");
            req.addHeader(SupportAccessHeaders.LEGACY_IMPERSONATE, String.valueOf(TARGET_ID));
            MockHttpServletResponse res = new MockHttpServletResponse();
            RecordingChain chain = new RecordingChain();

            filter.doFilter(req, res, chain);

            assertThat(res.getStatus()).isEqualTo(400);
            assertThat(res.getContentAsString()).contains("SUPPORT_ACCESS_012");
            assertThat(chain.called).isFalse();
        }
    }

    @Test
    @DisplayName("S13 旧ヘッダーは /system-admin・/admin 宛でも 400（旧フィルターの素通し除外を引き継がない）")
    void S13_旧ヘッダーは運営パスでも400() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/system-admin/feedbacks");
        req.setRequestURI("/api/v1/system-admin/feedbacks");
        req.addHeader(SupportAccessHeaders.LEGACY_IMPERSONATE, String.valueOf(TARGET_ID));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new RecordingChain());

        assertThat(res.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("009 代理入力・後見のヘッダー（X-Proxy-For-User-Id）との併用は 400 SUPPORT_ACCESS_009")
    void 併用は009() throws Exception {
        MockHttpServletRequest req = request("GET", "/api/v1/me/profile");
        req.addHeader("X-Proxy-For-User-Id", "17");
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString()).contains("SUPPORT_ACCESS_009");
        assertThat(chain.called).isFalse();
    }

    @Test
    @DisplayName("S15 WebSocket（/ws/**）はヘッダーがあっても代理にならない")
    void S15_WebSocketは代理にならない() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request("GET", "/ws/info"), res, chain);

        if (chain.called.get()) {
            assertThat(chain.authInChain.get().getName()).isEqualTo(String.valueOf(OPERATOR_ID));
            assertThat(chain.contextActiveInChain).isFalse();
        } else {
            assertThat(res.getStatus()).isIn(400, 403);
        }
        verify(roleClaimResolver, never()).resolveRoles(TARGET_ID);
    }
}
