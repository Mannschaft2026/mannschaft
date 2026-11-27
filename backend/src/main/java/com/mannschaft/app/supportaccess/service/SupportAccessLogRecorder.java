package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.supportaccess.SupportAccessAuditAction;
import com.mannschaft.app.supportaccess.SupportAccessEventKind;
import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import com.mannschaft.app.supportaccess.context.SupportAccessWritable;
import com.mannschaft.app.supportaccess.entity.SupportAccessAuditOutboxEntity;
import com.mannschaft.app.supportaccess.entity.SupportAccessLogEntity;
import com.mannschaft.app.supportaccess.event.SupportAccessDeniedAuditAppendedEvent;
import com.mannschaft.app.supportaccess.repository.SupportAccessAuditOutboxRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
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
 * <p><b>デッドロック注意（書込順の固定）</b>: 閲覧記録は grant への FK を持ち、INSERT は親の grant 行に共有ロックを取る。
 * 呼び出し側の TX が同じ grant 行を排他ロック（{@code findByIdForUpdate}）または更新済み（未コミット・未 flush の
 * dirty も、間に JPQL が入ると自動 flush で UPDATE が先に出る）の状態でこのクラスの REQUIRES_NEW を呼ぶと、
 * 親行のロック待ちで自分自身を待つ。そのため grant を遷移させる処理（閲覧開始・終了）の閲覧記録は本クラスでは書かず、
 * {@code SupportAccessGrantTransitionService} が同じ TX で書く。本クラスを呼ぶのは grant 行を書き換えない
 * 呼び出し側（{@code SupportAccessFilter}）だけに限る。本クラスの各メソッドは自分の TX の中で grant 行を書き換えない。</p>
 *
 * <p>拒否の監査は、閲覧記録と同じ REQUIRES_NEW の TX で監査 outbox（support_access_audit_outbox）に1行書き、
 * コミット後に {@code SupportAccessAuditListener} が audit_logs へ反映する（失敗は outbox に残り再試行バッチが拾う）。</p>
 */
@Slf4j
@Component
@SupportAccessWritable
public class SupportAccessLogRecorder {

    private static final int MAX_PATH_LENGTH = 512;

    /** /api/v1/{teams|organizations|villages|committees}/{id}/... のスコープ推定。 */
    private static final Pattern SCOPE_PATH = Pattern.compile(
            "^/api/v1/(teams|organizations|villages|committees)/(\\d+)(?:/|$)");

    private final SupportAccessLogRepository logRepository;
    private final SupportAccessAuditOutboxRepository auditOutboxRepository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public SupportAccessLogRecorder(SupportAccessLogRepository logRepository,
                                    SupportAccessAuditOutboxRepository auditOutboxRepository,
                                    Clock clock,
                                    ApplicationEventPublisher eventPublisher) {
        this.logRepository = logRepository;
        this.auditOutboxRepository = auditOutboxRepository;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    /** 単体テスト用（閲覧記録だけを検証する。拒否の監査 outbox・イベントは扱わない）。 */
    public SupportAccessLogRecorder(SupportAccessLogRepository logRepository, Clock clock) {
        this(logRepository, null, clock, null);
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
     * いずれの場合も監査 outbox（SUPPORT_ACCESS_DENIED_REQUEST）に同じ TX で1行書く。audit_logs への反映は
     * コミット後に {@code SupportAccessAuditListener} が行い、失敗しても outbox に残り再試行バッチが拾う。
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
            UUID auditOutboxId = null;
            if (auditOutboxRepository != null) {
                Instant now = Instant.now(clock);
                auditOutboxId = auditOutboxRepository.save(SupportAccessAuditOutboxEntity.builder()
                        .grantId(targetUserId == null ? null : grantId)
                        .eventType(SupportAccessAuditRecorder.EVENT_PREFIX + SupportAccessAuditAction.DENIED_REQUEST.name())
                        .operatorUserId(operatorUserId)
                        .targetUserId(targetUserId)
                        .occurredAt(now)
                        .attemptCount(0)
                        .nextAttemptAt(now)
                        .build()).getId();
            }
            if (eventPublisher != null && auditOutboxId != null) {
                // 監査（他ドメイン auth の Repository）はこの TX の中では書かない。コミット後に
                // SupportAccessAuditListener がこの1行だけを書く（D-3T: supportaccess の TX を他ドメインへ到達させない）。
                eventPublisher.publishEvent(new SupportAccessDeniedAuditAppendedEvent(auditOutboxId));
            }
            return null;
        });
    }

    private SupportAccessLogEntity.SupportAccessLogEntityBuilder<?, ?> base(UUID grantId, Long operatorUserId,
                                                                          Long targetUserId, SupportAccessEventKind kind) {
        return SupportAccessLogEntity.builder()
                .grantId(grantId)
                .operatorUserId(operatorUserId)
                .targetUserId(targetUserId)
                .eventKind(kind)
                .occurredAt(Instant.now(clock));
    }

    /** パスからスコープ（TEAM/ORGANIZATION/VILLAGE/COMMITTEE/PERSONAL）を推定して付ける。 */
    private static SupportAccessLogEntity withScope(SupportAccessLogEntity row, String path) {
        String scopeType = null;
        Long scopeId = null;
        if (path != null) {
            Matcher m = SCOPE_PATH.matcher(path);
            if (m.find()) {
                scopeId = parseScopeId(m.group(2));
                // 数字が long に収まらない（巨大な数字の細工）ならスコープ不明として記録する（503 にしない）
                if (scopeId != null) {
                    scopeType = switch (m.group(1)) {
                        case "teams" -> "TEAM";
                        case "organizations" -> "ORGANIZATION";
                        case "villages" -> "VILLAGE";
                        default -> "COMMITTEE";
                    };
                }
            } else if (path.startsWith("/api/v1/users/me") || path.startsWith("/api/v1/me/") || path.equals("/api/v1/me")) {
                scopeType = "PERSONAL";
            }
        }
        return row.toBuilder().scopeType(scopeType).scopeId(scopeId).build();
    }

    /** パス中のスコープ ID。long に収まらない値は null（スコープ不明）。 */
    static Long parseScopeId(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            log.warn("サポート閲覧の閲覧記録: スコープ ID が数値の範囲外のためスコープ不明として記録する（桁数={}）",
                    digits == null ? 0 : digits.length());
            return null;
        }
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
