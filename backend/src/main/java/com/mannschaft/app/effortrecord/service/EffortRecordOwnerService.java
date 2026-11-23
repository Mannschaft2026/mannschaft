package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.timezone.UserTimezoneCache;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.effortrecord.dto.CreateEffortRecordRequest;
import com.mannschaft.app.effortrecord.dto.EffortDeleteResponse;
import com.mannschaft.app.effortrecord.dto.EffortOwnerRecordResponse;
import com.mannschaft.app.effortrecord.dto.EffortOwnerShare;
import com.mannschaft.app.effortrecord.dto.EffortRecordListCriteria;
import com.mannschaft.app.effortrecord.dto.EffortRecordPage;
import com.mannschaft.app.effortrecord.dto.UpdateEffortRecordRequest;
import com.mannschaft.app.effortrecord.entity.EffortRecordEntity;
import com.mannschaft.app.effortrecord.enums.EffortShareState;
import com.mannschaft.app.membership.dto.EffortShareQualification;
import com.mannschaft.app.membership.dto.EffortShareQualificationQuery;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F06.4.1 個人原本の Owner Service（非 TX の入口）。全操作は認証主体 ownerUserId に束縛される。
 *
 * <p>他人・不在・削除済み・purged は {@code EFFORT_001}（404）で区別しない。業務 TX は
 * {@link EffortRecordTxService} が持つ。本クラスは TX の外で本人 TZ（記録日の未来判定に使う）を解決して渡す
 * （TX 入口から他ドメイン Repository へ到達させない。ArchUnit D-3T）。</p>
 *
 * <p>共有の資格（共有に束縛された加入期間 ID が今も有効か）も TX の外で TEAM 側へ照会する（データ設計 §8）。
 * owner の GET は加入期間が終わった共有を実効 state=REVOKED で返し（owner 行の後処理の遅れに関係なく古い VISIBLE・FAILED の
 * retry 導線を出さない）、原本の訂正は資格がある間だけ同じ共有の同期を要求する。</p>
 */
@Service
@RequiredArgsConstructor
public class EffortRecordOwnerService {

    private static final ZoneId FALLBACK_ZONE = UserZoneLocalDateTimeParser.SERVER_ZONE;

    private final EffortRecordTxService tx;
    private final EffortShareTxService shareTx;
    private final UserTimezoneCache userTimezones;
    private final EffortDefinitionTokenCodec tokens;
    private final EffortTeamAccessGuard teamAccess;
    private final MembershipService membershipService;
    private final TeamService teamService;

    /**
     * 原本を作成する（Idempotency-Key 必須）。
     *
     * <p>TEAM 定義（definitionToken あり）は、token の署名・期限・本人・定義 UUID・revision の束縛を検証し
     * （不正は 400 definitionToken）、token が束縛するチームへの<b>現在所属</b>を再検証してから
     * （脱退済みは 403 COMMON_002）TX へ渡す。所属照会は TX の外で行う（TX 入口から他ドメインへ到達させない）。
     * 同じ Idempotency-Key・同じ本文の作成が完了済みなら、token の期限より先に元の応答を返し、同じ key・別の本文なら
     * token 検証より先に 409 COMMON_003 を返す（API 契約 §4.1）。</p>
     */
    public EffortOwnerRecordResponse create(Long ownerUserId, String idempotencyKey, CreateEffortRecordRequest request) {
        EffortDefinitionTokenCodec.Claims teamToken = null;
        if (request.definitionToken() != null) {
            java.util.Optional<EffortOwnerRecordResponse> completed = tx.findCompletedCreate(ownerUserId, idempotencyKey, request);
            if (completed.isPresent()) {
                return completed.get();
            }
            teamToken = tokens.verify(request.definitionToken(), ownerUserId, request.definitionUuid(),
                    request.definitionRevision());
            teamAccess.requireMember(ownerUserId, teamToken.teamId());
        }
        return tx.create(ownerUserId, idempotencyKey, request, userZone(ownerUserId), teamToken);
    }

    /** 本人の原本一覧（recordedOn DESC, createdAt DESC, id DESC のカーソルページング）。 */
    public EffortRecordPage list(Long ownerUserId, EffortRecordListCriteria criteria) {
        EffortRecordPage page = tx.list(ownerUserId, criteria);
        return new EffortRecordPage(withEffectiveShares(ownerUserId, page.items()), page.nextCursor(), page.hasNext(),
                page.limit());
    }

    /** 本人の原本詳細。 */
    public EffortOwnerRecordResponse get(Long ownerUserId, UUID id) {
        return withEffectiveShares(ownerUserId, List.of(tx.get(ownerUserId, id))).get(0);
    }

    /**
     * 原本を全置換更新する（version 一致必須。不一致は COMMON_003 / 409）。共有中の原本は、共有先チームが有効で
     * 加入期間が今も有効なときだけ同じ共有の同期を要求する（資格を失った共有は私的な更新だけで再同期しない）。
     */
    public EffortOwnerRecordResponse update(Long ownerUserId, UUID id, UpdateEffortRecordRequest request) {
        EffortRecordEntity current = shareTx.owned(ownerUserId, id);
        boolean resync = current.getShareId() != null && current.getShareState() != null
                && EffortShareTxService.ACTIVE.contains(current.getShareState())
                && teamService.findTeamSummary(current.getShareTeamId()).isPresent()
                && eligible(ownerUserId, current.getShareTeamId(), current.getGrantMembershipId());
        return tx.update(ownerUserId, id, request, userZone(ownerUserId), resync);
    }

    /** 原本を論理削除する（Idempotency-Key 必須、version 一致必須）。 */
    public EffortDeleteResponse delete(Long ownerUserId, UUID id, long version, String idempotencyKey) {
        return tx.delete(ownerUserId, id, version, idempotencyKey);
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    /**
     * 加入期間が終わった共有（PENDING・VISIBLE・FAILED のまま後処理を待つもの）を実効 state=REVOKED にする。
     * 資格照会は 1 ページにつき 1 回（PRIMARY の独立 TX）。
     */
    private List<EffortOwnerRecordResponse> withEffectiveShares(Long ownerUserId, List<EffortOwnerRecordResponse> items) {
        List<EffortShareQualificationQuery> queries = new ArrayList<>();
        for (EffortOwnerRecordResponse item : items) {
            if (isActive(item.share())) {
                queries.add(new EffortShareQualificationQuery(item.share().teamId(), ownerUserId,
                        item.share().grantMembershipId()));
            }
        }
        if (queries.isEmpty()) {
            return items;
        }
        Set<Long> eligibleGrants = new HashSet<>();
        for (EffortShareQualification q : membershipService.getEffortShareQualificationsBatch(queries)) {
            if (q.isCurrentEligible()) {
                eligibleGrants.add(q.grantMembershipId());
            }
        }
        List<EffortOwnerRecordResponse> result = new ArrayList<>(items.size());
        for (EffortOwnerRecordResponse item : items) {
            if (isActive(item.share()) && !eligibleGrants.contains(item.share().grantMembershipId())) {
                result.add(revoked(item));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    private boolean eligible(Long ownerUserId, Long teamId, Long grantMembershipId) {
        if (teamId == null || grantMembershipId == null) {
            return false;
        }
        List<EffortShareQualification> result = membershipService.getEffortShareQualificationsBatch(
                List.of(new EffortShareQualificationQuery(teamId, ownerUserId, grantMembershipId)));
        return !result.isEmpty() && result.get(0).isCurrentEligible();
    }

    private static boolean isActive(EffortOwnerShare share) {
        return share != null && share.state() != null && EffortShareTxService.ACTIVE.contains(share.state());
    }

    private static EffortOwnerRecordResponse revoked(EffortOwnerRecordResponse item) {
        EffortOwnerShare s = item.share();
        EffortOwnerShare effective = new EffortOwnerShare(s.id(), s.teamId(), EffortShareState.REVOKED, s.version(),
                s.sourceVersion(), s.grantMembershipId(), s.selectedFieldKeys(), s.lastErrorCode(), s.updatedAt());
        return new EffortOwnerRecordResponse(item.id(), item.version(), item.contentRevision(), item.recordedOn(),
                item.recordTimezone(), item.definition(), item.values(), item.privateNote(), effective, item.createdAt(),
                item.updatedAt());
    }

    private ZoneId userZone(Long ownerUserId) {
        try {
            return ZoneId.of(userTimezones.getTimezone(ownerUserId));
        } catch (DateTimeException | NullPointerException e) {
            return FALLBACK_ZONE;
        }
    }
}
