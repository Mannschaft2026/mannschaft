package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentFixtureRosterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 出場メンバーリポジトリ。
 */
public interface TournamentFixtureRosterRepository extends JpaRepository<TournamentFixtureRosterEntity, Long> {

    List<TournamentFixtureRosterEntity> findByMatchIdOrderByParticipantIdAscJerseyNumberAsc(Long matchId);

    /**
     * 認可根治戦役 Wave2 トランシェ2C: 出場メンバーが指定試合配下に属するかの束縛検証（BOLA 対策）。
     */
    Optional<TournamentFixtureRosterEntity> findByIdAndMatchId(Long id, Long matchId);

    List<TournamentFixtureRosterEntity> findByMatchIdAndParticipantId(Long matchId, Long participantId);

    /** 自チーム分の roster を sortOrder 相当（背番号→id）で取得する（rosters/me 取得用） */
    List<TournamentFixtureRosterEntity> findByMatchIdAndParticipantIdOrderByJerseyNumberAscIdAsc(
            Long matchId, Long participantId);

    void deleteByMatchId(Long matchId);

    /**
     * 自チーム分の roster を全削除する（PUT rosters/me・apply-template の全置換 UPSERT に使用）
     *
     * <p>全置換（delete → 再 insert）用の一括 DELETE（CMP-261127-2135）。派生 delete（deleteBy...）は
     * 行を読んで remove を予約するだけで、DB への DELETE は flush まで遅れる。その間に IDENTITY 主キーの
     * save は INSERT を即時に流し、UUID 主キーでも flush は INSERT を DELETE より先に流すため、
     * 同じ一意キーでの再 insert が一意制約に当たる。JPQL の一括 DELETE はその場で DB に流れるので、
     * 後続の insert より必ず先に効く。{@code flushAutomatically} で呼び出し前の未確定の変更を先に流す。</p>
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM TournamentFixtureRosterEntity e WHERE e.matchId = :matchId AND e.participantId = :participantId")
    void deleteByMatchIdAndParticipantId(@Param("matchId") Long matchId, @Param("participantId") Long participantId);
}
