package com.mannschaft.app.supportaccess.port;

/**
 * F10.9 運営アカウントがテナント（チーム・組織・村・サポーター）に所属しているか（S7・010）。
 *
 * <p>実装役は Z-A PR2 の {@code PlatformAccountAffiliationPolicy} へ委譲する（未マージなら依存）。</p>
 */
public interface SupportAccessOperatorAffiliationPort {

    boolean isAffiliatedWithAnyTenant(Long operatorUserId);
}
