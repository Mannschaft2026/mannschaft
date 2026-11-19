package com.mannschaft.app.shift.service;

import java.time.LocalDate;

/**
 * 公開の予算連動判定に使う値を、公開 TX の外で取得する口（CMP-261115-1821）。
 *
 * <p>実装は予算を所有する shiftbudget ドメインに置く（依存の向きは shiftbudget → shift のまま）。
 * 呼び出し元は非 tx の {@link ShiftScheduleFacade} だけで、認可の後に呼ぶ。実装は {@code @Transactional} を
 * 付けず、公開 TX へ参加しない。</p>
 */
public interface ShiftPublicationBudgetContextPort {

    /**
     * チームの閉区間 {@code [from, to]} に重なる予算割当候補と、候補組織の予算機能判定を取得して固定する。
     *
     * @param teamId 公開対象スケジュールのチームID
     * @param from   枠日付の最小値
     * @param to     枠日付の最大値
     * @return coveredFrom/coveredTo に {@code from}/{@code to} を持つ不変の context
     */
    ShiftPublicationBudgetContext load(Long teamId, LocalDate from, LocalDate to);
}
