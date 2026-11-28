package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentIndividualRankingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 個人ランキングリポジトリ。
 */
public interface TournamentIndividualRankingRepository extends JpaRepository<TournamentIndividualRankingEntity, Long> {

    Page<TournamentIndividualRankingEntity> findByTournamentIdAndStatKeyOrderByRankAsc(
            Long tournamentId, String statKey, Pageable pageable);

    List<TournamentIndividualRankingEntity> findByTournamentIdAndStatKeyOrderByRankAsc(
            Long tournamentId, String statKey);

    Optional<TournamentIndividualRankingEntity> findByTournamentIdAndStatKeyAndUserId(
            Long tournamentId, String statKey, Long userId);

    /**
     * <p>全置換（delete → 再 insert）用の一括 DELETE（CMP-261127-2135）。派生 delete（deleteBy...）は
     * 行を読んで remove を予約するだけで、DB への DELETE は flush まで遅れる。その間に IDENTITY 主キーの
     * save は INSERT を即時に流し、UUID 主キーでも flush は INSERT を DELETE より先に流すため、
     * 同じ一意キーでの再 insert が一意制約に当たる。JPQL の一括 DELETE はその場で DB に流れるので、
     * 後続の insert より必ず先に効く。{@code flushAutomatically} で呼び出し前の未確定の変更を先に流す。</p>
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM TournamentIndividualRankingEntity e WHERE e.tournamentId = :tournamentId AND e.statKey = :statKey")
    void deleteByTournamentIdAndStatKey(@Param("tournamentId") Long tournamentId, @Param("statKey") String statKey);

    void deleteByTournamentId(Long tournamentId);
}
