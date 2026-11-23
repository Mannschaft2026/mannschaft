package com.mannschaft.app.effortrecord.repository;

import com.mannschaft.app.effortrecord.entity.EffortTeamShareEntity;
import com.mannschaft.app.effortrecord.enums.EffortTeamShareState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * F06.4.1 チーム共有の読み取りクエリ（一覧の走査・定義候補・F00 の owner 照合・チーム削除の後処理・保守の走査）。
 *
 * <p>チーム側の照会はすべて {@code team_id} を必須条件にし（保守の走査を除く）、
 * {@code idx_effort_team_date}（team, deleted_at, state, recorded_on DESC, created_at DESC, id DESC）の範囲で読む。
 * F00 の owner 照合は原本 ID の集合を 1 回で読む専用 native query で、purged 行（tombstone）も読み、
 * JSON 本文列（値・note・snapshot）を SELECT しない（データ設計 §7.1）。</p>
 */
@Repository
public class EffortTeamShareQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /** 一覧の走査位置（この位置より後ろを読む）。 */
    public record After(LocalDate recordedOn, Instant createdAt, UUID id) {
    }

    /**
     * F00 の owner 照合に使う原本の共有列（値・note・snapshot を含まない）。
     *
     * @param purged  退会 purge 後の tombstone か
     * @param deleted 本人が原本を削除済みか（明示削除は deny）
     */
    public record OwnerShareState(UUID recordId, Long ownerUserId, UUID shareId, Long shareTeamId, String shareState,
                                  long shareVersion, long contentRevision, Long syncedSourceVersion,
                                  Long grantMembershipId, String desiredHash, boolean purged, boolean deleted) {
    }

    /**
     * チームの VISIBLE 行を recordedOn DESC, createdAt DESC, id DESC の順で after より後ろから fetchSize 件読む。
     */
    public List<EffortTeamShareEntity> findVisiblePage(Long teamId, LocalDate dateFrom, LocalDate dateTo,
                                                       UUID definitionUuid, Long ownerUserId, After after, int fetchSize) {
        StringBuilder jpql = new StringBuilder("SELECT s FROM EffortTeamShareEntity s WHERE s.teamId = :team "
                + "AND s.state = :state");
        if (ownerUserId != null) {
            jpql.append(" AND s.ownerUserId = :owner");
        }
        if (dateFrom != null) {
            jpql.append(" AND s.recordedOn >= :dateFrom");
        }
        if (dateTo != null) {
            jpql.append(" AND s.recordedOn <= :dateTo");
        }
        if (definitionUuid != null) {
            jpql.append(" AND s.definitionId = :definition");
        }
        if (after != null) {
            jpql.append(" AND (s.recordedOn < :cursorDate"
                    + " OR (s.recordedOn = :cursorDate AND s.createdAt < :cursorCreated)"
                    + " OR (s.recordedOn = :cursorDate AND s.createdAt = :cursorCreated AND s.id < :cursorId))");
        }
        jpql.append(" ORDER BY s.recordedOn DESC, s.createdAt DESC, s.id DESC");
        TypedQuery<EffortTeamShareEntity> query = entityManager.createQuery(jpql.toString(), EffortTeamShareEntity.class)
                .setParameter("team", teamId)
                .setParameter("state", EffortTeamShareState.VISIBLE)
                .setMaxResults(fetchSize);
        if (ownerUserId != null) {
            query.setParameter("owner", ownerUserId);
        }
        if (dateFrom != null) {
            query.setParameter("dateFrom", dateFrom);
        }
        if (dateTo != null) {
            query.setParameter("dateTo", dateTo);
        }
        if (definitionUuid != null) {
            query.setParameter("definition", definitionUuid);
        }
        if (after != null) {
            query.setParameter("cursorDate", after.recordedOn())
                    .setParameter("cursorCreated", after.createdAt())
                    .setParameter("cursorId", after.id());
        }
        return query.getResultList();
    }

    /**
     * F00 の owner 照合（原本 ID の集合を 1 回で読む。purged を含み、本文列を読まない）。
     * 原本 ID は UUID 型の値だけを 16 進リテラルへ写すので、入力文字列を SQL へ埋め込まない。
     */
    public List<OwnerShareState> findOwnerShareStates(Collection<UUID> recordIds) {
        if (recordIds.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("SELECT LOWER(HEX(id)), owner_user_id, LOWER(HEX(share_id)), "
                        + "share_team_id, share_state, share_version, content_revision, synced_source_version, "
                        + "grant_membership_id, desired_hash, purged_at IS NOT NULL, deleted_at IS NOT NULL "
                        + "FROM effort_records WHERE id IN (" + hexList(recordIds) + ")")
                .getResultList();
        List<OwnerShareState> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new OwnerShareState(uuid((String) row[0]), ((Number) row[1]).longValue(), uuid((String) row[2]),
                    row[3] == null ? null : ((Number) row[3]).longValue(), row[4] == null ? null : String.valueOf(row[4]),
                    ((Number) row[5]).longValue(), ((Number) row[6]).longValue(),
                    row[7] == null ? null : ((Number) row[7]).longValue(),
                    row[8] == null ? null : ((Number) row[8]).longValue(), row[9] == null ? null : String.valueOf(row[9]),
                    truthy(row[10]), truthy(row[11])));
        }
        return result;
    }

    /** share UUID の集合のチーム側共有行（未削除）を 1 回で読む（F00 の team 照合）。 */
    public List<EffortTeamShareEntity> findByIds(Collection<UUID> shareIds) {
        if (shareIds.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery("SELECT s FROM EffortTeamShareEntity s WHERE s.id IN :ids",
                        EffortTeamShareEntity.class)
                .setParameter("ids", shareIds)
                .getResultList();
    }

    /** 定義候補用: チームの VISIBLE 行を新しい順に最大 limit 件（走査上限）。 */
    public List<EffortTeamShareEntity> findVisibleForCandidates(Long teamId, int limit) {
        return entityManager.createQuery("SELECT s FROM EffortTeamShareEntity s WHERE s.teamId = :team AND s.state = :state "
                        + "ORDER BY s.recordedOn DESC, s.createdAt DESC, s.id DESC", EffortTeamShareEntity.class)
                .setParameter("team", teamId)
                .setParameter("state", EffortTeamShareState.VISIBLE)
                .setMaxResults(limit)
                .getResultList();
    }

    /** 保守の走査: 未失効（VISIBLE・PENDING）の共有行を id 昇順で after より後ろから limit 件。 */
    public List<EffortTeamShareEntity> findLiveAfter(UUID afterId, int limit) {
        String jpql = "SELECT s FROM EffortTeamShareEntity s WHERE s.state IN :states"
                + (afterId == null ? "" : " AND s.id > :after") + " ORDER BY s.id ASC";
        TypedQuery<EffortTeamShareEntity> query = entityManager.createQuery(jpql, EffortTeamShareEntity.class)
                .setParameter("states", List.of(EffortTeamShareState.VISIBLE, EffortTeamShareState.PENDING))
                .setMaxResults(limit);
        if (afterId != null) {
            query.setParameter("after", afterId);
        }
        return query.getResultList();
    }

    /** 加入期間の終了: そのチームで endedMembershipId に束縛された未失効（VISIBLE・PENDING）の行。 */
    public List<EffortTeamShareEntity> findLiveForMembership(Long teamId, Long ownerUserId, Long grantMembershipId) {
        return entityManager.createQuery("SELECT s FROM EffortTeamShareEntity s WHERE s.teamId = :team "
                        + "AND s.ownerUserId = :owner AND s.grantMembershipId = :grant AND s.state IN :states",
                        EffortTeamShareEntity.class)
                .setParameter("team", teamId)
                .setParameter("owner", ownerUserId)
                .setParameter("grant", grantMembershipId)
                .setParameter("states", List.of(EffortTeamShareState.VISIBLE, EffortTeamShareState.PENDING))
                .getResultList();
    }

    /** チーム削除の後処理: そのチームの共有行を論理削除する（物理削除しない）。冪等。 */
    public int softDeleteByTeam(Long teamId, Instant now) {
        return entityManager.createNativeQuery("UPDATE effort_team_shares SET deleted_at = ?1, updated_at = ?1 "
                        + "WHERE team_id = ?2 AND deleted_at IS NULL")
                .setParameter(1, now)
                .setParameter(2, teamId)
                .executeUpdate();
    }

    private static String hexList(Collection<UUID> ids) {
        StringBuilder sql = new StringBuilder();
        for (UUID id : ids) {
            if (!sql.isEmpty()) {
                sql.append(',');
            }
            sql.append("UNHEX('").append(id.toString().replace("-", "")).append("')");
        }
        return sql.toString();
    }

    private static UUID uuid(String hex) {
        if (hex == null) {
            return null;
        }
        byte[] bytes = HexFormat.of().parseHex(hex);
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bytes[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (bytes[i] & 0xff);
        }
        return new UUID(msb, lsb);
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && ((Number) value).intValue() == 1;
    }
}
