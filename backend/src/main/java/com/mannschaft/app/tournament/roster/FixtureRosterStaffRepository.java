package com.mannschaft.app.tournament.roster;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * ベンチ入り役員リポジトリ（F08.7.1/05 §8.3）。
 */
public interface FixtureRosterStaffRepository extends JpaRepository<FixtureRosterStaffEntity, UUID> {

    /** 試合×参加チーム単位でベンチ役員一覧を取得する */
    List<FixtureRosterStaffEntity> findByMatchIdAndParticipantIdOrderByCreatedAtAsc(Long matchId, Long participantId);

    /** 試合の全参加チームのベンチ役員一覧を取得する（主催者ビュー用） */
    List<FixtureRosterStaffEntity> findByMatchIdOrderByParticipantIdAscCreatedAtAsc(Long matchId);

    /**
     * 自チーム分のベンチ役員を全削除する（提出時の全置換 UPSERT に使用）
     *
     * <p>全置換（delete → 再 insert）用の一括 DELETE（CMP-261127-2135）。派生 delete（deleteBy...）は
     * 行を読んで remove を予約するだけで、DB への DELETE は flush まで遅れる。その間に IDENTITY 主キーの
     * save は INSERT を即時に流し、UUID 主キーでも flush は INSERT を DELETE より先に流すため、
     * 同じ一意キーでの再 insert が一意制約に当たる。JPQL の一括 DELETE はその場で DB に流れるので、
     * 後続の insert より必ず先に効く。{@code flushAutomatically} で呼び出し前の未確定の変更を先に流す。</p>
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM FixtureRosterStaffEntity e WHERE e.matchId = :matchId AND e.participantId = :participantId")
    void deleteByMatchIdAndParticipantId(@Param("matchId") Long matchId, @Param("participantId") Long participantId);
}
