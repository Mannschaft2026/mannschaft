package com.mannschaft.app.common.security;

import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * IP アドレスの許可リスト（単一 IP または CIDR。IPv4 / IPv6）。
 *
 * <p>照合は Spring Security の {@link IpAddressMatcher} に委ねる。ただし同クラスは
 * {@code InetAddress.getByName} を使うため、IP リテラルでない値（ホスト名や {@code 999.1.1.1}
 * のような範囲外の値）を渡すと DNS 解決が走る。これを避けるため、項目も照合対象も
 * 先に {@link #isIpLiteral(String)} で IP リテラルであることを確かめてから渡す。</p>
 *
 * <p>書式不正の項目は無視する。結果として有効な項目が 1 つも無いリストは何も許可しない
 * （fail-closed）。「制限しない」は呼び出し側が「リスト自体を持たない」ことで表す。</p>
 */
public final class IpAllowList {

    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    /** IPv6 は文字種だけ先に絞り、構文の正しさは {@code InetAddress}（IPv6 リテラルは DNS を引かない）に任せる。 */
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]+$");
    private static final Pattern PREFIX = Pattern.compile("^\\d{1,3}$");

    private final List<IpAddressMatcher> matchers;

    private IpAllowList(List<IpAddressMatcher> matchers) {
        this.matchers = matchers;
    }

    /**
     * 項目一覧から許可リストを作る。書式不正の項目は捨てる。
     *
     * @param entries 単一 IP または CIDR の一覧（null 可）
     * @return 許可リスト
     */
    public static IpAllowList of(Collection<String> entries) {
        List<IpAddressMatcher> list = new ArrayList<>();
        if (entries != null) {
            for (String entry : entries) {
                if (isValidEntry(entry)) {
                    list.add(new IpAddressMatcher(entry.trim()));
                }
            }
        }
        return new IpAllowList(List.copyOf(list));
    }

    /** 有効な項目が 1 つも無いか。 */
    public boolean isEmpty() {
        return matchers.isEmpty();
    }

    /**
     * 指定 IP がいずれかの項目に一致するか。IP リテラルでない値は常に不一致。
     *
     * @param ip 照合する IP アドレス（null 可）
     * @return 一致すれば true
     */
    public boolean contains(String ip) {
        if (!isIpLiteral(ip)) {
            return false;
        }
        String address = ip.trim();
        for (IpAddressMatcher matcher : matchers) {
            if (matcher.matches(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 単一 IP または CIDR として正しい書式か。
     *
     * @param entry 検査する値
     * @return 正しければ true
     */
    public static boolean isValidEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return false;
        }
        String value = entry.trim();
        int slash = value.indexOf('/');
        String address = slash < 0 ? value : value.substring(0, slash);
        if (!isIpLiteral(address)) {
            return false;
        }
        if (slash < 0) {
            return true;
        }
        String prefix = value.substring(slash + 1);
        if (!PREFIX.matcher(prefix).matches()) {
            return false;
        }
        int bits = Integer.parseInt(prefix);
        int max = IPV4.matcher(address).matches() ? 32 : 128;
        if (bits > max) {
            return false;
        }
        try {
            new IpAddressMatcher(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * IP リテラル（IPv4 のドット区切り 10 進、または IPv6）か。名前解決は行わない。
     *
     * @param value 検査する値
     * @return IP リテラルなら true
     */
    public static boolean isIpLiteral(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim();
        if (IPV4.matcher(v).matches()) {
            return true;
        }
        if (v.indexOf(':') < 0 || !IPV6_CHARS.matcher(v).matches()) {
            return false;
        }
        try {
            // ':' を含む値は IPv6 リテラルとして解析され、DNS は引かない
            java.net.InetAddress.getByName(v);
            return true;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}
