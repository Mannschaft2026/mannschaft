package com.mannschaft.app.supportaccess;

/** F10.9 サポート閲覧のリクエストヘッダー名。 */
public final class SupportAccessHeaders {

    /** 代理閲覧中に運営者が付けるヘッダー（値は grant の UUID）。 */
    public static final String GRANT_ID = "X-Support-Access-Grant-Id";

    /** 廃止した旧・管理者変身ヘッダー。送られたら 400 SUPPORT_ACCESS_012。 */
    public static final String LEGACY_IMPERSONATE = "X-Admin-Impersonate-User-Id";

    private SupportAccessHeaders() {
    }
}
