package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.supportaccess.SupportAccessAuditAction;
import com.mannschaft.app.supportaccess.SupportAccessEventKind;
import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import com.mannschaft.app.supportaccess.context.SupportAccessWritable;
import com.mannschaft.app.supportaccess.entity.SupportAccessLogEntity;
import com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent;
import com.mannschaft.app.supportaccess.repository.SupportAccessLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F10.9 閲覧記録を業務 TX から独立して書く（MfaFailureAuditRecorder と同じ型: 別 Bean・REQUIRES_NEW）。
 *
 * <p>各 public メソッドは {@code REQUIRES_NEW}。代理中でも書込可能な接続で書く。接続は TX 開始時（メソッド本体の前）に
 * 取得されるため、{@link SupportAccessWritable}（{@code SupportAccessWritableAspect}）が TX の外側で書込可能な区間を開く。
 * 業務 TX が巻き戻っても記録は残る。
 * 呼び出し側（SupportAccessFilter）は {@link #recordView} が失敗したら 503 で fail-closed にする。</p>
 *
 * <p><b>デッドロック注意</b>: 閲覧記録は grant への FK を持つ。呼び出し側の業務 TX が同じ grant 行を
 * 更新済み（未コミット）の状態でこのクラスを呼ぶと、親行のロック待ちで自己デッドロックする。
 * GrantService は grant を書き換える<b>前</b>にこのクラスを呼ぶこと。</p>
 */
@Component
@SupportAccessWritable
public class SupportAccessLogRecorder {

    private static final int MAX_PATH_LENGTH = 512;

    /** /api/v1/{teams|organizations|villages|committees}/{id}/... のスコープ推定。 */
    private static final Pattern SCOPE_PATH = Pattern.compile(
            "^/api/v1/(teams|organizations|villages|committees)/(\\d+)(?:/|$)");

    private final SupportAccessLogRepository logRepository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public SupportAccessLogRecorder(SupportAccessLogRepository logRepository,
                                    @Qualifier("wallClock") Clock clock,
                                    ApplicationEventPublisher eventPublisher) {
        this.logRepository = logRepository;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    /** 単体テスト用（拒否の監査イベントは publish しない）。 */
    public SupportAccessLogRecorder(SupportAccessLogRepository logRepository, Clock clock) {
        this(logRepository, clock, null);
    }

    /** chain の前に VIEW 行を書く。失敗したら呼び出し側（フィルター）は 503 で fail-closed。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID recordView(SupportAccessContext context, String httpMethod, String requestPath) {
        String path = stripQuery(requestPath);
        SupportAccessLogEntity row = base(context.grantId(), context.operatorUserId(), context.targetUserId(),
                SupportAccessEventKind.VIEW)
                .httpMethod(httpMethod)
                .requestPath(path)
                .build();
        return SupportAccessContextHolder.callWithWritableConnection(() -> logRepository.save(withScope(row, path)).getId());
    }

    /** chain の後に応答状態と最適一致パターンを書き足す。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeView(UUID logId, int httpStatus, String routePattern) {
        if (logId == null) {
            return;
        }
        SupportAccessContextHolder.callWithWritableConnection(() -> {
            logRepository.findById(logId).ifPresent(row -> {
                row.complete(httpStatus, routePattern);
                logRepository.save(row);
            });
            return null;
        });
    }

    /**
     * 拒否を記録する。grant が実在する場合は support_access_logs に DENIED 行（本人から見える）、
     * いずれの場合も監査（SUPPORT_ACCESS_DENIED_REQUEST）に残す（コミット後に {@code SupportAccessAuditListener} が書く）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(Long operatorUserId, String grantIdHeader, String httpMethod, String requestPath, String denyCode) {
        UUID grantId = parseUuid(grantIdHeader);
        Long targetUserId = grantId == null ? null : logRepository.findTargetUserIdByGrantId(grantId).orElse(null);
        String path = stripQuery(requestPath);
        SupportAccessContextHolder.callWithWritableConnection(() -> {
            if (grantId != null && targetUserId != null) {
                SupportAccessLogEntity row = base(grantId, operatorUserId, targetUserId, SupportAccessEventKind.DENIED)
                        .httpMethod(httpMethod)
                        .requestPath(path)
                        .denyCode(denyCode)
                        .build();
                logRepository.save(withScope(row, path));
            }
            if (eventPublisher != null) {
                // 監査（他ドメイン auth の Repository）はこの TX の中では書かない。コミット後に
                // SupportAccessAuditListener が書く（D-3T: supportaccess の TX を他ドメインへ到達させない）。
                eventPublisher.publishEvent(new SupportAccessGrantChangedEvent(
                        SupportAccessAuditAction.DENIED_REQUEST, grantId, operatorUserId, targetUserId, null));
            }
            return null;
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSessionStart(SupportAccessContext context) {
        recordSession(context, SupportAccessEventKind.SESSION_START);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSessionEnd(SupportAccessContext context) {
        recordSession(context, SupportAccessEventKind.SESSION_END);
    }

    private void recordSession(SupportAccessContext context, SupportAccessEventKind kind) {
        SupportAccessLogEntity row = base(context.grantId(), context.operatorUserId(), context.targetUserId(), kind).build();
        SupportAccessContextHolder.callWithWritableConnection(() -> logRepository.save(row));
    }

    private SupportAccessLogEntity.SupportAccessLogEntityBuilder<?, ?> base(UUID grantId, Long operatorUserId,
                                                                          Long targetUserId, SupportAccessEventKind kind) {
        return SupportAccessLogEntity.builder()
                .grantId(grantId)
                .operatorUserId(operatorUserId)
                .targetUserId(targetUserId)
                .eventKind(kind)
                .occurredAt(LocalDateTime.now(clock));
    }

    /** パスからスコープ（TEAM/ORGANIZATION/VILLAGE/COMMITTEE/PERSONAL）を推定して付ける。 */
    private static SupportAccessLogEntity withScope(SupportAccessLogEntity row, String path) {
        String scopeType = null;
        Long scopeId = null;
        if (path != null) {
            Matcher m = SCOPE_PATH.matcher(path);
            if (m.find()) {
                scopeType = switch (m.group(1)) {
                    case "teams" -> "TEAM";
                    case "organizations" -> "ORGANIZATION";
                    case "villages" -> "VILLAGE";
                    default -> "COMMITTEE";
                };
                scopeId = Long.parseLong(m.group(2));
            } else if (path.startsWith("/api/v1/users/me") || path.startsWith("/api/v1/me/") || path.equals("/api/v1/me")) {
                scopeType = "PERSONAL";
            }
        }
        return row.toBuilder().scopeType(scopeType).scopeId(scopeId).build();
    }

    /** クエリ文字列・フラグメントは保存しない（PII 回避）。長すぎるパスは切り詰める。 */
    private static String stripQuery(String requestPath) {
        if (requestPath == null) {
            return null;
        }
        String path = requestPath;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int h = path.indexOf('#');
        if (h >= 0) {
            path = path.substring(0, h);
        }
        return path.length() > MAX_PATH_LENGTH ? path.substring(0, MAX_PATH_LENGTH) : path;
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
