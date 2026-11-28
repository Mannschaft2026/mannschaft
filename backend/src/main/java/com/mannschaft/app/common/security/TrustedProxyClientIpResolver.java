package com.mannschaft.app.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

/**
 * アクセス制御の判定に使う送信元 IP を、信頼プロキシの設定に基づいて決める。
 *
 * <p>{@link com.mannschaft.app.common.IpAddressUtils#getClientIp} は {@code X-Forwarded-For} の
 * 左端（クライアントが自由に書ける値）を採るため、ログ・レート制限キーのような「詐称されても
 * 実害が限定的な」用途にしか使えない（docs/security/06 §4.3.1・§4.4 の既知トレードオフ）。
 * IP 許可リストのような<b>許否の判定</b>には本クラスを使う。</p>
 *
 * <p>規則（Tomcat {@code RemoteIpValve} と同じ右端方式）:</p>
 * <ol>
 *   <li>TCP の接続元（{@code remoteAddr}）が信頼プロキシでなければ、それを送信元とする
 *       （{@code X-Forwarded-For} / {@code X-Real-IP} は見ない）。</li>
 *   <li>信頼プロキシなら {@code X-Forwarded-For} を右端から辿り、信頼プロキシでない最初の値を
 *       送信元とする。IP リテラルでない値に当たればそこで止め、直前の（信頼できる）ホップを返す。</li>
 * </ol>
 *
 * <p>信頼プロキシは {@code mannschaft.client-ip.trusted-proxies}（環境変数
 * {@code MANNSCHAFT_TRUSTED_PROXIES}、カンマ区切りの IP / CIDR）で与える。既定は空で、
 * その場合は常に {@code remoteAddr} を使う（fail-closed）。本番（Cloudflare → AWS）では
 * ロードバランサのサブネットと Cloudflare の公開 IP 範囲を設定する。</p>
 */
@Component
public class TrustedProxyClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final IpAllowList trustedProxies;

    public TrustedProxyClientIpResolver(
            @Value("${mannschaft.client-ip.trusted-proxies:}") String trustedProxies) {
        List<String> entries = trustedProxies == null || trustedProxies.isBlank()
                ? List.of()
                : Arrays.stream(trustedProxies.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (String entry : entries) {
            if (!IpAllowList.isValidEntry(entry)) {
                throw new IllegalStateException(
                        "mannschaft.client-ip.trusted-proxies に IP / CIDR でない値があります: " + entry);
            }
        }
        this.trustedProxies = IpAllowList.of(entries);
    }

    /**
     * 送信元 IP を決める。
     *
     * @param request HTTP リクエスト
     * @return 送信元 IP（接続元が取れなければ null）
     */
    public String resolve(HttpServletRequest request) {
        String current = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !trustedProxies.contains(current)) {
            return current;
        }
        List<String> hops = forwardedHops(request);
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (!IpAllowList.isIpLiteral(hop)) {
                return current;
            }
            current = hop;
            if (!trustedProxies.contains(hop)) {
                return hop;
            }
        }
        return current;
    }

    /** 複数行の X-Forwarded-For を到着順に連結し、ホップの並びにする。 */
    private static List<String> forwardedHops(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();
        Enumeration<String> headers = request.getHeaders(X_FORWARDED_FOR);
        if (headers == null) {
            return hops;
        }
        while (headers.hasMoreElements()) {
            String line = headers.nextElement();
            if (line == null) {
                continue;
            }
            for (String part : line.split(",")) {
                String hop = part.trim();
                if (!hop.isEmpty()) {
                    hops.add(hop);
                }
            }
        }
        return hops;
    }
}
