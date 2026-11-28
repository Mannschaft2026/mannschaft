package com.mannschaft.app.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TrustedProxyClientIpResolver} 単体テスト（CMP-261127-1814）。
 *
 * <p>{@code X-Forwarded-For} は、直前のホップ（TCP の接続元）が信頼プロキシのときに限り、
 * 右端から信頼プロキシを剥がした最初の値を送信元とする。クライアントが自分で書き込める
 * 左端の値を信じないことを固定する。</p>
 */
@DisplayName("TrustedProxyClientIpResolver 単体テスト")
class TrustedProxyClientIpResolverTest {

    private static MockHttpServletRequest request(String remoteAddr, String... xff) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(remoteAddr);
        for (String v : xff) {
            req.addHeader("X-Forwarded-For", v);
        }
        return req;
    }

    @Test
    @DisplayName("信頼プロキシ未設定ならX-Forwarded-Forを無視し接続元を返す")
    void 信頼プロキシ未設定ならXFFを無視する() {
        TrustedProxyClientIpResolver resolver = new TrustedProxyClientIpResolver("");

        assertThat(resolver.resolve(request("203.0.113.9", "192.168.1.100"))).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("接続元が信頼プロキシでなければX-Forwarded-Forを無視する")
    void 接続元が信頼プロキシでなければXFFを無視する() {
        TrustedProxyClientIpResolver resolver = new TrustedProxyClientIpResolver("10.0.0.0/8");

        assertThat(resolver.resolve(request("203.0.113.9", "192.168.1.100"))).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("信頼プロキシ経由なら右端から信頼プロキシを剥がした最初の値を返す")
    void 信頼プロキシ経由なら右端の非信頼値を返す() {
        TrustedProxyClientIpResolver resolver = new TrustedProxyClientIpResolver("10.0.0.0/8, 172.16.0.0/12");

        // client(198.51.100.7) → edge(172.16.0.5) → ALB(10.0.0.1) → app
        assertThat(resolver.resolve(request("10.0.0.1", "198.51.100.7, 172.16.0.5"))).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("クライアントが左端に偽装した値は採らない")
    void 左端の偽装値は採らない() {
        TrustedProxyClientIpResolver resolver = new TrustedProxyClientIpResolver("10.0.0.0/8");

        // 攻撃者(198.51.100.7)が "192.168.1.100" を自前で付けて送り、プロキシが実IPを追記した形
        assertThat(resolver.resolve(request("10.0.0.1", "192.168.1.100, 198.51.100.7")))
                .isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("複数行のX-Forwarded-Forも1本の連鎖として扱う")
    void 複数行のXFFも連鎖として扱う() {
        TrustedProxyClientIpResolver resolver = new TrustedProxyClientIpResolver("10.0.0.0/8");

        assertThat(resolver.resolve(request("10.0.0.1", "192.168.1.100", "198.51.100.7")))
                .isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("連鎖の途中にIPリテラルでない値があればそこで止め、直前の信頼ホップを返す")
    void 壊れた値で止める() {
        TrustedProxyClientIpResolver resolver = new TrustedProxyClientIpResolver("10.0.0.0/8");

        assertThat(resolver.resolve(request("10.0.0.1", "192.168.1.100, unknown"))).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("全ホップが信頼プロキシなら最も左のホップを返す")
    void 全ホップが信頼プロキシなら最左を返す() {
        TrustedProxyClientIpResolver resolver = new TrustedProxyClientIpResolver("10.0.0.0/8");

        assertThat(resolver.resolve(request("10.0.0.1", "10.0.0.3, 10.0.0.2"))).isEqualTo("10.0.0.3");
    }

    @Test
    @DisplayName("IPv6の信頼プロキシとクライアントを扱える")
    void IPv6を扱える() {
        TrustedProxyClientIpResolver resolver = new TrustedProxyClientIpResolver("fd00::/8");

        assertThat(resolver.resolve(request("fd00::1", "2001:db8::7"))).isEqualTo("2001:db8::7");
    }
}
