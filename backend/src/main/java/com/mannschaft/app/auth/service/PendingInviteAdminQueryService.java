package com.mannschaft.app.auth.service;

import com.mannschaft.app.common.PendingAggregate;
import org.springframework.stereotype.Service;

/**
 * 再招待依頼（registration_pending_invites の REISSUE_REQUESTED）の管理者向け集計（CMP-261107-1833 PR3 設計 b・f・i）。
 *
 * <p>管理者向け承認待ち集約（INVITE_REISSUE）に件数とプレビューを返す。件数の述語は再招待依頼の一覧と共有する
 * （status=REISSUE_REQUESTED・スコープ一致・依頼者 ACTIVE・論理削除なし・閉鎖中のスコープは 0 件）。
 * {@code previewSize=0} なら件数だけを返し、表示名を引く SQL を出さない。{@code @Transactional} を持たない。</p>
 *
 * <p><b>試練スタブ</b>: 形だけを置いた。出陣で実装する。</p>
 */
@Service
public class PendingInviteAdminQueryService {

    /**
     * 指定スコープの再招待依頼の件数とプレビュー（古い順）。
     *
     * @param scopeType   TEAM / ORGANIZATION
     * @param scopeId     スコープ ID
     * @param scopeSlug   スコープ slug（遷移先の組み立てに使う）
     * @param previewSize プレビュー件数（0 なら件数だけ）
     */
    public PendingAggregate reissueRequestsFor(String scopeType, Long scopeId, String scopeSlug, int previewSize) {
        throw new UnsupportedOperationException("未実装: PendingInviteAdminQueryService#reissueRequestsFor（CMP-261107-1833 PR3）");
    }
}
