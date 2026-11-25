package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.effortrecord.dto.EffortSharedRecord;
import com.mannschaft.app.effortrecord.entity.EffortTeamShareEntity;
import com.mannschaft.app.effortrecord.repository.EffortTeamShareQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F06.4.1 タイムラインの努力由来投稿へ載せる SharedRecord の read hydrator（データ設計 §7。非 TX）。
 *
 * <p>timeline の read TX を終えた呼出元（{@code TimelineEffortReadGuard}）から share UUID の集合を受け取り、team 行を
 * 1 回で読み、同じ行に対して {@link EffortTeamShareVisibility}（F00 resolver と同じ判定）を掛けてから、通った行だけを
 * {@link EffortSharedRecordAssembler} で組み立てる。判定と組み立てを同じ読み込み結果で行うので、判定のあとに
 * 書き換わった projection（owner の ack 前の新しい版など）を返さない。照会は 1 ページにつき定数回（件数に比例しない）で、
 * 値をキャッシュへ固定保存しない。照会の障害は 503 EFFORT_004（空や「見えない」に置き換えない）。</p>
 */
@Component
@RequiredArgsConstructor
public class EffortTimelineShareHydrator {

    private final EffortTeamShareQueryRepository queries;
    private final EffortTeamShareVisibility visibility;
    private final EffortSharedRecordAssembler assembler;

    /**
     * 閲覧者に見える共有だけを SharedRecord にする。
     *
     * @return share UUID → SharedRecord（見えない共有は含めない）
     */
    public Map<UUID, EffortSharedRecord> hydrate(Collection<UUID> shareIds, Long viewerUserId) {
        Map<UUID, EffortSharedRecord> result = new HashMap<>();
        if (shareIds == null || shareIds.isEmpty() || viewerUserId == null) {
            return result;
        }
        List<EffortTeamShareEntity> rows;
        try {
            rows = queries.findByIds(new LinkedHashSet<>(shareIds));
        } catch (DataAccessException e) {
            throw new BusinessException(EffortErrorCode.EFFORT_004);
        }
        EffortTeamShareVisibility.Result allowed = visibility.filter(rows, viewerUserId, null);
        List<EffortTeamShareEntity> visible = rows.stream().filter(r -> allowed.allowed().contains(r.getId())).toList();
        if (visible.isEmpty()) {
            return result;
        }
        assembler.fromTeamRows(visible, allowed.purgedOwner()).forEach(record -> result.put(record.id(), record));
        return result;
    }
}
