package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.event.JobNotificationEvent;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.policy.JobPolicy;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.jobmatching.service.command.ReportCompletionCommand;
import com.mannschaft.app.jobmatching.state.JobContractStateMachine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 求人契約サービス。F13.1 Phase 13.1.1 MVP の中核。
 *
 * <p>本サービスは以下の責務を持つ:</p>
 * <ul>
 *   <li>採用確定（{@link #acceptApplication(Long, Long)}）— 最重要。応募 → 契約生成 + チャット自動作成 + 通知</li>
 *   <li>完了報告・承認・差し戻し（rejectionCount 管理、上限 3 回）</li>
 *   <li>契約キャンセル（Requester または Worker 本人）</li>
 *   <li>契約閲覧（関係者のみ）・契約一覧（関与する全契約）</li>
 * </ul>
 *
 * <p>採用確定の排他は InnoDB の行ロックで行う（CMP-261021-0355）。求人行の {@code SELECT ... FOR UPDATE} で
 * 同一求人への受諾を直列化し、応募行を {@code PESSIMISTIC_WRITE} で読み直し、採用済み件数を
 * {@code SELECT COUNT(*) ... FOR SHARE} で数える。locking read は REPEATABLE READ のスナップショットではなく
 * 最新のコミット済み行を読むため、先行受諾・取り下げ・不採用の結果を見落とさない。行ロックはコミットまで保持される。
 * {@code GET_LOCK} はコミット前に解放されるため排他の保証にはならず、同一求人への受諾どうしの入口の待ちを
 * 打ち切る流量制御としてだけ使う。10 秒で打ち切られるのは {@code GET_LOCK} の待ちだけで、受諾どうしの待ちは
 * 概ね 10 秒で 409 になるが、ほかの操作（不採用・取り下げ・応募）との待ちは求人行・応募行の行ロック待ちで
 * {@code innodb_lock_wait_timeout} に従い、そのタイムアウトやデッドロックは既定の handler で 500 になる。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class JobContractService {

    /** 差し戻し回数の上限（超過で JOB_REJECTION_LIMIT_EXCEEDED）。 */
    private static final int MAX_REJECTION_COUNT = 3;

    /** GET_LOCK（受諾どうしの入口の流量制御）のタイムアウト秒数。GET_LOCK を 10 秒以内に獲得できなければ 409 で返す。 */
    private static final int LOCK_TIMEOUT_SECONDS = 10;

    /** 求人単位のロックキー接頭辞。MySQL GET_LOCK は 64 文字以内。 */
    private static final String LOCK_KEY_PREFIX = "job_posting_";

    private final JobApplicationRepository applicationRepository;
    private final JobPostingRepository postingRepository;
    private final JobContractRepository contractRepository;
    private final JobContractStateMachine stateMachine;
    private final JobPolicy jobPolicy;
    private final JobChatService jobChatService;
    private final ApplicationEventPublisher eventPublisher;

    @PersistenceContext
    private EntityManager entityManager;

    // ---------------------------------------------------------------------
    // 採用確定（MVP 中核）
    // ---------------------------------------------------------------------

    /**
     * 応募を採用確定して契約を成立させる。本サービスの最重要メソッド。
     *
     * <p>排他制御（ロック順は求人行 → 応募行。{@link JobApplicationService} の不採用も同じ順で取る）:</p>
     * <ol>
     *   <li>{@code GET_LOCK('job_posting_{postingId}', 10)} で受諾どうしの入口を絞る（GET_LOCK の待ちが 10 秒を
     *       超えると 409。finally でコミット前に解放するため、排他の保証はしない。求人行の行ロック待ちは
     *       {@code innodb_lock_wait_timeout} に従い、そのタイムアウトやデッドロックは既定の handler で 500）</li>
     *   <li>求人行を {@code SELECT ... FOR UPDATE} で排他ロックする。同一求人の受諾・不採用はここで直列化される</li>
     *   <li>応募行を {@code PESSIMISTIC_WRITE} で読み直す（先頭の通常 SELECT の値はスナップショットで古い可能性がある）</li>
     *   <li>応募ステータス・求人ステータス・採用済み件数（{@code FOR SHARE} の locking read）を最新値で確認する</li>
     *   <li>応募 ACCEPTED 更新 → 契約 INSERT → チャット作成 → 求人 CLOSED（必要時）→ 通知イベント発行</li>
     * </ol>
     *
     * @param applicationId 採用する応募ID
     * @param requesterId   採用判断者のユーザーID（Requester or 管理者）
     * @return 生成された契約
     */
    @Transactional
    public JobContractEntity acceptApplication(Long applicationId, Long requesterId) {
        Objects.requireNonNull(applicationId, "applicationId は必須");
        Objects.requireNonNull(requesterId, "requesterId は必須");

        JobApplicationEntity app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_APPLICATION_NOT_FOUND));

        Long postingId = app.getJobPostingId();
        String lockKey = LOCK_KEY_PREFIX + postingId;

        // GET_LOCK で受諾の入口を絞る（10 秒で取れなければ競合中として状態遷移エラーで返す）。
        // コミット前に解放するので排他の保証にはならない。排他は下の求人行・応募行のロックが担う。
        acquireLockOrFail(lockKey);
        try {
            // 求人行の SELECT ... FOR UPDATE。同一求人の受諾・不採用を直列化する本当の排他（コミットまで保持）。
            JobPostingEntity posting = postingRepository.findByIdForUpdate(postingId)
                    .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_NOT_FOUND));

            // 採否権限チェック。
            if (!jobPolicy.canDecideApplication(requesterId, posting)) {
                throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
            }

            // 応募行を locking read で最新化してロックする。先頭の findById はスナップショット読みのため、
            // ロック待ちの間にコミットされた受諾・取り下げ・不採用が見えていない可能性がある。
            entityManager.refresh(app, LockModeType.PESSIMISTIC_WRITE);

            // 応募が処理前（APPLIED）であるか。
            if (app.getStatus() != JobApplicationStatus.APPLIED) {
                throw new BusinessException(JobmatchingErrorCode.JOB_APPLICATION_NOT_PENDING);
            }

            // 求人が OPEN であるか（CLOSED/CANCELLED 等は採用不可）。
            if (posting.getStatus() != JobPostingStatus.OPEN) {
                throw new BusinessException(JobmatchingErrorCode.JOB_NOT_OPEN);
            }

            // 定員未充足か。スナップショットでは並行受諾のコミットを数え漏らすため locking read で数える。
            long accepted = applicationRepository.countAcceptedByJobPostingIdForShare(postingId);
            if (accepted >= posting.getCapacity()) {
                throw new BusinessException(JobmatchingErrorCode.JOB_CAPACITY_FULL);
            }

            // 応募 ACCEPTED 更新（decided_at / decided_by_user_id を埋める）。
            // 応募側の状態遷移は Entity#accept() 内で完結しており、ここでは契約生成のみを中心に処理する。
            app.accept(requesterId);
            applicationRepository.save(app);

            // 契約 INSERT。
            JobContractEntity contract = JobContractEntity.builder()
                    .jobPostingId(postingId)
                    .jobApplicationId(applicationId)
                    .requesterUserId(posting.getCreatedByUserId())
                    .workerUserId(app.getApplicantUserId())
                    .baseRewardJpy(posting.getBaseRewardJpy())
                    .workStartAt(posting.getWorkStartAt())
                    .workEndAt(posting.getWorkEndAt())
                    .status(JobContractStatus.MATCHED)
                    .matchedAt(LocalDateTime.now())
                    .rejectionCount(0)
                    .build();
            contract = contractRepository.save(contract);

            // チャット自動作成。★目玉機能。
            Long chatRoomId = jobChatService.createRoomForContract(contract, posting);
            contract.assignChatRoom(chatRoomId);
            contract = contractRepository.save(contract);

            // 定員充足で求人を CLOSED に遷移。
            if (accepted + 1 >= posting.getCapacity()) {
                posting.close();
                postingRepository.save(posting);
                log.info("求人定員充足により CLOSED: postingId={}, capacity={}", postingId, posting.getCapacity());
            }

            // 通知はイベント発行のみ。実通知は AFTER_COMMIT のリスナーが行う（Issue #2997）。
            eventPublisher.publishEvent(JobNotificationEvent.matched(contract.getId(), postingId));

            log.info("採用確定・契約成立: contractId={}, applicationId={}, postingId={}, requesterId={}, workerId={}",
                    contract.getId(), applicationId, postingId, requesterId, contract.getWorkerUserId());
            return contract;
        } finally {
            releaseLockQuietly(lockKey);
        }
    }

    // ---------------------------------------------------------------------
    // 完了報告・承認・差し戻し
    // ---------------------------------------------------------------------

    /**
     * Worker が業務完了を報告する（MATCHED → COMPLETION_REPORTED）。
     */
    @Transactional
    public JobContractEntity reportCompletion(Long contractId, ReportCompletionCommand cmd, Long workerId) {
        Objects.requireNonNull(cmd, "cmd は必須");
        JobContractEntity contract = findContractOrThrow(contractId);
        if (!jobPolicy.canReportCompletion(workerId, contract)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }
        stateMachine.validate(contract.getStatus(), JobContractStatus.COMPLETION_REPORTED);
        contract.reportCompletion();
        JobContractEntity saved = contractRepository.save(contract);

        // Requester へ通知。イベント発行のみ。実通知は AFTER_COMMIT のリスナーが行う（Issue #2997）。
        eventPublisher.publishEvent(JobNotificationEvent.completionReported(
                saved.getId(), contract.getJobPostingId()));

        log.info("業務完了報告: contractId={}, workerId={}, comment={}",
                contractId, workerId, cmd.comment() != null ? "あり" : "なし");
        return saved;
    }

    /**
     * Requester が完了承認する（COMPLETION_REPORTED → COMPLETED）。
     */
    @Transactional
    public JobContractEntity approveCompletion(Long contractId, Long requesterId) {
        JobContractEntity contract = findContractOrThrow(contractId);
        if (!jobPolicy.canApproveCompletion(requesterId, contract)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }
        stateMachine.validate(contract.getStatus(), JobContractStatus.COMPLETED);
        contract.approveCompletion();
        JobContractEntity saved = contractRepository.save(contract);
        log.info("完了承認: contractId={}, requesterId={}", contractId, requesterId);
        return saved;
    }

    /**
     * Requester が差し戻しする（COMPLETION_REPORTED → MATCHED）。差し戻し回数を加算し、上限超過で拒否する。
     *
     * <p>Entity の {@link JobContractEntity#rejectCompletion(String)} は内部状態として IN_PROGRESS に
     * 遷移するが、MVP の状態遷移表では MATCHED 差し戻しを正とする。ここでは明示的に MATCHED に書き戻す。</p>
     */
    @Transactional
    public JobContractEntity rejectCompletion(Long contractId, String reason, Long requesterId) {
        JobContractEntity contract = findContractOrThrow(contractId);
        if (!jobPolicy.canApproveCompletion(requesterId, contract)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }
        stateMachine.validate(contract.getStatus(), JobContractStatus.MATCHED);

        // 差し戻し回数の上限チェック（現在値 + 1 が上限を超えるなら拒否）。
        if (contract.getRejectionCount() != null && contract.getRejectionCount() + 1 > MAX_REJECTION_COUNT) {
            throw new BusinessException(JobmatchingErrorCode.JOB_REJECTION_LIMIT_EXCEEDED);
        }

        // Entity 側ヘルパでカウントアップ・理由記録し、MVP の遷移として IN_PROGRESS ではなく MATCHED を正とする。
        // managed entity を直接ミューテートして save するため id を保持し、UPDATE として永続化される
        // （旧実装の toBuilder().build() は BaseEntity 継承の id を引き継がず INSERT 化する不具合があった）。
        contract.rejectCompletionToMatched(reason);
        JobContractEntity saved = contractRepository.save(contract);
        log.info("完了差し戻し: contractId={}, requesterId={}, rejectionCount={}, reason={}",
                contractId, requesterId, saved.getRejectionCount(), reason);
        return saved;
    }

    // ---------------------------------------------------------------------
    // キャンセル
    // ---------------------------------------------------------------------

    /**
     * 契約をキャンセルする。Requester または Worker 本人のみ許可。
     *
     * @param reason キャンセル理由（MVP では Entity に保存欄がないためログのみ記録）
     */
    @Transactional
    public JobContractEntity cancelContract(Long contractId, Long userId, String reason) {
        JobContractEntity contract = findContractOrThrow(contractId);
        if (!isParticipant(userId, contract)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }
        stateMachine.validate(contract.getStatus(), JobContractStatus.CANCELLED);
        contract.cancel();
        JobContractEntity saved = contractRepository.save(contract);
        log.info("契約キャンセル: contractId={}, userId={}, reason={}", contractId, userId, reason);
        return saved;
    }

    // ---------------------------------------------------------------------
    // クエリ系
    // ---------------------------------------------------------------------

    /**
     * 契約を ID で取得する。Requester または Worker 本人のみ閲覧可。
     */
    public JobContractEntity findById(Long contractId, Long userId) {
        JobContractEntity contract = findContractOrThrow(contractId);
        if (!isParticipant(userId, contract)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_CONTRACT_NOT_FOUND);
        }
        return contract;
    }

    /**
     * 自分が Worker または Requester として関与する契約一覧をページング取得する。
     */
    public Page<JobContractEntity> listMyContracts(Long userId, Pageable pageable) {
        return contractRepository.findByUserInvolvement(userId, pageable);
    }

    // ---------------------------------------------------------------------
    // 内部ヘルパー
    // ---------------------------------------------------------------------

    private JobContractEntity findContractOrThrow(Long contractId) {
        return contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_CONTRACT_NOT_FOUND));
    }

    /**
     * 契約の Requester または Worker 本人であるかを判定する。
     */
    private boolean isParticipant(Long userId, JobContractEntity contract) {
        return jobPolicy.isContractParticipant(userId, contract);
    }

    /**
     * MySQL {@code GET_LOCK} で受諾の入口ロックを取得する。失敗時は状態遷移エラーで返す。
     *
     * <p>呼び出し元の finally でコミット前に解放されるため、排他の保証ではなく流量制御として扱う。
     * 10 秒で打ち切られるのは GET_LOCK の待ちだけで、受諾どうしの待ちは概ね 10 秒で 409 になるが、ほかの操作
     * （不採用・取り下げ・応募）との待ちは求人行・応募行の行ロック待ちで {@code innodb_lock_wait_timeout} に従い、
     * そのタイムアウトやデッドロックは既定の handler で 500 になる。整合性は求人行・応募行の行ロックが守る。</p>
     *
     * <p>{@code SELECT GET_LOCK(key, timeout)} は 1 を返すとロック獲得、0 はタイムアウト、
     * NULL はエラー（例: 接続切断）。</p>
     */
    private void acquireLockOrFail(String lockKey) {
        Object result = entityManager.createNativeQuery("SELECT GET_LOCK(?1, ?2)")
                .setParameter(1, lockKey)
                .setParameter(2, LOCK_TIMEOUT_SECONDS)
                .getSingleResult();
        if (result == null || !"1".equals(result.toString())) {
            log.warn("GET_LOCK 獲得失敗: lockKey={}, result={}", lockKey, result);
            throw new BusinessException(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION);
        }
    }

    /**
     * MySQL {@code RELEASE_LOCK} でロック解放する。例外は呑み込み、ログ警告に留める
     * （トランザクション完了後のクリーンアップであり、主処理に影響を与えない）。
     */
    private void releaseLockQuietly(String lockKey) {
        try {
            entityManager.createNativeQuery("SELECT RELEASE_LOCK(?1)")
                    .setParameter(1, lockKey)
                    .getSingleResult();
        } catch (Exception e) {
            log.warn("RELEASE_LOCK 失敗（無視）: lockKey={}, error={}", lockKey, e.getMessage());
        }
    }
}
