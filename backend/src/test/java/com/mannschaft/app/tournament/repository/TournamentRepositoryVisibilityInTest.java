package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.tournament.ParticipantStatus;
import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.TournamentVisibility;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-028 Phase C — {@link TournamentRepository#findVisibleByOrganizationId} 結合テスト。
 *
 * <p>受け入れ条件 AC-C2（歯抜けゼロ・CUSTOM 混在でも）・AC-C3（総件数正確）・
 * AC-C4（PARTICIPANTS_ONLY の許可側/拒否側）・AC-C7（fail-closed 維持）を実 MySQL で検証する。</p>
 *
 * <p>フィクスチャは「可視行の間に不可視行・CUSTOM(PARTICIPANTS_ONLY) 行を混ぜる」方式
 * （{@code ActivityResultRepositoryVisibilityInTest} と同じ流儀。全件可視のデータでは
 * 歯抜けを検出できないという AC-25 の教訓に基づく）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("TournamentRepository.findVisibleByOrganizationId — 結合テスト")
class TournamentRepositoryVisibilityInTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private TournamentRepository tournamentRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long OTHER_AUTHOR_ID = 1L;
    private static final Long VIEWER_ID = 999L;
    private static final Long PARTICIPANT_TEAM_ID = 555L;
    private static final Long NON_PARTICIPANT_TEAM_ID = 556L;

    private Long orgId;

    @BeforeEach
    void setUp() {
        orgId = insertOrganization("大会結合 組織");

        // AC-C2 の赤を検出できるフィクスチャ: 可視(PUBLIC, OPEN) 20件の間に
        // 不可視(ADMINS_AND_ABOVE) を10件、CUSTOM(PARTICIPANTS_ONLY) を5件（非参加者には不可視）挟み込む。
        for (int i = 0; i < 20; i++) {
            persistTournament(TournamentVisibility.PUBLIC, TournamentStatus.OPEN, OTHER_AUTHOR_ID);
            if (i < 10) {
                persistTournament(TournamentVisibility.ADMINS_AND_ABOVE, TournamentStatus.OPEN, OTHER_AUTHOR_ID);
            }
            if (i < 5) {
                persistTournament(TournamentVisibility.PARTICIPANTS_ONLY, TournamentStatus.OPEN, OTHER_AUTHOR_ID);
            }
        }
        em.flush();
        em.clear();
    }

    /**
     * AC-C2: viewer が PUBLIC のみ可視でも、20件の PUBLIC 行が存在する以上 size=20 で必ず 20 件返る。
     * 間に不可視行・CUSTOM(PARTICIPANTS_ONLY・非参加者には不可視) 行が混ざっても歯抜けは起きない。
     */
    @Test
    @DisplayName("AC-C2: 可視行20件+不可視/CUSTOM行混在でも size=20 で必ず20件返る（歯抜けゼロ）")
    void 歯抜けゼロ() {
        Page<TournamentEntity> page = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), VIEWER_ID, false, false, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).allMatch(t -> t.getVisibility() == TournamentVisibility.PUBLIC);
    }

    /**
     * AC-C3: 総件数は実際の可視件数（PUBLIC×OPEN = 20件）と一致する。
     */
    @Test
    @DisplayName("AC-C3: 総件数は実可視件数と一致する（上界近似ではない）")
    void 総件数は実可視件数と一致() {
        Page<TournamentEntity> page = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), VIEWER_ID, false, false, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(20L);
    }

    /**
     * AC-C4（拒否側）: 参加チームに所属しない viewer には PARTICIPANTS_ONLY 大会も
     * ADMINS_AND_ABOVE 大会も一切見えない。
     */
    @Test
    @DisplayName("AC-C4（拒否側）: 非参加者にPARTICIPANTS_ONLY大会は見えない")
    void 非参加者には見えない() {
        Page<TournamentEntity> page = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), VIEWER_ID, false, false, PageRequest.of(0, 100));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).noneMatch(t -> t.getVisibility() == TournamentVisibility.PARTICIPANTS_ONLY);
        assertThat(page.getContent()).noneMatch(t -> t.getVisibility() == TournamentVisibility.ADMINS_AND_ABOVE);
    }

    /**
     * AC-C4（許可側）: 参加チームのアクティブメンバーには PARTICIPANTS_ONLY 大会が見える
     * （拒否側だけだと「全部不可視になる」実装ミスに気付けないという申し送りに基づく）。
     */
    @Test
    @DisplayName("AC-C4（許可側）: 参加チームのアクティブメンバーにはPARTICIPANTS_ONLY大会が見える")
    void 参加者には見える() {
        Long tid = persistTournament(TournamentVisibility.PARTICIPANTS_ONLY, TournamentStatus.OPEN, OTHER_AUTHOR_ID);
        Long divId = persistDivision(tid);
        persistParticipant(divId, PARTICIPANT_TEAM_ID, ParticipantStatus.ACTIVE);
        persistMembership(VIEWER_ID, PARTICIPANT_TEAM_ID);
        em.flush();
        em.clear();

        Page<TournamentEntity> page = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), VIEWER_ID, false, false, PageRequest.of(0, 100));

        assertThat(page.getContent()).extracting(TournamentEntity::getId).contains(tid);
    }

    /**
     * AC-C4: 参加チームではないチームのメンバーには見えない（束縛検証）。
     */
    @Test
    @DisplayName("AC-C4: 別チームのメンバーにはPARTICIPANTS_ONLY大会が見えない")
    void 別チームのメンバーには見えない() {
        Long tid = persistTournament(TournamentVisibility.PARTICIPANTS_ONLY, TournamentStatus.OPEN, OTHER_AUTHOR_ID);
        Long divId = persistDivision(tid);
        persistParticipant(divId, PARTICIPANT_TEAM_ID, ParticipantStatus.ACTIVE);
        // viewer は参加チームではない別チームのメンバー
        persistMembership(VIEWER_ID, NON_PARTICIPANT_TEAM_ID);
        em.flush();
        em.clear();

        Page<TournamentEntity> page = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), VIEWER_ID, false, false, PageRequest.of(0, 100));

        assertThat(page.getContent()).extracting(TournamentEntity::getId).doesNotContain(tid);
    }

    /**
     * AC-C7（拒否側）: 未認証（viewerUserId=null）には PUBLIC×OPEN のみ見え、
     * PARTICIPANTS_ONLY・ADMINS_AND_ABOVE は一切漏れない（fail-closed）。
     */
    @Test
    @DisplayName("AC-C7: 未認証にはPUBLICのみ可視・機微な行は漏れない")
    void 未認証はPUBLICのみ() {
        Page<TournamentEntity> page = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), null, false, false, PageRequest.of(0, 100));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).allMatch(t -> t.getVisibility() == TournamentVisibility.PUBLIC);
    }

    /**
     * AC-C6 相当: ADMINS_AND_ABOVE までラダーに含む viewer には ADMINS_AND_ABOVE 行も含まれる
     * （許可側を書かないと「全部不可視になる」実装ミスに気付けない）。
     */
    @Test
    @DisplayName("ADMINS_AND_ABOVE まで可視な閲覧者にはADMINS_AND_ABOVE大会が含まれる")
    void ADMINSまで可視なら含まれる() {
        Page<TournamentEntity> page = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC", "ADMINS_AND_ABOVE"), VIEWER_ID, false, false, PageRequest.of(0, 100));

        assertThat(page.getTotalElements()).isEqualTo(30L); // 20 PUBLIC + 10 ADMINS_AND_ABOVE
        assertThat(page.getContent()).anyMatch(t -> t.getVisibility() == TournamentVisibility.ADMINS_AND_ABOVE);
    }

    /**
     * DRAFT は作成者本人のみ可視（visibility 無関係）。
     */
    @Test
    @DisplayName("DRAFTは作成者本人のみ可視")
    void DRAFTは作成者本人のみ() {
        Long myDraftId = persistTournament(TournamentVisibility.PUBLIC, TournamentStatus.DRAFT, VIEWER_ID);
        em.flush();
        em.clear();

        Page<TournamentEntity> page = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), VIEWER_ID, false, false, PageRequest.of(0, 100));

        assertThat(page.getContent()).extracting(TournamentEntity::getId).contains(myDraftId);
        assertThat(page.getContent()).noneMatch(t -> t.getStatus() == TournamentStatus.DRAFT
                && !VIEWER_ID.equals(t.getCreatedBy()));
    }

    /**
     * 戦役 Z-A PR4: SystemAdmin 単独（所属なし・DEPUTY_ADMIN 未満）は CANCELLED 大会を見られない。
     * （引数撤去前は SystemAdmin フラグで見えていた。期待値を反転）
     */
    @Test
    @DisplayName("SystemAdmin単独にはCANCELLED大会は見えない")
    void SystemAdmin単独には見えない() {
        Long cancelledId = persistTournament(TournamentVisibility.PUBLIC, TournamentStatus.CANCELLED, OTHER_AUTHOR_ID);
        em.flush();
        em.clear();

        Page<TournamentEntity> page = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), VIEWER_ID, false, false, PageRequest.of(0, 100));

        assertThat(page.getContent()).extracting(TournamentEntity::getId).doesNotContain(cancelledId);
    }

    /**
     * 戦役 Z-A PR4（マスター決定 2026-11-27）: CANCELLED/ARCHIVED は著者本人（組織に今も所属）だけが見られる。
     * 所属を離れた著者には見えない。
     */
    @Test
    @DisplayName("CANCELLED大会は著者本人（所属中）には見え、所属を離れた著者には見えない")
    void CANCELLEDは所属中の著者だけ() {
        Long mine = persistTournament(TournamentVisibility.PUBLIC, TournamentStatus.CANCELLED, VIEWER_ID);
        em.flush();
        em.clear();

        Page<TournamentEntity> member = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), VIEWER_ID, true, false, PageRequest.of(0, 100));
        Page<TournamentEntity> departed = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), VIEWER_ID, false, false, PageRequest.of(0, 100));

        assertThat(member.getContent()).extracting(TournamentEntity::getId).contains(mine);
        assertThat(departed.getContent()).extracting(TournamentEntity::getId).doesNotContain(mine);
    }

    /**
     * 戦役 Z-A PR4: CANCELLED/ARCHIVED は組織の DEPUTY_ADMIN 以上なら他人の分も見られる。
     */
    @Test
    @DisplayName("CANCELLED大会は組織のDEPUTY_ADMIN以上には他人の分も見える")
    void CANCELLEDは管理者に見える() {
        Long other = persistTournament(TournamentVisibility.PUBLIC, TournamentStatus.CANCELLED, OTHER_AUTHOR_ID);
        em.flush();
        em.clear();

        Page<TournamentEntity> page = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), VIEWER_ID, true, true, PageRequest.of(0, 100));

        assertThat(page.getContent()).extracting(TournamentEntity::getId).contains(other);
    }

    /**
     * 戦役 Z-A PR4（マスター決定 2026-11-27「公開範囲も守る」）: CANCELLED/ARCHIVED は著者・DEPUTY_ADMIN 以上の
     * 条件に加え、その大会の公開範囲（visibility）を満たす閲覧者だけ。単票（F00 Resolver）と同じ結果になること。
     */
    @Test
    @DisplayName("CANCELLED/ARCHIVEDは所属中の著者でも公開範囲を満たさなければ一覧に出ない（単票と同じ）")
    void CANCELLEDは公開範囲も満たす必要がある() {
        Long adminsOnlyCancelled = persistTournament(
                TournamentVisibility.ADMINS_AND_ABOVE, TournamentStatus.CANCELLED, VIEWER_ID);
        Long adminsOnlyArchived = persistTournament(
                TournamentVisibility.ADMINS_AND_ABOVE, TournamentStatus.ARCHIVED, VIEWER_ID);
        Long publicCancelled = persistTournament(
                TournamentVisibility.PUBLIC, TournamentStatus.CANCELLED, VIEWER_ID);
        em.flush();
        em.clear();

        // 所属中の著者（DEPUTY_ADMIN 未満）。可視ラダーは PUBLIC のみ
        Page<TournamentEntity> author = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), VIEWER_ID, true, false, PageRequest.of(0, 100));
        // DEPUTY_ADMIN 以上。可視ラダーに ADMINS_AND_ABOVE を含む
        Page<TournamentEntity> deputy = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC", "ADMINS_AND_ABOVE"), VIEWER_ID, true, true, PageRequest.of(0, 100));

        assertThat(author.getContent()).extracting(TournamentEntity::getId)
                .contains(publicCancelled)
                .doesNotContain(adminsOnlyCancelled, adminsOnlyArchived);
        assertThat(deputy.getContent()).extracting(TournamentEntity::getId)
                .contains(publicCancelled, adminsOnlyCancelled, adminsOnlyArchived);
    }

    @Test
    @DisplayName("PARTICIPANTS_ONLYのCANCELLED大会は、所属中の著者かつ参加チーム関係者にだけ一覧に出る")
    void CANCELLEDのPARTICIPANTS_ONLYは参加チーム関係者の著者だけ() {
        Long participantsOnly = persistTournament(
                TournamentVisibility.PARTICIPANTS_ONLY, TournamentStatus.CANCELLED, VIEWER_ID);
        Long divisionId = persistDivision(participantsOnly);
        persistParticipant(divisionId, PARTICIPANT_TEAM_ID, ParticipantStatus.REGISTERED);
        em.flush();
        em.clear();

        Page<TournamentEntity> notRelated = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), VIEWER_ID, true, false, PageRequest.of(0, 100));
        persistMembership(VIEWER_ID, PARTICIPANT_TEAM_ID);
        em.flush();
        em.clear();
        Page<TournamentEntity> related = tournamentRepository.findVisibleByOrganizationId(
                orgId, null, Set.of("PUBLIC"), VIEWER_ID, true, false, PageRequest.of(0, 100));

        assertThat(notRelated.getContent()).extracting(TournamentEntity::getId).doesNotContain(participantsOnly);
        assertThat(related.getContent()).extracting(TournamentEntity::getId).contains(participantsOnly);
    }

    private Long persistTournament(TournamentVisibility visibility, TournamentStatus status, Long createdBy) {
        TournamentEntity entity = TournamentEntity.builder()
                .organizationId(orgId)
                .name("大会")
                .format(com.mannschaft.app.tournament.TournamentFormat.LEAGUE)
                .visibility(visibility)
                .createdBy(createdBy)
                .build();
        em.persist(entity);
        entity.changeStatus(status);
        em.flush();
        return entity.getId();
    }

    private Long persistDivision(Long tournamentId) {
        TournamentDivisionEntity div = TournamentDivisionEntity.builder()
                .tournamentId(tournamentId)
                .name("1部")
                .build();
        em.persist(div);
        return div.getId();
    }

    private void persistParticipant(Long divisionId, Long teamId, ParticipantStatus status) {
        TournamentParticipantEntity p = TournamentParticipantEntity.builder()
                .divisionId(divisionId)
                .teamId(teamId)
                .status(status)
                .build();
        em.persist(p);
    }

    private void persistMembership(Long userId, Long teamId) {
        em.createNativeQuery(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                        + "VALUES (:uid, 'TEAM', :tid, 'MEMBER', NOW(), NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("tid", teamId)
                .executeUpdate();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
