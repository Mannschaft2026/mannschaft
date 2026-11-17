package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.dto.ShiftBudgetMonthlyPostCommand;
import com.mannschaft.app.budget.dto.ShiftBudgetMonthlyPostResult;
import org.springframework.stereotype.Service;

/**
 * 3段締めの段2 Post（budget TX。CMP-260909-2152 確定設計 r12 §3）。
 *
 * <p>年度行 FOR UPDATE → 投稿判定行 FOR UPDATE。判定行があれば年度状態に関係なく保存済み判定を返す。
 * 無ければ年度 OPEN で仕訳＋判定 POSTED、CLOSED で判定 ABORTED(FY_CLOSED)、同割当同仕訳日の生存仕訳があれば
 * 判定 ABORTED(JOURNAL_CONFLICT)。</p>
 *
 * <p>試練段階の骨組み。中身は出陣で実装する。</p>
 */
@Service
public class ShiftBudgetMonthlyJournalService {

    /** 段2 Post。 */
    public ShiftBudgetMonthlyPostResult post(ShiftBudgetMonthlyPostCommand command) {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }
}
