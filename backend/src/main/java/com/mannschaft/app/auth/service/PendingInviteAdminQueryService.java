package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.dto.PendingReissueRequestRow;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 再招待依頼（registration_pending_invites の REISSUE_REQUESTED）の管理者向け集計（CMP-261107-1833 PR3 設計 b・f・i）。
 *
 * <p>管理者向け承認待ち集約（INVITE_REISSUE）に件数とプレビューを返す。件数の述語は再招待依頼の一覧と共有する
 * （{@link PendingInviteStore#countReissueRequests}: status=REISSUE_REQUESTED・スコープ一致・依頼者 ACTIVE・論理削除なし）。
 * {@code previewSize=0} または 0 件なら件数の 1 本だけで、表示名を引く SQL を出さない。プレビューの表示名は
 * Store の TX の外で {@link NameResolverService} によりまとめて 1 本で引く。{@code @Transactional} を持たない。
 * 招待権限による出し分けは呼び出し側（{@code AdminActionRequiredFacade}）が行う。</p>
 */
@Service
@RequiredArgsConstructor
public class PendingInviteAdminQueryService {

    private final PendingInviteStore store;
    private final NameResolverService nameResolverService;

    /**
     * 指定スコープの再招待依頼の件数とプレビュー（古い順）。
     *
     * @param scopeType   TEAM / ORGANIZATION
     * @param scopeId     スコープ ID
     * @param scopeSlug   スコープ slug（遷移先の組み立てに使う）
     * @param previewSize プレビュー件数（0 なら件数だけ）
     */
    public PendingAggregate reissueRequestsFor(String scopeType, Long scopeId, String scopeSlug, int previewSize) {
        long count = store.countReissueRequests(scopeType, scopeId);
        if (count == 0 || previewSize <= 0) {
            return new PendingAggregate(count, List.of());
        }
        List<PendingReissueRequestRow> rows = store.pageReissueRequests(scopeType, scopeId, 0, previewSize);
        Map<Long, String> names = nameResolverService.resolveUserDisplayNames(
                rows.stream().map(PendingReissueRequestRow::userId).distinct().toList());
        String route = reissueRequestsRoute(scopeType, scopeSlug);
        List<PendingAggregate.Item> items = rows.stream()
                .map(row -> new PendingAggregate.Item(row.id().toString(), names.get(row.userId()),
                        names.get(row.userId()), toServerLocal(row), route))
                .toList();
        return new PendingAggregate(count, items);
    }

    /** 招待管理画面の再招待依頼の欄（{@code /{teams|organizations}/{slug}/invites#reissue-requests}）。 */
    public static String reissueRequestsRoute(String scopeType, String scopeSlug) {
        return ("TEAM".equals(scopeType) ? "/teams/" : "/organizations/") + scopeSlug + "/invites#reissue-requests";
    }

    private static LocalDateTime toServerLocal(PendingReissueRequestRow row) {
        return row.requestedAt() == null ? null
                : LocalDateTime.ofInstant(row.requestedAt(), UserZoneLocalDateTimeParser.SERVER_ZONE);
    }
}
