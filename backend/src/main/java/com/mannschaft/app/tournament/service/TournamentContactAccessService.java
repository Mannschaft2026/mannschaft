package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 大会・ディビジョン連絡スペースの認可サービス（F08.7.1 連絡機能 §4）。
 *
 * <p>村の二段認可（{@link com.mannschaft.app.village.service.VillageBulletinAccessService}）を範とし、
 * read（{@link #checkView}）/ write（{@link #checkPost}）を分離する。掲示板・チャット双方の
 * Service 入口で必ず通す（多層防御）。</p>
 *
 * <h2>認可ルール</h2>
 * <ul>
 *   <li>閲覧（{@code checkView}）: 公開スペース（{@code is_public=true}）なら未ログイン含め全員 ／
 *       参加チーム（status∈{REGISTERED,ACTIVE}）のアクティブメンバー ／ 主催組織 ADMIN ／ SYSTEM_ADMIN。</li>
 *   <li>投稿（{@code checkPost}）: 参加チームの ADMIN/DEPUTY_ADMIN ／ 主催組織 ADMIN ／ SYSTEM_ADMIN。
 *       一般 MEMBER / SUPPORTER / PUBLIC は閲覧のみ（権限昇格防止）。</li>
 *   <li>公開設定（{@code checkVisibilityManage}）: 主催組織 ADMIN ／ SYSTEM_ADMIN のみ（チーム代表は不可）。</li>
 * </ul>
 *
 * <p>存在しない／論理削除済みスペース・大会・ディビジョンは一律 404（IDOR 対策・存在を漏らさない）。
 * クロスドメインの所属判定は {@code AccessControlService} と {@code TournamentParticipantRepository} の
 * exists クエリ（ID 参照のみ・原則1）で行い、N+1 を回避する（§4.3）。読み取り専用ゆえ
 * {@code @Transactional(readOnly=true)} に閉じる（§4.4）。</p>
 *
 * <p>CMP-261116-0726: 閲覧判定の正本は {@link TournamentContactSpaceAccessGate}（tx なし）に移した。
 * 閲覧権なしは不在と同一の 404 に畳む（旧 403 {@code TOUR_030} は欠番）。他ドメインの入口は Controller／
 * tx なし Facade から同ゲートを前段で呼び、本サービスの {@code checkPost} は tx 内の多層防御として残す。
 * ArchUnit D-3T の凍結キーを増やさないため、本サービスへのメソッド追加・シグネチャ変更は禁止。</p>
 *
 * <p>設計書: docs/features/F08.7.1_tournament_extensions/01_communication.md §4</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentContactAccessService {

    private final TournamentRepository tournamentRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final TournamentParticipantRepository participantRepository;
    private final AccessControlService accessControlService;
    private final TournamentContactSpaceAccessGate accessGate;

    /**
     * 連絡スペースの閲覧認可を検証する（設計書 §4.1）。認可違反時は例外を投げる。
     *
     * <p>判定は {@link TournamentContactSpaceAccessGate#requireView} に委譲する（CMP-261116-0726）。
     * 閲覧権なし・スペース不在・大会／ディビジョン不在はすべて不在と同一の 404
     * {@link TournamentErrorCode#CONTACT_SPACE_NOT_FOUND} に畳み、存在オラクルを塞ぐ
     * （旧 {@code TOUR_030} は欠番・不送出）。</p>
     *
     * @param scopeType スコープ種別（TOURNAMENT / TOURNAMENT_DIVISION）
     * @param scopeId   大会 ID（TOURNAMENT）またはディビジョン ID（TOURNAMENT_DIVISION）
     * @param spaceKind スペース種別（BULLETIN / CHAT）
     * @param userId    閲覧しようとするユーザー ID（未ログインは null）
     * @throws BusinessException 閲覧権なし・不在（{@link TournamentErrorCode#CONTACT_SPACE_NOT_FOUND}・404）
     */
    public void checkView(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind spaceKind, Long userId) {
        accessGate.requireView(scopeType, scopeId, spaceKind, userId, TournamentErrorCode.CONTACT_SPACE_NOT_FOUND);
    }

    /**
     * 連絡スペースへの投稿認可を検証する（設計書 §4.2）。認可違反時は例外を投げる。
     *
     * <p>投稿できるのは各参加チームの代表（ADMIN）・副代表（DEPUTY_ADMIN）と主催組織 ADMIN ／ SYSTEM_ADMIN のみ。
     * 一般 MEMBER / SUPPORTER / PUBLIC は閲覧のみ（権限昇格防止）。</p>
     *
     * <p><b>tx 内多層防御専用</b>（CMP-261116-0726）: 本メソッドはスペースの存在確認も存在秘匿も行わない。
     * 存在確認と秘匿（閲覧権なし→不在応答、閲覧可・投稿権なし→403）は呼び出し側の前段ゲート
     * （{@link TournamentContactSpaceAccessGate#requirePost} 等）が担う。ArchUnit D-3T の凍結キーを増やさないため、
     * 本メソッドの中身から {@code TournamentContactSpaceRepository} に触れてはならない。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   大会 ID またはディビジョン ID
     * @param userId    投稿しようとするユーザー ID
     * @throws BusinessException 投稿権限なし（{@link TournamentErrorCode#CONTACT_SPACE_POST_FORBIDDEN}・403）／
     *                           スコープ不在（{@link TournamentErrorCode#CONTACT_SPACE_NOT_FOUND}・404）
     */
    public void checkPost(ContactSpaceScopeType scopeType, Long scopeId, Long userId) {
        if (userId == null) {
            throw new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        }

        // 参加チームの ADMIN/DEPUTY_ADMIN なら許可（N+1 回避 exists クエリ）
        boolean isTeamAdmin = scopeType == ContactSpaceScopeType.TOURNAMENT
                ? participantRepository.existsTeamAdminOfAnyParticipantTeam(scopeId, userId)
                : participantRepository.existsTeamAdminOfDivisionParticipantTeam(scopeId, userId);
        if (isTeamAdmin) {
            return;
        }

        // 主催組織 ADMIN / SYSTEM_ADMIN なら許可
        if (isOrganizerAdminOrSystemAdmin(scopeType, scopeId, userId)) {
            return;
        }

        throw new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
    }

    /**
     * 連絡スペースの公開設定変更認可を検証する（設計書 §5・主催組織 ADMIN / SYSTEM_ADMIN 限定）。
     *
     * <p>{@code checkPost} より厳しく、チーム代表は公開設定を変更できない。</p>
     *
     * <p>tx 内多層防御（CMP-261116-0726）: 存在秘匿（知り得ない者→不在と同一の 404、パスの大会 ID と
     * ディビジョンの帰属検証）は Controller 前段の
     * {@link TournamentContactSpaceAccessGate#requireVisibilityManage} が担う。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   大会 ID またはディビジョン ID
     * @param userId    操作ユーザー ID
     * @throws BusinessException 権限なし（{@link TournamentErrorCode#CONTACT_SPACE_VISIBILITY_FORBIDDEN}・403）／
     *                           スコープ不在（{@link TournamentErrorCode#CONTACT_SPACE_NOT_FOUND}・404）
     */
    public void checkVisibilityManage(ContactSpaceScopeType scopeType, Long scopeId, Long userId) {
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        Long organizationId = resolveOrganizationId(scopeType, scopeId);
        if (userId != null && accessControlService.isAdmin(userId, organizationId, "ORGANIZATION")) {
            return;
        }
        throw new BusinessException(TournamentErrorCode.CONTACT_SPACE_VISIBILITY_FORBIDDEN);
    }

    // ========================================================================
    // 内部ヘルパー
    // ========================================================================

    private boolean isOrganizerAdminOrSystemAdmin(ContactSpaceScopeType scopeType, Long scopeId, Long userId) {
        Long organizationId = resolveOrganizationId(scopeType, scopeId);
        if (accessControlService.isAdmin(userId, organizationId, "ORGANIZATION")) {
            return true;
        }
        return accessControlService.isSystemAdmin(userId);
    }

    /**
     * スコープから主催組織 ID を解決する。大会／ディビジョンが存在しない場合は 404。
     */
    private Long resolveOrganizationId(ContactSpaceScopeType scopeType, Long scopeId) {
        if (scopeType == ContactSpaceScopeType.TOURNAMENT) {
            TournamentEntity tournament = tournamentRepository.findById(scopeId)
                    .orElseThrow(() -> new BusinessException(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));
            return tournament.getOrganizationId();
        }
        TournamentDivisionEntity division = divisionRepository.findById(scopeId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));
        TournamentEntity tournament = tournamentRepository.findById(division.getTournamentId())
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));
        return tournament.getOrganizationId();
    }
}
