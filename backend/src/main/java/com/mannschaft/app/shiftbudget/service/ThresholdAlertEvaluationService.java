package com.mannschaft.app.shiftbudget.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.entity.BudgetThresholdAlertEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.event.BudgetThresholdAlertTriggeredEvent;
import com.mannschaft.app.shiftbudget.repository.BudgetThresholdAlertRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.workflow.dto.CreateWorkflowRequestRequest;
import com.mannschaft.app.workflow.dto.WorkflowRequestResponse;
import com.mannschaft.app.workflow.service.WorkflowRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F08.7 シフト予算 閾値超過警告 評価サービス（Phase 9-δ 第2段）。
 *
 * <p>設計書 F08.7 (v1.2) §3 UC-5 / §4.5 / §5.5 / §9.1 に準拠。</p>
 *
 * <p>消化記録 ({@code shift_budget_consumptions}) の INSERT/CANCEL の度に
 * {@link com.mannschaft.app.shiftbudget.listener.ShiftBudgetConsumptionRecordListener}
 * 等の hook から呼ばれ、当該割当の現在消化率を再計算して
 * 80% / 100% / 120% の閾値ごとに警告レコード ({@code budget_threshold_alerts}) を発火する。</p>
 *
 * <p>冪等性保証:</p>
 * <ul>
 *   <li>UNIQUE (allocation_id, threshold_percent) により重複発火不可（DB 層での真の防衛線）</li>
 *   <li>アプリ層でも {@link BudgetThresholdAlertRepository#findByAllocationIdAndThresholdPercent}
 *       で事前確認 → 既存ありなら skip</li>
 * </ul>
 *
 * <p>F05.6 ワークフロー連携 (100% 到達時): Phase 10-α で本格実装。
 * {@code budget_configs.over_limit_workflow_id} を参照し、設定済なら
 * {@link WorkflowRequestService#createRequest} + {@code submitRequest} を呼び出し、
 * 起動結果の {@code workflow_request_id} を {@code budget_threshold_alerts} に書戻す。
 * 未設定時は監査ログ {@code WORKFLOW_NOT_CONFIGURED} のみ記録。</p>
 *
 * <p>本サービスは AFTER_COMMIT hook から呼ばれるが、自身も例外を握りつぶす責務は
 * 呼び出し側 (Listener) に委譲する。本サービス内の例外は呼び出し側で個別 catch される前提。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThresholdAlertEvaluationService {

    /** 評価対象の閾値（昇順）。設計書 §4.5 / §5.5 chk_bta_threshold で固定。 */
    private static final int[] THRESHOLDS = {80, 100, 120};

    /** F05.6 起動の許容上限（100% 以上のみ）。 */
    private static final int WORKFLOW_TRIGGER_THRESHOLD = 100;

    private final ShiftBudgetAllocationRepository allocationRepository;
    private final BudgetThresholdAlertRepository alertRepository;
    private final BudgetConfigRepository budgetConfigRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuditLogService auditLogService;
    private final WorkflowRequestService workflowRequestService;
    private final ObjectMapper objectMapper;
    /** Phase 10-β で追加: 失敗イベントの永続化 */
    private final ShiftBudgetFailedEventService failedEventService;
    /** Issue #2990 L13: 通知を業務コミット後（AFTER_COMMIT）へ逃がすためのイベント publish。 */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * CMP-260909-2152: 訂正後の消化額で判定するための、訂正差額合計の読取口（同ドメインの値の受け口）と TX マネージャ。
     *
     * <p>コンストラクタ引数に足さずフィールド注入にしているのは、ArchUnit の D-5 凍結キーがコンストラクタの
     * シグネチャを含むため、引数を変えると既存の凍結済み違反が別キーの「新しい違反」として数えられるから
     * （新しい凍結キーを増やさない方針）。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired
    private ShiftBudgetCorrectionDeltaReader correctionDeltaReader;
    @org.springframework.beans.factory.annotation.Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 指定 allocation について現在消化率を計算し、80/100/120% 閾値の発火判定を行う。
     *
     * <p>呼び出し側 ({@code ShiftBudgetConsumptionRecordListener}) は AFTER_COMMIT hook 内で
     * 例外を握りつぶす責務を持つ。本メソッドは例外をそのまま伝播させる
     * （個別 (slot,user) ごとに try-catch して残り処理を続行する責任は呼び出し側）。</p>
     *
     * <p>独立トランザクション（REQUIRES_NEW）で評価することで、呼出元の失敗が評価を巻き戻さず、
     * 通知（AFTER_COMMIT）がこの評価のコミット後に配送される。</p>
     *
     * <p>CMP-260909-2152: 判定に使う消化額は、月次締め後の訂正を反映した<b>訂正後の消化額</b>（全入口で共通）。
     * 訂正の差額は budget の値なので、TX に入る前に取得して値だけを TX の中へ持ち込む
     * （TX 内で他ドメインを呼ばない）。TX の入口をこのメソッドの注釈ではなく {@link TransactionTemplate} で
     * 開くのはそのため。</p>
     *
     * @param allocationId 評価対象 allocation の ID
     */
    public void evaluateAndTrigger(Long allocationId) {
        BigDecimal correctionDelta = correctionDeltaReader.totalOf(allocationId);
        inNewTransaction(() -> evaluate(allocationId, null, correctionDelta));
    }

    /**
     * 訂正差額を見ずに、割当のキャッシュ（未訂正の消化額）だけで閾値を判定する。
     *
     * <p>{@code @Transactional} を持つ入口（失敗イベントの再試行 {@code ShiftBudgetRetryExecutor#execute} など）は、
     * {@link #evaluateAndTrigger} を呼ぶと訂正差額の取得（budget の読取）を TX の中から辿ってしまう
     * （D-3T の新しい凍結キーになる）。そのため再試行の入口は本メソッドを使う。再試行の TX 分離は別 PR（#4559）で
     * 段取り役に移し、そこで {@link #evaluateAndTrigger}（訂正後の消化額）へ切り替える。</p>
     */
    public void evaluateOnCachedAmounts(Long allocationId) {
        inNewTransaction(() -> evaluate(allocationId, null, BigDecimal.ZERO));
    }

    private void inNewTransaction(Runnable action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> action.run());
    }

    /**
     * 月次締め後の訂正で消化額が変わったとき、<b>訂正後の消化額</b>で閾値を再判定する（CMP-260909-2152）。
     *
     * <p>段取り役（訂正の Coordinator）が訂正のコミット後に TX の外から呼ぶ。判定の中身は
     * {@link #evaluateAndTrigger} と同じで、消化額だけ割当のキャッシュではなく呼出側が算出した訂正後の値を使う。
     * 通知（AFTER_COMMIT）が配送されるよう、<b>呼出側が独立トランザクション（REQUIRES_NEW）で呼ぶこと</b>。
     * 本メソッド自体は {@code @Transactional} を持たない（コンストラクタ引数と TX 入口を増やすと、
     * このクラスの他ドメイン Repository 依存の凍結キーが新しい違反として数えられるため）。
     * 閾値・ワークフロー起動の TX 分離（Coordinator 化）は別戦役で行うため、ここでは既存の評価を再利用するに留める。</p>
     *
     * @param effectiveConsumed 訂正後の消化額
     */
    public void evaluateWithEffectiveConsumed(Long allocationId, BigDecimal effectiveConsumed) {
        evaluate(allocationId, effectiveConsumed, BigDecimal.ZERO);
    }

    private void evaluate(Long allocationId, BigDecimal consumedOverride, BigDecimal correctionDelta) {
        ShiftBudgetAllocationEntity allocation = allocationRepository.findById(allocationId)
                .orElse(null);
        if (allocation == null || allocation.getDeletedAt() != null) {
            log.debug("F08.7 閾値判定: 対象 allocation 不在/論理削除済のためスキップ: id={}", allocationId);
            return;
        }

        BigDecimal allocated = allocation.getAllocatedAmount();
        BigDecimal consumed = consumedOverride != null ? consumedOverride : correctedConsumed(allocation, correctionDelta);
        if (allocated == null || allocated.signum() == 0) {
            // 予算ゼロ円の境界ケース: 設計書 §11 では即 100% 超過判定とするが、
            // 本実装では「割当0+消化0=0%」として WARN/EXCEEDED いずれも発火させない方針
            // （割当0+消化>0 は理論上ありえる：その場合は 100/120% 発火相当と扱う）
            if (consumed != null && consumed.signum() > 0) {
                triggerForZeroBudget(allocation, consumed);
            }
            return;
        }

        // 消化率を 4 桁小数で算出（BigDecimal で厳密計算）
        BigDecimal rate = consumed.divide(allocated, 4, RoundingMode.HALF_UP);
        BigDecimal percent = rate.multiply(BigDecimal.valueOf(100));

        for (int threshold : THRESHOLDS) {
            if (percent.compareTo(BigDecimal.valueOf(threshold)) >= 0) {
                fireAlertIfNotExists(allocation, threshold, consumed);
            }
        }
    }

    /** キャッシュの消化額・確定額と訂正差額から、訂正後の消化額を出す（閾値判定・reader 共通の式）。 */
    private static BigDecimal correctedConsumed(ShiftBudgetAllocationEntity allocation, BigDecimal correctionDelta) {
        if (allocation.getConsumedAmount() == null) {
            return null;
        }
        BigDecimal confirmed = allocation.getConfirmedAmount() == null ? BigDecimal.ZERO : allocation.getConfirmedAmount();
        return CorrectedAmounts.consumed(allocation.getConsumedAmount(), confirmed, correctionDelta);
    }

    /**
     * 予算 0 円で消化発生したケースの特殊処理（設計書 §11 境界ケース）。
     */
    private void triggerForZeroBudget(ShiftBudgetAllocationEntity allocation, BigDecimal consumed) {
        // 100% / 120% 相当として扱う
        fireAlertIfNotExists(allocation, 100, consumed);
        fireAlertIfNotExists(allocation, 120, consumed);
    }

    /**
     * 指定閾値の警告レコードが既存でなければ INSERT + 通知発火する。
     *
     * <p>UNIQUE (allocation_id, threshold_percent) により真の防衛線は DB 層だが、
     * アプリ層でも事前 SELECT で重複を抑止して通知の重送を防ぐ。</p>
     */
    private void fireAlertIfNotExists(ShiftBudgetAllocationEntity allocation,
                                      int thresholdPercent,
                                      BigDecimal consumedAtTrigger) {
        if (alertRepository.findByAllocationIdAndThresholdPercent(
                allocation.getId(), thresholdPercent).isPresent()) {
            log.debug("F08.7 閾値判定: 既存 alert あり → skip: allocId={}, threshold={}%",
                    allocation.getId(), thresholdPercent);
            return;
        }

        // 受信ロール解決: 当該組織の ADMIN/DEPUTY_ADMIN + BUDGET_ADMIN 保有者の和集合
        List<Long> recipientUserIds = resolveRecipients(allocation.getOrganizationId());

        // alert レコード INSERT（UNIQUE で重複時は DataIntegrityViolationException、上位で握りつぶす）
        BudgetThresholdAlertEntity alert = BudgetThresholdAlertEntity.builder()
                .allocationId(allocation.getId())
                .thresholdPercent(thresholdPercent)
                .triggeredAt(LocalDateTime.now())
                .consumedAmountAtTrigger(consumedAtTrigger)
                .notifiedUserIds(serializeUserIds(recipientUserIds))
                .build();
        BudgetThresholdAlertEntity savedAlert;
        try {
            savedAlert = alertRepository.saveAndFlush(alert);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 並行 INSERT 競合（UNIQUE 違反）— 既に他スレッドが先に発火させた → skip
            log.debug("F08.7 閾値判定: 並行 INSERT 競合（UNIQUE 違反）でスキップ: allocId={}, threshold={}%",
                    allocation.getId(), thresholdPercent);
            return;
        }

        // 通知発火（業務コミット後に配送させるためイベント publish のみ行う）
        publishThresholdAlertEvent(allocation, savedAlert.getId(), thresholdPercent, recipientUserIds);

        // F05.6 ワークフロー起動（100% 到達時のみ）
        if (thresholdPercent >= WORKFLOW_TRIGGER_THRESHOLD) {
            tryStartWorkflow(allocation, savedAlert);
        }

        // 監査ログ
        auditLogService.record(
                "BUDGET_THRESHOLD_ALERT_TRIGGERED",
                null, null,
                allocation.getTeamId(), allocation.getOrganizationId(),
                null, null, null,
                String.format("{\"allocation_id\":%d,\"threshold_percent\":%d,"
                                + "\"consumed_amount\":%s,\"recipient_count\":%d}",
                        allocation.getId(), thresholdPercent,
                        consumedAtTrigger, recipientUserIds.size()));

        log.info("F08.7 閾値超過警告を発火: allocId={}, threshold={}%, recipients={}",
                allocation.getId(), thresholdPercent, recipientUserIds.size());
    }

    /**
     * 受信ロール解決: ADMIN/DEPUTY_ADMIN ∪ BUDGET_ADMIN 保有者。
     *
     * <p>設計書 §4.5 「対象ロール（ADMIN + 予算管理者）」に対応。
     * V11.034 マイグレーション後は両者がほぼ重複するが、組織が個別に
     * BUDGET_ADMIN を別ユーザーへ追加付与しているケースを取りこぼさないため両系を OR で結合する。</p>
     */
    private List<Long> resolveRecipients(Long organizationId) {
        Set<Long> uniq = new HashSet<>();
        uniq.addAll(userRoleRepository.findAdminUserIdsByOrganizationId(organizationId));
        uniq.addAll(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(
                organizationId, "BUDGET_ADMIN"));
        List<Long> sorted = new ArrayList<>(uniq);
        sorted.sort(Long::compareTo);
        return sorted;
    }

    /**
     * 閾値超過警告の発火イベントを publish する（Issue #2990 L13）。
     *
     * <p>是正前はここで {@code notificationHelper.notifyAllLocalized(...)} を直接呼んでいたが、
     * その下流 {@code NotificationService#createNotification} は既定の {@code REQUIRED} 伝播で
     * 本メソッドのトランザクションに参加するため、通知の DB 例外が
     * <b>try/catch で握られていてもトランザクションを rollback-only にし</b>、
     * 直前に INSERT した {@code budget_threshold_alerts} 行・ワークフロー起動の書戻し・
     * 監査ログをまとめて巻き戻していた（commit 時の {@code UnexpectedRollbackException}）。</p>
     *
     * <p>配送は {@code ShiftBudgetThresholdAlertNotificationListener} が
     * {@code AFTER_COMMIT} + {@code @Async("event-pool")} で行う。
     * イベントには ID と閾値だけを載せる（描画済み文字列も JPA 管理エンティティも載せない）。</p>
     */
    private void publishThresholdAlertEvent(ShiftBudgetAllocationEntity allocation,
                                            Long alertId,
                                            int thresholdPercent,
                                            List<Long> recipientUserIds) {
        eventPublisher.publishEvent(new BudgetThresholdAlertTriggeredEvent(
                alertId,
                allocation.getId(),
                allocation.getOrganizationId(),
                thresholdPercent,
                List.copyOf(recipientUserIds)));
    }

    /**
     * F05.6 ワークフロー起動（100% 到達時）— Phase 10-α 本格実装。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>{@code budget_configs.over_limit_workflow_id} を参照</li>
     *   <li>未設定 → 監査ログ {@code WORKFLOW_NOT_CONFIGURED} のみ記録（既存挙動維持）</li>
     *   <li>設定済 → {@link WorkflowRequestService#createRequest} + {@code submitRequest} を呼び出し
     *       起動結果の {@code workflow_request_id} を {@code budget_threshold_alerts} に書戻</li>
     *   <li>起動成功 → 監査ログ {@code WORKFLOW_STARTED_FROM_BUDGET}</li>
     *   <li>起動失敗（例外） → try-catch で握りつぶし、ERROR ログ + 監査ログ
     *       {@code WORKFLOW_START_FAILED}（main トランザクション保護、9-δ AFTER_COMMIT パターン踏襲）</li>
     * </ol>
     *
     * <p>申請者ユーザー（{@code requestedBy}）はシステム自動起動のため null とする
     * （{@code workflow_requests.requested_by} は NULL 許容）。</p>
     *
     * @param allocation 警告発火対象の割当
     * @param alert      INSERT 済の警告レコード（成功時に workflow_request_id を書戻）
     */
    private void tryStartWorkflow(ShiftBudgetAllocationEntity allocation,
                                   BudgetThresholdAlertEntity alert) {
        Long workflowId = budgetConfigRepository
                .findByScopeTypeAndScopeId("ORGANIZATION", allocation.getOrganizationId())
                .map(BudgetConfigEntity::getOverLimitWorkflowId)
                .orElse(null);
        if (workflowId == null) {
            auditLogService.record(
                    "WORKFLOW_NOT_CONFIGURED",
                    null, null,
                    allocation.getTeamId(), allocation.getOrganizationId(),
                    null, null, null,
                    String.format("{\"allocation_id\":%d,\"reason\":\"over_limit_workflow_id is null\"}",
                            allocation.getId()));
            log.info("F08.7 100% 到達: 組織が over_limit_workflow_id 未設定のためワークフロー起動スキップ: "
                    + "allocId={}, orgId={}", allocation.getId(), allocation.getOrganizationId());
            return;
        }

        // F05.6 起動 — 例外は握りつぶす（main トランザクションを保護）
        // Issue #2715 CMP-055 ロットC-4 スコープ確認: この title は notify()/createNotification() へは渡らず、
        // WorkflowRequestService が保持する workflow_requests.title（承認者複数名が閲覧する管理レコードの
        // タイトル）である。単一の「受信者 locale」が存在しないため本ロットの i18n パターン（受信者ごとの
        // messageSource 解決）をそのまま適用できず、意図的に対象外とした。
        try {
            String title = String.format("シフト予算 超過承認 (allocation #%d, %d%%)",
                    allocation.getId(), alert.getThresholdPercent());
            CreateWorkflowRequestRequest createReq = new CreateWorkflowRequestRequest(
                    workflowId,
                    title,
                    null,                              // fieldValues: 自動起動のため未設定
                    "BUDGET_THRESHOLD_ALERT",          // sourceType: 設計書 §12.4 連携元識別子
                    alert.getId()                      // sourceId: 警告 ID で紐付
            );
            WorkflowRequestResponse created = workflowRequestService.createRequest(
                    "ORGANIZATION", allocation.getOrganizationId(), null, createReq);
            WorkflowRequestResponse submitted = workflowRequestService.submitRequest(
                    "ORGANIZATION", allocation.getOrganizationId(), created.getId());

            // 起動結果を alert に書戻
            alert.linkWorkflowRequest(submitted.getId());
            alertRepository.save(alert);

            auditLogService.record(
                    "WORKFLOW_STARTED_FROM_BUDGET",
                    null, null,
                    allocation.getTeamId(), allocation.getOrganizationId(),
                    null, null, null,
                    String.format("{\"allocation_id\":%d,\"alert_id\":%d,\"workflow_id\":%d,"
                                    + "\"workflow_request_id\":%d}",
                            allocation.getId(), alert.getId(), workflowId, submitted.getId()));
            log.info("F08.7 100% 到達: ワークフロー起動成功: allocId={}, alertId={}, "
                            + "workflowId={}, requestId={}",
                    allocation.getId(), alert.getId(), workflowId, submitted.getId());
        } catch (Exception e) {
            // 設計書 障害対応の原則: AFTER_COMMIT hook と同じく、ワークフロー起動失敗は
            // main の警告発火トランザクションを巻き戻させない。ERROR ログ + 監査で運用補正の起点とする。
            log.error("F08.7 100% 到達: ワークフロー起動失敗 (握りつぶし): allocId={}, "
                            + "alertId={}, workflowId={}",
                    allocation.getId(), alert.getId(), workflowId, e);
            auditLogService.record(
                    "WORKFLOW_START_FAILED",
                    null, null,
                    allocation.getTeamId(), allocation.getOrganizationId(),
                    null, null, null,
                    String.format("{\"allocation_id\":%d,\"alert_id\":%d,\"workflow_id\":%d,"
                                    + "\"error\":\"%s\"}",
                            allocation.getId(), alert.getId(), workflowId,
                            escapeJson(e.getClass().getSimpleName() + ": " + e.getMessage())));
            // Phase 10-β: failed_events にも記録 → リトライバッチ + 管理 API で根治
            try {
                failedEventService.recordFailure(
                        allocation.getOrganizationId(),
                        ShiftBudgetFailedEventType.WORKFLOW_START,
                        alert.getId(),
                        Map.of(
                                "allocation_id", allocation.getId(),
                                "alert_id", alert.getId(),
                                "workflow_id", workflowId
                        ),
                        e.getClass().getSimpleName() + ": " + e.getMessage()
                );
            } catch (Exception recEx) {
                log.error("F08.7: WORKFLOW_START failed_events 記録自体も失敗（諦め）: "
                                + "allocId={}, alertId={}",
                        allocation.getId(), alert.getId(), recEx);
            }
        }
    }

    /**
     * 監査ログ JSON フィールドへ埋め込む文字列の最低限のエスケープ。
     * 例外メッセージにダブルクォート・改行・バックスラッシュが含まれても JSON 構造を壊さないようにする。
     */
    private String escapeJson(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String serializeUserIds(List<Long> userIds) {
        try {
            return objectMapper.writeValueAsString(userIds);
        } catch (JsonProcessingException e) {
            log.warn("F08.7 通知先ユーザーIDのシリアライズ失敗: {}", userIds, e);
            return "[]";
        }
    }
}
