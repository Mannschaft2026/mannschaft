package com.mannschaft.app.supportaccess.filter;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * F10.9 代理閲覧中は GET でも拒否するパス（SUPPORT_ACCESS_004）の判定。
 *
 * <p>代理閲覧は「本人の画面を見るだけ」であり、次は読み取りであっても許さない（fail-closed）。</p>
 * <ul>
 *   <li>認証・資格情報（/auth/**、パスワード・メール・OAuth 連携・ログイン履歴・退会・API トークン）</li>
 *   <li>決済・課金・請求（billing・stripe・支払方法・領収書）</li>
 *   <li>招待トークンの発行と閲覧（招待・QR・*-tokens）</li>
 *   <li>資格情報を返す GET（QR トークン {@code qr}・{@code *-qr}・{@code qr-*}、共有リンク {@code links}・{@code file-links}、
 *       {@code secret(s)}。公開トークン経由の閲覧・書出しへの抜け道になるため）</li>
 *   <li>書き出し（download / export / csv / pdf を含むパス。署名 URL・ファイル生成を含む）</li>
 *   <li>本人承認の API（/me/support-access/**。自己許可の抜け道を塞ぐ）</li>
 *   <li>運営 API（/system-admin/**・/actuator・/api/internal、および SecurityConfig で SYSTEM_ADMIN 予約の
 *       /api/v1/admin/** の各系統）。代理中の認証は本人のロールに作り直すため、運営 API は元から通らないが、
 *       フィルターの時点で 004 として明示的に拒否して記録に残す</li>
 * </ul>
 *
 * <p>/api/v1/admin/** は一律に禁止しない。スコープ管理者（ADMIN）が使う per-scope の管理 API
 * （dashboard・permission-groups・feedbacks 等）は本人の視点として閲覧できる。禁止するのは
 * {@code SecurityConfig} が {@code hasRole("SYSTEM_ADMIN")} で予約している系統だけで、
 * 番人（SupportAccessEnforcementGuardTest）が SecurityConfig の規則との差分を検出する。</p>
 *
 * <p>判定はリクエストパス（クエリ・パスパラメータを除く）だけで行う。大文字小文字・連続スラッシュ・末尾スラッシュは
 * 正規化して比べる。パーセントエンコードのデコードは呼び出し側の責務で、{@code SupportAccessFilter} は生の URI と
 * デコード済みの lookup path の両方を渡し（さらに {@code %} を含む URI は一律に拒否し）、
 * {@code SupportAccessHandlerInterceptor} はルートパターン（{@link #isForbiddenRoutePattern(String)}）でも判定する。</p>
 */
public final class SupportAccessPathPolicy {

    /** 書き出し系の目印（パスのどこかに含まれたら拒否）。 */
    private static final Pattern EXPORT_LIKE = Pattern.compile("download|export|csv|pdf");

    /** 招待トークン・QR トークンの発行/閲覧（セグメント単位）。 */
    private static final Pattern TOKEN_OR_INVITE_SEGMENT = Pattern.compile(
            "(?:.*-)?tokens?|(?:.*-)?invit(?:e|es|ation|ations)");

    /** 資格情報を返す GET の目印（セグメント単位）: QR トークン・共有リンク・シークレット。 */
    private static final Pattern CREDENTIAL_SEGMENT = Pattern.compile(
            "(?:.*-)?qr(?:-.*)?|links|file-links|(?:.*-)?secrets?");

    /** ルートパターンのパス変数（{id}・{*rest}）。判定前に中立な値へ置き換える。 */
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}]*}");

    /** 認証・決済の語がパスのどこかのセグメントに現れたら拒否。 */
    private static final Set<String> FORBIDDEN_SEGMENTS = Set.of(
            "billing", "stripe", "stripe-connect", "payment-methods", "api-keys");

    /**
     * 先頭一致（セグメント境界）で拒否するパス。{@code /api/v1} 配下を中心に、認証・本人承認・運営・
     * SYSTEM_ADMIN 予約の admin 系統を列挙する。
     */
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            // 認証・資格情報
            "/api/v1/auth",
            "/api/v1/account",
            "/api/v1/users/me/password",
            "/api/v1/users/me/email",
            "/api/v1/users/me/oauth",
            "/api/v1/users/me/login-history",
            "/api/v1/users/me/withdrawal",
            "/api/v1/users/me/stripe-connect",
            "/api/api-keys",
            // 決済・課金・請求
            "/api/v1/billing",
            "/api/v1/me/billing",
            "/api/v1/me/payment-methods",
            "/api/v1/me/receipts",
            "/api/v1/my/receipts",
            "/api/v1/member-payments",
            "/api/v1/payment",
            // 本人承認の API（代理中に自分で許可する抜け道を塞ぐ）
            "/api/v1/me/support-access",
            // 運営 API
            "/api/v1/system-admin",
            "/actuator",
            "/api/internal",
            // SecurityConfig で hasRole("SYSTEM_ADMIN") 予約の admin 系統（SecurityConfig の規則と同期すること）
            "/api/v1/admin/age-group-settings",
            "/api/v1/admin/stripe",
            "/api/v1/admin/reports",
            "/api/v1/admin/moderation",
            "/api/v1/admin/warning-re-reviews",
            "/api/v1/admin/users",
            "/api/v1/admin/onboarding/presets",
            "/api/v1/admin/form-presets",
            "/api/v1/admin/seals",
            "/api/v1/admin/action-templates",
            "/api/v1/admin/notifications");

    private SupportAccessPathPolicy() {
    }

    /** 代理閲覧中にこのパスを拒否すべきか（メソッドは問わない）。 */
    public static boolean isForbiddenDuringSupportAccess(String requestPath) {
        if (requestPath == null) {
            return true;
        }
        String path = normalize(requestPath);
        if (EXPORT_LIKE.matcher(path).find()) {
            return true;
        }
        for (String prefix : FORBIDDEN_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (FORBIDDEN_SEGMENTS.contains(segment) || TOKEN_OR_INVITE_SEGMENT.matcher(segment).matches()
                    || CREDENTIAL_SEGMENT.matcher(segment).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Spring MVC のルートパターン（{@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}。例
     * {@code /api/v1/member-cards/{id}/qr}）が代理中の禁止パスに当たるか。パス変数は中立な値に置き換えて判定する
     * （変数名 {@code {exportId}} 等で誤って拒否しないため）。パターンが無い（null）なら判定しない（false）。
     */
    public static boolean isForbiddenRoutePattern(String routePattern) {
        if (routePattern == null || routePattern.isBlank()) {
            return false;
        }
        return isForbiddenDuringSupportAccess(PATH_VARIABLE.matcher(routePattern).replaceAll("x"));
    }

    /** 小文字化・パスパラメータ（;…）除去・連続スラッシュと末尾スラッシュの正規化。 */
    static String normalize(String requestPath) {
        StringBuilder sb = new StringBuilder(requestPath.length());
        for (String segment : requestPath.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            int semicolon = segment.indexOf(';');
            String clean = semicolon >= 0 ? segment.substring(0, semicolon) : segment;
            if (clean.isEmpty()) {
                continue;
            }
            sb.append('/').append(clean.toLowerCase(Locale.ROOT));
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }
}
