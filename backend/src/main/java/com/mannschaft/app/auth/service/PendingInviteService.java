package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.dto.PendingInviteAcceptResponse;
import com.mannschaft.app.auth.dto.PendingInviteResponse;
import com.mannschaft.app.auth.dto.PendingInviteReissueResponse;

/**
 * 招待付き新規登録の保留承認（ログイン後に本人が承認して参加）の入口（CMP-261107-1833 設計 §2・§3）。
 * {@code @Transactional} を持たない。
 *
 * <p><b>試練スタブ</b>: 契約（メソッド署名）だけを置いた。振る舞いは出陣で実装する。</p>
 */
public class PendingInviteService {

    /** 本人の保留を返す。無ければ null。 */
    public PendingInviteResponse findMine(Long userId) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    public PendingInviteAcceptResponse accept(Long userId) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    public void decline(Long userId) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    public PendingInviteReissueResponse requestReissue(Long userId) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    public void dismiss(Long userId) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }
}
