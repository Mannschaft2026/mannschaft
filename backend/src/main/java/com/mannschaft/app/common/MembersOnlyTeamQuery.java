package com.mannschaft.app.common;

import java.util.Collection;
import java.util.Set;

/**
 * 非公開チーム（{@code teams.visibility = MEMBERS_AND_ABOVE}）の判定を問い合わせる共通契約
 * （CMP-261124-0159・F00 §20.2 規則 5 / §20.3）。
 *
 * <p>チームの公開範囲は team ドメインが持つ。可視性基盤（{@code common.visibility}）と認可の共通の門
 * （{@link AccessControlService}）は本契約だけを通して読み、{@code teams} 表を直接参照しない。</p>
 *
 * <p>実装は 1 回の呼び出しにつき {@code teams} の主キー IN の問い合わせを 1 本だけ発行し、
 * リクエストをまたぐキャッシュを持たない（公開範囲の変更は次の呼び出しから即時に効く。AC-0159-06）。</p>
 */
public interface MembersOnlyTeamQuery {

    /**
     * 指定したチーム ID のうち、非公開チーム（メンバー限定）のものだけを返す。
     *
     * <p>論理削除済みのチームも公開範囲どおりに判定する（削除済みでも中身を開かない側へ倒す）。
     * 空・null の入力では問い合わせを発行せず空集合を返す。</p>
     *
     * @param teamIds 判定対象のチーム ID（null 要素は無視）
     * @return 非公開チームの ID 集合（変更不可）
     */
    Set<Long> findMembersOnlyTeamIds(Collection<Long> teamIds);
}
