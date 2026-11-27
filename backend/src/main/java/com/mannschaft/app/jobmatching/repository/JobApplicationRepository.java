package com.mannschaft.app.jobmatching.repository;

import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 求人応募リポジトリ。
 */
public interface JobApplicationRepository extends JpaRepository<JobApplicationEntity, Long> {

    /**
     * 求人への応募一覧を応募日時降順で取得する（Requester の応募者一覧画面用）。
     */
    List<JobApplicationEntity> findByJobPostingIdOrderByAppliedAtDesc(Long jobPostingId);

    /**
     * ユーザーの応募履歴を新しい順で取得する（Worker のマイ応募一覧用）。
     */
    List<JobApplicationEntity> findByApplicantUserIdOrderByAppliedAtDesc(Long userId);

    /**
     * ユーザーの応募履歴をページング取得する（マイ応募一覧 API 用）。
     */
    Page<JobApplicationEntity> findByApplicantUserId(Long userId, Pageable pageable);

    /**
     * 特定ユーザーの特定求人への応募を取得する（重複応募チェック用）。
     */
    Optional<JobApplicationEntity> findByJobPostingIdAndApplicantUserId(Long jobPostingId, Long userId);

    /**
     * 求人に対する応募総数（論理削除なし設計のため単純カウント）。
     * update() 時の「応募者がいれば報酬・日時変更不可」判定に利用する。
     */
    int countByJobPostingId(Long jobPostingId);

    /**
     * 求人の特定ステータスの応募件数を取得する（採用済み人数カウント等）。
     */
    int countByJobPostingIdAndStatus(Long jobPostingId, JobApplicationStatus status);

    /**
     * 求人の採用済み（ACCEPTED）応募件数を locking read（{@code FOR SHARE}）で数える（採用確定の定員判定用）。
     *
     * <p>REPEATABLE READ の通常 SELECT は TX 内で最初に確定したスナップショットを読むため、求人行のロック待ちの間に
     * コミットされた並行受諾を数え漏らす。locking read は最新のコミット済み行を読み、数えた範囲に共有ロックを
     * 掛けてコミットまで保持する。求人行を {@code FOR UPDATE} でロックした TX の中で呼ぶこと（CMP-261021-0355）。</p>
     */
    @Query(value = "SELECT COUNT(*) FROM job_applications WHERE job_posting_id = :jobPostingId "
            + "AND status = 'ACCEPTED' FOR SHARE", nativeQuery = true)
    long countAcceptedByJobPostingIdForShare(@Param("jobPostingId") Long jobPostingId);
}
