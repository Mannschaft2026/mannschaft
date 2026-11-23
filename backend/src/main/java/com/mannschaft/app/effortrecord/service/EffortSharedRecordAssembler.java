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
        Authors authors = authors(rows.stream().map(EffortTeamShareEntity::getOwnerUserId).toList());
        List<EffortSharedRecord> result = new ArrayList<>(rows.size());
        rows.forEach(row -> result.add(fromTeamRow(row, authors, anonymousShares.contains(row.getId()))));
        return result;
    }

    public EffortSharedRecord build(UUID shareId, long version, Long teamId, Long ownerUserId, LocalDate recordedOn,
                                    UUID definitionUuid, EffortShareProjector.Projection projection, Instant createdAt,
                                    Instant updatedAt, Authors authors, boolean anonymous) {
        String displayName = authors.displayNames().get(ownerUserId);
        EffortSharedRecord.Author author = anonymous || displayName == null
                ? new EffortSharedRecord.Author(null, WITHDRAWN_AUTHOR, null)
                : new EffortSharedRecord.Author(ownerUserId, displayName, authors.avatarUrls().get(ownerUserId));
        return new EffortSharedRecord(shareId, version, teamId, author, recordedOn, projection.recordTimezone(),
                definitionUuid, projection.templateName(), projection.fields(), createdAt, updatedAt,
                EffortSharedRecord.Interactions.NONE);
    }
}
