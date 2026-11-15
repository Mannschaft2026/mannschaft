package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.effortrecord.dto.EffortStatsCriteria;
import com.mannschaft.app.effortrecord.dto.EffortStatsResponse;
import org.springframework.stereotype.Service;

/**
 * F06.4.1 個人集計 Service（骨格）。グラフ許可表（API 契約 §5.1）に従い BAR/LINE/PIE を集計する。
 *
 * <p>骨格段階のため未実装。</p>
 */
@Service
public class EffortStatsService {

    /** 本人の記録を条件どおり集計する。chart と semantic の不適合・期間上限超過は COMMON_001（400）。 */
    public EffortStatsResponse aggregate(Long ownerUserId, EffortStatsCriteria criteria) {
        throw new UnsupportedOperationException("F06.4.1 未実装");
    }
}
