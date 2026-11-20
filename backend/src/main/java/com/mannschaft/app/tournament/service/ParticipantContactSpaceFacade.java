package com.mannschaft.app.tournament.service;

import com.mannschaft.app.tournament.dto.ParticipantContactSpacesResponse;
import org.springframework.stereotype.Component;

/**
 * 参加者向け連絡スペース一覧のファサード（CMP-261118-0900 §10.3.4）。
 *
 * <p>意図して {@code @Transactional} を持たない（判定は tx の外で行い、拒否は同一の TOUR_029 を 1 箇所で投げる）。</p>
 */
@Component
public class ParticipantContactSpaceFacade {

    /**
     * 参加者向けに、自チームの文脈で見られる連絡スペースを解決する。
     *
     * @param teamSlug     チーム slug
     * @param tournamentId 大会 ID
     * @param userId       ユーザー ID（認証必須）
     */
    public ParticipantContactSpacesResponse listForParticipant(String teamSlug, Long tournamentId, Long userId) {
        throw new UnsupportedOperationException("CMP-261118-0900 未実装");
    }
}
