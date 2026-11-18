package com.mannschaft.app.search.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.organization.service.OrganizationQueryService;
import com.mannschaft.app.search.SearchViewer;
import com.mannschaft.app.team.service.TeamQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 検索の閲覧者（所属スコープとスコープ単位のロール）を 1 回だけ一括解決する（設計書 §6 検索実行フロー 3）。
 *
 * <p><b>母集団の規則</b>（マスター制約「権限範囲外のデータを出さない」）:</p>
 * <ul>
 *   <li>所属の列挙は正準窓口 {@link AccessControlService#findAffiliatedScopeIds}（{@code user_roles} ∪
 *       {@code memberships}）を使う。</li>
 *   <li>ロールは {@link MembershipBatchQueryService#snapshotForUser} の {@code roleByScope}（スコープごとに
 *       権限ロールと所属ロールの最強）で<b>スコープ単位</b>に決める。全所属先の和集合では判定しない。</li>
 *   <li>SUPPORTER 以上のロールを持つスコープだけを母集団に入れる。GUEST のみ・ロール不明（JOBBER 等の並行
 *       ロール）のスコープは除外する。</li>
 *   <li>SYSTEM_ADMIN の全体昇格は使わない（バイパスなし）。スナップショットが SYSTEM_ADMIN 扱いになった場合は
 *       スコープ別ロールが得られないため、所属スコープは母集団に入れない（安全側。SYSTEM_ADMIN は通常の
 *       テナントアカウントではない前提）。</li>
 *   <li>ACTIVE（承諾済み）かつ未アーカイブ・未削除のスコープだけを残す（論理削除済みスコープ経由で
 *       仲間やコンテンツが見えないようにする）。</li>
 * </ul>
 *
 * <p><b>SQL 数</b>: 所属スコープ数に依らない定数回（所属列挙 4 + ロール一括 最大 5 + 生存確認 2）。</p>
 *
 * <p><b>{@code @Transactional} を付けない理由</b>: 本クラスは他ドメイン（role / membership / team /
 * organization）の公開 Service を呼ぶだけの集約であり、トランザクション境界を検索ドメインへ広げない
 * （D-3 / D-3T 番人。各 Service が自ドメインの読み取りトランザクションを持つ）。</p>
 */
@Component
@RequiredArgsConstructor
public class SearchViewerResolver {

    private static final String TEAM = "TEAM";
    private static final String ORGANIZATION = "ORGANIZATION";

    /** 母集団に入れる最弱のロール。 */
    private static final String MIN_ROLE = "SUPPORTER";

    private final AccessControlService accessControlService;
    private final MembershipBatchQueryService membershipBatchQueryService;
    private final TeamQueryService teamQueryService;
    private final OrganizationQueryService organizationQueryService;

    /**
     * 閲覧者を解決する。
     *
     * @param userId 閲覧者のユーザー ID
     * @return 検索母集団に含める所属スコープ（スコープ単位のロール付き）
     */
    public SearchViewer resolve(Long userId) {
        Set<Long> teamIds = accessControlService.findAffiliatedScopeIds(userId, TEAM);
        Set<Long> orgIds = accessControlService.findAffiliatedScopeIds(userId, ORGANIZATION);
        if (teamIds.isEmpty() && orgIds.isEmpty()) {
            return new SearchViewer(userId, List.of());
        }

        Set<ScopeKey> scopes = new LinkedHashSet<>();
        teamIds.forEach(id -> scopes.add(new ScopeKey(TEAM, id)));
        orgIds.forEach(id -> scopes.add(new ScopeKey(ORGANIZATION, id)));
        UserScopeRoleSnapshot snapshot = membershipBatchQueryService.snapshotForUser(userId, scopes, Set.of());
        if (snapshot.isSystemAdmin()) {
            return new SearchViewer(userId, List.of());
        }

        Set<Long> activeTeamIds = teamQueryService.findActiveIds(teamIds);
        Set<Long> activeOrgIds = organizationQueryService.findActiveIds(orgIds);

        List<SearchViewer.ViewerScope> viewerScopes = new ArrayList<>();
        for (ScopeKey scope : scopes) {
            boolean alive = TEAM.equals(scope.scopeType())
                    ? activeTeamIds.contains(scope.scopeId())
                    : activeOrgIds.contains(scope.scopeId());
            if (!alive) {
                continue;
            }
            String role = snapshot.roleByScope().get(scope);
            if (role == null || !RolePriority.isRegistered(role) || "SYSTEM_ADMIN".equals(role)
                    || !RolePriority.isAtLeast(role, MIN_ROLE)) {
                continue;
            }
            viewerScopes.add(new SearchViewer.ViewerScope(scope.scopeType(), scope.scopeId(), role));
        }
        viewerScopes.sort(Comparator.comparing(SearchViewer.ViewerScope::scopeType)
                .thenComparing(SearchViewer.ViewerScope::scopeId));
        return new SearchViewer(userId, List.copyOf(viewerScopes));
    }
}
