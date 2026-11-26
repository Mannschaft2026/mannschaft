package com.mannschaft.app.supportaccess.filter;

/**
 * F10.9 代理閲覧中は GET でも拒否するパス（004）の判定。試練スタブ。
 *
 * <p>対象: 認証系一式・決済/課金・/me/support-access/**・/system-admin/**・SecurityConfig で SYSTEM_ADMIN 予約の
 * /api/v1/admin/** 系統（列挙）・download-url/export/csv/pdf。</p>
 */
public final class SupportAccessPathPolicy {

    private SupportAccessPathPolicy() {
    }

    public static boolean isForbiddenDuringSupportAccess(String requestPath) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
