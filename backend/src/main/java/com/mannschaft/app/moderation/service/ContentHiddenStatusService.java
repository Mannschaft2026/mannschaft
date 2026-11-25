package com.mannschaft.app.moderation.service;

import com.mannschaft.app.moderation.ReportTargetType;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 通報・モデレーションによるコンテンツ非表示（{@code content_reports.content_hidden}）の読み取り専用照会。
 *
 * <p>F06.4.1 の F00（努力由来のタイムライン投稿の共有）が、非表示の確定通知（AFTER_COMMIT）の欠落に依らず
 * 非表示を判定に含めるために読む（データ設計 §7.2）。呼出元は非 TX で、照会は moderation の読み取り TX で閉じる。
 * 依存は通報 Repository だけに限り、可視性判定の側から通報 Service 群（作成・レビュー）へ依存を広げない。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentHiddenStatusService {

    private final ContentReportRepository reportRepository;

    /**
     * タイムライン投稿 ID の集合のうち、通報・モデレーションで非表示にされているもの（1 回の照会）。
     *
     * @param postIds タイムライン投稿 ID
     * @return 非表示にされている投稿 ID
     */
    public Set<Long> findHiddenTimelinePostIds(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(reportRepository.findHiddenTargetIds(ReportTargetType.TIMELINE_POST, postIds));
    }
}
