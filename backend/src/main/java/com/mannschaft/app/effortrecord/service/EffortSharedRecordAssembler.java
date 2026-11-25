package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.effortrecord.dto.EffortSharedRecord;
import com.mannschaft.app.effortrecord.entity.EffortTeamShareEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F06.4.1 SharedRecord の組み立て（API 契約 §2）。チームへ見せる許可 snapshot（projection）と投稿者の公開プロフィールだけを使い、
 * 私的原本から値を組み立てない（親設計 §5）。プロフィールは 1 ページにつき一括で引く（行ごとに照会しない）。
 */
@Component
@RequiredArgsConstructor
public class EffortSharedRecordAssembler {

    /** purge 済み・退会済みの投稿者の表示名（著者だけを匿名化し、共有本文は残す。退会フロー §13.13）。 */
    public static final String WITHDRAWN_AUTHOR = "退会済みユーザー";

    private final EffortShareProjector projector;
    private final NameResolverService names;

    /** 投稿者の公開プロフィール（表示名・アバター）の一括解決結果。 */
    public record Authors(Map<Long, String> displayNames, Map<Long, String> avatarUrls) {
    }

    public Authors authors(Collection<Long> userIds) {
        Set<Long> ids = new HashSet<>(userIds);
        return new Authors(names.resolveUserDisplayNames(ids), names.resolveUserAvatarUrls(ids));
    }

    /** team 行から SharedRecord を組み立てる（anonymous なら著者を匿名化する）。 */
    public EffortSharedRecord fromTeamRow(EffortTeamShareEntity row, Authors authors, boolean anonymous) {
        EffortShareProjector.Projection projection = projector.readProjection(row.getProjectionJson());
        return build(row.getId(), row.getVersion(), row.getTeamId(), row.getOwnerUserId(), row.getRecordedOn(),
                row.getDefinitionId(), projection, row.getCreatedAt(), row.getUpdatedAt(), authors, anonymous);
    }

    public List<EffortSharedRecord> fromTeamRows(List<EffortTeamShareEntity> rows, Set<UUID> anonymousShares) {
        return fromTeamRows(rows, anonymousShares, authors(rows.stream().map(EffortTeamShareEntity::getOwnerUserId).toList()));
    }

    /** 解決済みの投稿者プロフィールで team 行を組み立てる（呼出元が匿名の判定に使ったのと同じ解決結果を使う）。 */
    public List<EffortSharedRecord> fromTeamRows(List<EffortTeamShareEntity> rows, Set<UUID> anonymousShares,
                                                 Authors authors) {
        List<EffortSharedRecord> result = new ArrayList<>(rows.size());
        rows.forEach(row -> result.add(fromTeamRow(row, authors, anonymousShares.contains(row.getId()))));
        return result;
    }

    /**
     * 著者を匿名で出すか（purge 済み、または公開プロフィールが解決できない＝退会済み）。{@link #build} の判定そのもので、
     * 利用者 ID で引く一覧から匿名の共有を除く判定（チーム一覧の {@code user_id} 指定）にも同じものを使う。
     */
    public boolean isAnonymous(Long ownerUserId, Authors authors, boolean purged) {
        return purged || authors.displayNames().get(ownerUserId) == null;
    }

    public EffortSharedRecord build(UUID shareId, long version, Long teamId, Long ownerUserId, LocalDate recordedOn,
                                    UUID definitionUuid, EffortShareProjector.Projection projection, Instant createdAt,
                                    Instant updatedAt, Authors authors, boolean anonymous) {
        EffortSharedRecord.Author author = isAnonymous(ownerUserId, authors, anonymous)
                ? new EffortSharedRecord.Author(null, WITHDRAWN_AUTHOR, null)
                : new EffortSharedRecord.Author(ownerUserId, authors.displayNames().get(ownerUserId),
                        authors.avatarUrls().get(ownerUserId));
        return new EffortSharedRecord(shareId, version, teamId, author, recordedOn, projection.recordTimezone(),
                definitionUuid, projection.templateName(), projection.fields(), createdAt, updatedAt,
                EffortSharedRecord.Interactions.NONE);
    }
}
