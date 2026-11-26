package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.repository.SupportAccessLogRepository;

import java.time.Clock;
import java.util.UUID;

/**
 * F10.9 閲覧記録を業務 TX から独立して書く（MfaFailureAuditRecorder と同じ型: 別 Bean・REQUIRES_NEW）。試練スタブ。
 *
 * <p>実装役は各 public メソッドに {@code @Transactional(propagation = Propagation.REQUIRES_NEW)} を付け、
 * 代理中でも書込可能な接続で書く（{@code SupportAccessContextHolder#callWithWritableConnection}）。</p>
 */
public class SupportAccessLogRecorder {

    private final SupportAccessLogRepository logRepository;
    private final Clock clock;

    public SupportAccessLogRecorder(SupportAccessLogRepository logRepository, Clock clock) {
        this.logRepository = logRepository;
        this.clock = clock;
    }

    /** chain の前に VIEW 行を書く。失敗したら呼び出し側（フィルター）は 503 で fail-closed。 */
    public UUID recordView(SupportAccessContext context, String httpMethod, String requestPath) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    /** chain の後に応答状態と最適一致パターンを書き足す。 */
    public void completeView(UUID logId, int httpStatus, String routePattern) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    /** 拒否を記録する（grant が実在する場合は support_access_logs に DENIED 行、無い場合は監査のみ）。 */
    public void recordDenied(Long operatorUserId, String grantIdHeader, String httpMethod, String requestPath, String denyCode) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    public void recordSessionStart(SupportAccessContext context) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    public void recordSessionEnd(SupportAccessContext context) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
