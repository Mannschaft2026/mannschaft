package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.timeline.repository.TimelineEffortSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * F06.4.1 努力由来の投稿への通常操作を拒否する共通ガード（データ設計 §7.2・API 契約 §6 の EFFORT_003）。
 *
 * <p>判定は「投稿が対応表 {@code timeline_effort_sources} に載っているか」だけで行う（取消済みの行も含む。
 * F00 の可否は見ない）。like / mitayo / reply / quote / repost / 通常の本文更新 / deletePost / pin / bookmark /
 * フレンドチーム転送 / 公開化は、操作を問わず 403 {@code EFFORT_003}（fieldErrors なし）に揃える。</p>
 *
 * <p><b>存在オラクルを作らない</b>: 呼出元は、既存の可視性判定（{@link TimelinePostVisibilityAccessGuard}）で
 * 投稿が見えることを確かめてから本ガードを呼ぶ。見えない者には既存の「見えない投稿」と同じ応答を返し、
 * 努力由来かどうかを判別させない。参照するのは timeline 自身の表だけで、別ドメインの Repository へは到達しない。</p>
 */
@Component
@RequiredArgsConstructor
public class TimelineEffortOperationGuard {

    private final TimelineEffortSourceRepository sources;

    /**
     * 投稿が努力由来なら 403 EFFORT_003 で拒否する。
     *
     * @param postId 投稿 ID（null は何もしない）
     */
    public void rejectIfEffortSource(Long postId) {
        if (postId != null && sources.existsByTimelinePostId(postId)) {
            throw new BusinessException(EffortErrorCode.EFFORT_003);
        }
    }
}
