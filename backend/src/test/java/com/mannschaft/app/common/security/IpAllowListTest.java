package com.mannschaft.app.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IpAllowList} 単体テスト（CMP-261127-1814）。
 *
 * <p>単一 IP・CIDR・IPv6 を照合でき、IP リテラルでない値（ホスト名・壊れた値）は
 * 名前解決をせずに「書式不正」として扱うことを固定する。</p>
 */
@DisplayName("IpAllowList 単体テスト")
class IpAllowListTest {

    @Test
    @DisplayName("単一IPv4は完全一致のみ許可する")
    void 単一IPv4は完全一致のみ許可する() {
        IpAllowList list = IpAllowList.of(List.of("192.168.1.100"));

        assertThat(list.contains("192.168.1.100")).isTrue();
        assertThat(list.contains("192.168.1.101")).isFalse();
    }

    @Test
    @DisplayName("IPv4のCIDRは範囲内のみ許可する")
    void IPv4のCIDRは範囲内のみ許可する() {
        IpAllowList list = IpAllowList.of(List.of("10.0.0.0/24"));

        assertThat(list.contains("10.0.0.1")).isTrue();
        assertThat(list.contains("10.0.0.255")).isTrue();
        assertThat(list.contains("10.0.1.1")).isFalse();
    }

    @Test
    @DisplayName("IPv6の単一アドレスとCIDRを照合できる")
    void IPv6の単一アドレスとCIDRを照合できる() {
        IpAllowList list = IpAllowList.of(List.of("2001:db8::1", "2001:db8:abcd::/48"));

        assertThat(list.contains("2001:db8::1")).isTrue();
        assertThat(list.contains("2001:0db8:0:0:0:0:0:1")).isTrue();
        assertThat(list.contains("2001:db8:abcd:12::5")).isTrue();
        assertThat(list.contains("2001:db8:abce::5")).isFalse();
    }

    @Test
    @DisplayName("IPv4の許可リストにIPv6の送信元は一致しない")
    void IPv4の許可リストにIPv6の送信元は一致しない() {
        IpAllowList list = IpAllowList.of(List.of("10.0.0.0/8"));

        assertThat(list.contains("::1")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "example.com", "999.1.1.1", "10.0.0.0/33", "10.0.0.0/-1",
            "1.2.3", "", " ", "10.0.0.1\"]", "2001:db8::/129", "::1/abc", "10.0.0.0/"})
    @DisplayName("IPリテラルでない値・範囲外のプレフィックスは書式不正")
    void IPリテラルでない値は書式不正(String entry) {
        assertThat(IpAllowList.isValidEntry(entry)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.100", "10.0.0.0/24", "0.0.0.0/0", "::1", "2001:db8::/32",
            "::ffff:192.0.2.1"})
    @DisplayName("単一IP・CIDR・IPv6は書式として正しい")
    void 単一IPとCIDRとIPv6は書式として正しい(String entry) {
        assertThat(IpAllowList.isValidEntry(entry)).isTrue();
    }

    @Test
    @DisplayName("送信元がIPリテラルでなければ一致しない（名前解決しない）")
    void 送信元がIPリテラルでなければ一致しない() {
        IpAllowList list = IpAllowList.of(List.of("0.0.0.0/0"));

        assertThat(list.contains("localhost")).isFalse();
        assertThat(list.contains(null)).isFalse();
        assertThat(list.contains("")).isFalse();
    }

    @Test
    @DisplayName("書式不正の項目は無視し、全項目が不正なら何も許可しない（fail-closed）")
    void 書式不正の項目だけなら何も許可しない() {
        IpAllowList list = IpAllowList.of(List.of("not-an-ip"));

        assertThat(list.isEmpty()).isTrue();
        assertThat(list.contains("127.0.0.1")).isFalse();
    }
}
