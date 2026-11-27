package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentStatDefEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 大会個人成績項目リポジトリ。
 */
public interface TournamentStatDefRepository extends JpaRepository<TournamentStatDefEntity, Long> {

    List<TournamentStatDefEntity> findByTournamentIdOrderBySortOrderAsc(Long tournamentId);

    List<TournamentStatDefEntity> findByTournamentIdAndIsRankingTargetTrueOrderBySortOrderAsc(Long tournamentId);

    Optional<TournamentStatDefEntity> findByTournamentIdAndStatKey(Long tournamentId, String statKey);

    /**
     * <p>全置換（delete → 再 insert）用の一括 DELETE（CMP-261127-2135）。派生 delete（deleteBy...）は
     * 行を読んで remove を予約するだけで、DB への DELETE は flush まで遅れる。その間に IDENTITY 主キーの
     * save は INSERT を即時に流し、UUID 主キーでも flush は INSERT を DELETE より先に流すため、
     * 同じ一意キーでの再 insert が一意制約に当たる。JPQL の一括 DELETE はその場で DB に流れるので、
     * 後続の insert より必ず先に効く。{@code flushAutomatically} で呼び出し前の未確定の変更を先に流す。</p>
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM TournamentStatDefEntity e WHERE e.tournamentId = :tournamentId")
    void deleteByTournamentId(@Param("tournamentId") Long tournamentId);
}
