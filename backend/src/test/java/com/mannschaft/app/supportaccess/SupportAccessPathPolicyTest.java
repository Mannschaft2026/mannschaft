package com.mannschaft.app.supportaccess;

import com.mannschaft.app.supportaccess.filter.SupportAccessPathPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 戦役Z-B: 代理閲覧中に拒否するパス（SUPPORT_ACCESS_004）の判定。許可すべき通常の閲覧が誤って塞がれないことと、
 * 認証・決済・招待トークン・書出し・運営 API を見逃さないことの両方を固定する。
 */
@DisplayName("戦役Z-B SupportAccessPathPolicy")
class SupportAccessPathPolicyTest {

    @ParameterizedTest(name = "閲覧できる: {0}")
    @ValueSource(strings = {
            "/api/v1/users/me",
            "/api/v1/users/me/audit-logs",
            "/api/v1/teams/3/schedules",
            "/api/v1/teams/3/members/17",
            "/api/v1/organizations/5/announcements",
            "/api/v1/me/notifications",
            "/api/v1/me/guardianship/children/9/schedules",
            "/api/v1/admin/dashboard",
            "/api/v1/admin/feedbacks",
            "/api/v1/admin/permission-groups",
            "/api/v1/teams/3/payment-items",
            "/api/v1/dashboard/widgets",
    })
    void 通常の閲覧は拒否しない(String path) {
        assertThat(SupportAccessPathPolicy.isForbiddenDuringSupportAccess(path)).isFalse();
    }

    @ParameterizedTest(name = "拒否: {0}")
    @ValueSource(strings = {
            // 認証・資格情報
            "/api/v1/auth/login",
            "/api/v1/auth/sessions",
            "/api/v1/auth/oauth/link/GOOGLE/callback",
            "/api/v1/users/me/password",
            "/api/v1/users/me/email/confirm",
            "/api/v1/users/me/oauth",
            "/api/v1/users/me/login-history",
            "/api/v1/users/me/withdrawal/cancel",
            "/api/api-keys",
            "/api/v1/me/ical/token",
            // 決済・課金・請求
            "/api/v1/billing/portal",
            "/api/v1/me/billing/invoices",
            "/api/v1/me/payment-methods",
            "/api/v1/payment/connect/status",
            "/api/v1/users/me/stripe-connect",
            "/api/v1/my/receipts",
            // 招待・トークン
            "/api/v1/teams/3/invite-tokens",
            "/api/v1/events/5/invite-tokens",
            "/api/v1/contact-invite-tokens",
            "/api/v1/contracts/4/qr-tokens",
            "/api/v1/me/pending-invite",
            "/api/v1/care-links/invitations",
            "/api/v1/organizations/x/team-invites",
            // 書出し
            "/api/v1/teams/3/files/7/download-url",
            "/api/v1/teams/3/members/export",
            "/api/v1/organizations/5/members.csv",
            "/api/v1/teams/3/shifts/schedules/9/pdf",
            "/api/v1/account/data-export/download",
            // 本人承認・運営・SYSTEM_ADMIN 予約
            "/api/v1/me/support-access/grants",
            "/api/v1/system-admin/support-access/grants",
            "/api/v1/admin/stripe/events",
            "/api/v1/admin/users/42",
            "/actuator/health/details",
            "/api/internal/anything",
    })
    void 認証決済招待書出し運営は拒否する(String path) {
        assertThat(SupportAccessPathPolicy.isForbiddenDuringSupportAccess(path)).isTrue();
    }

    @Test
    @DisplayName("大文字小文字・連続スラッシュ・末尾スラッシュ・パスパラメータの揺れで回避できない")
    void 表記の揺れで回避できない() {
        assertThat(SupportAccessPathPolicy.isForbiddenDuringSupportAccess("/API/V1/AUTH/refresh")).isTrue();
        assertThat(SupportAccessPathPolicy.isForbiddenDuringSupportAccess("/api//v1//auth//refresh")).isTrue();
        assertThat(SupportAccessPathPolicy.isForbiddenDuringSupportAccess("/api/v1/auth/")).isTrue();
        assertThat(SupportAccessPathPolicy.isForbiddenDuringSupportAccess("/api/v1/auth;x=1/refresh")).isTrue();
        assertThat(SupportAccessPathPolicy.isForbiddenDuringSupportAccess("/api/v1/me;jsessionid=1/support-access/grants")).isTrue();
    }

    @Test
    @DisplayName("パスが無い（null）なら fail-closed で拒否")
    void nullは拒否() {
        assertThat(SupportAccessPathPolicy.isForbiddenDuringSupportAccess(null)).isTrue();
    }
}
