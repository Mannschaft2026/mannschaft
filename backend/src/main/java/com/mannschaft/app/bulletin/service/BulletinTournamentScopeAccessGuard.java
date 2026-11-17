package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.service.TournamentContactAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 大会/ディビジョン連絡スペース（F08.7.1）の掲示板一覧の閲覧認可門。
 *
 * <p>閲覧認可（canView）は他ドメイン（tournament / membership / role）の Repository へ到達する。
 * {@link BulletinThreadService} は {@code @Transactional} のため、その内側で判定すると取引が越境する
 * （D-3T 番人、CLAUDE.md DB 設計の原則 #5）。本部品は {@code @Transactional} を持たず、
 * Controller がサービス呼び出しの前に呼ぶ。大会スコープ以外は何もしない（所属認可はサービス側）。</p>
 */
@Component
@RequiredArgsConstructor
public class BulletinTournamentScopeAccessGuard {

    private final TournamentContactAccessService tournamentContactAccessService;

    /**
     * 大会/ディビジョンスコープなら連絡閲覧認可を行う。それ以外のスコープは何もしない。
     *
     * @throws com.mannschaft.app.common.BusinessException 閲覧不可（TOUR_030 / TOUR_031 等）
     */
    public void checkListView(ScopeType scopeType, Long scopeId, Long userId) {
        if (!isTournamentScope(scopeType)) {
            return;
        }
        ContactSpaceScopeType contactScope = scopeType == ScopeType.TOURNAMENT
                ? ContactSpaceScopeType.TOURNAMENT
                : ContactSpaceScopeType.TOURNAMENT_DIVISION;
        tournamentContactAccessService.checkView(contactScope, scopeId, ContactSpaceKind.BULLETIN, userId);
    }

    /** bulletin の scope_type が大会/ディビジョン連絡スペースか。 */
    public static boolean isTournamentScope(ScopeType scopeType) {
        return scopeType == ScopeType.TOURNAMENT || scopeType == ScopeType.TOURNAMENT_DIVISION;
    }
}
