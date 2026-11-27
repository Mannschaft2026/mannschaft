package com.mannschaft.app.common;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * スコープの中身を読み取る<b>共通の門</b>（CMP-261124-0159・F00 §20.3）。
 *
 * <p>チームの公開範囲（{@code teams.visibility}）をそのチームの中身の閲覧上限とする。非公開チーム
 * （{@code MEMBERS_AND_ABOVE}）の中身はそのチームの ADMIN / DEPUTY_ADMIN / MEMBER にだけ見せ、サポーター・ゲスト・
 * 部外者・他チーム／他組織の利用者には見せない。公開側のチーム・TEAM 以外のスコープでは
 * {@link AccessControlService#checkMembership} と同じ判定（挙動不変）。</p>
 *
 * <h2>既存メソッドの組合せで作る（二つ目の判定器を作らない。精査 M-2）</h2>
 * <p>{@link AccessControlService#isMember} が真、かつ TEAM の非公開チームでは
 * {@link AccessControlService#isRegularMemberOrAbove} も真であること。SYSTEM_ADMIN を特別扱いしない
 * （{@code checkMembership} と同じく在籍が無ければ拒否。§20.2 規則 6「各経路の結果を変えない」）。
 * 拒否は {@code checkMembership} の非所属拒否と同一の 403 {@code COMMON_002}（新しい拒否コードを作らない。§20.4）。</p>
 *
 * <h2>なぜ {@link AccessControlService} に足さず共通基盤の部品にするか</h2>
 * <p>{@code DefaultAccessControlService}（role ドメイン）はクラス単位の {@code @Transactional} を持つため、
 * そこへ公開メソッドを足すと、その全てが他ドメイン（membership）の Repository へ推移的に到達するトランザクション入口になる
 * （番人 D-3T の新規違反）。本部品はトランザクションを開かず、{@link AccessControlService} の既存の照会と
 * 共通基盤の {@link MembersOnlyTeamQuery} だけを組み合わせる。</p>
 *
 * <p>呼び出し側は、中身を返す読み取りに {@link #checkScopeContentAccess} 系、中身を返さない書き込みに
 * {@link #checkMembershipForWrite} を使い、{@code checkMembership} / {@code isMember} を直接呼ばない
 * （番人 {@code ScopeContentAccessGateCoverageTest}。F00 §20.5.1）。</p>
 */
@Component
@RequiredArgsConstructor
public class ScopeContentAccessGate {

    private static final String TEAM = "TEAM";

    private final AccessControlService accessControlService;
    private final MembersOnlyTeamQuery membersOnlyTeamQuery;

    /**
     * 中身の読み取りを要求する。違反時は {@link AccessControlService#checkMembership} の非所属拒否と同一の
     * 403（COMMON_002）。
     */
    public void checkScopeContentAccess(Long userId, Long scopeId, String scopeType) {
        if (!canAccessScopeContent(userId, scopeId, scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /** {@link #checkScopeContentAccess} の真偽版（例外を投げない）。 */
    public boolean canAccessScopeContent(Long userId, Long scopeId, String scopeType) {
        if (userId == null || scopeId == null) {
            return false;
        }
        if (!accessControlService.isMember(userId, scopeId, scopeType)) {
            return false;
        }
        return !isBeyondTeamContentCap(userId, scopeId, scopeType);
    }

    /**
     * 複数チームの一括版。読めるチーム ID だけを入力順に返す。
     *
     * <p>在籍資格は既存の {@link AccessControlService#getTeamMembershipStates}（在籍と ADMIN / DEPUTY_ADMIN の
     * 一括取得）で求め、在籍はあるが正式メンバー以上でないチームがあるときだけ、それらに限った非公開チームの判定を
     * 1 本追加する（チーム数・件数に比例しない）。在籍の判定はこの投影の規則（archive 済みの在籍を除く）に従うため、
     * 単体版より狭い側（閲覧不可側）に倒れる。</p>
     */
    public Set<Long> filterAccessibleTeamIds(Long userId, Collection<Long> teamIds) {
        if (userId == null || teamIds == null || teamIds.isEmpty()) {
            return Set.of();
        }
        List<AccessControlService.TeamUserKey> keys = teamIds.stream().filter(Objects::nonNull).distinct()
                .map(teamId -> new AccessControlService.TeamUserKey(teamId, userId)).toList();
        if (keys.isEmpty()) {
            return Set.of();
        }
        Map<AccessControlService.TeamUserKey, AccessControlService.TeamMembershipState> states =
                accessControlService.getTeamMembershipStates(keys);
        List<Long> active = new ArrayList<>();
        List<Long> nonRegular = new ArrayList<>();
        for (AccessControlService.TeamUserKey key : keys) {
            AccessControlService.TeamMembershipState state = states.get(key);
            if (state == null || !state.active()) {
                continue;
            }
            active.add(key.teamId());
            if (!state.regularMemberOrAbove()) {
                nonRegular.add(key.teamId());
            }
        }
        if (nonRegular.isEmpty()) {
            return new LinkedHashSet<>(active);
        }
        Set<Long> capped = membersOnlyTeamQuery.findMembersOnlyTeamIds(nonRegular);
        return active.stream().filter(id -> !capped.contains(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 中身を返さない書き込み用の在籍確認。判定は {@link AccessControlService#checkMembership} と同一
     * （呼び出し箇所の意図を明示し、番人で読み取りと区別するための別名。F00 §20.5.1）。
     * 不変条件（非公開チームにサポーター・ゲストが在籍しない）に依拠する。
     */
    public void checkMembershipForWrite(Long userId, Long scopeId, String scopeType) {
        accessControlService.checkMembership(userId, scopeId, scopeType);
    }

    /**
     * 親チーム上限を越えるか（TEAM の非公開チームで、正式メンバー以上でない）。在籍の有無は問わない
     * （所有者・個別許可の成功経路に上限を AND するための判定。F00 §20.4・AC-0159-94）。
     * TEAM 以外・公開側のチームでは false（teams の主キーの問い合わせ 1 本だけ）。
     */
    public boolean isBeyondTeamContentCap(Long userId, Long scopeId, String scopeType) {
        if (!TEAM.equals(scopeType) || scopeId == null) {
            return false;
        }
        if (!membersOnlyTeamQuery.findMembersOnlyTeamIds(List.of(scopeId)).contains(scopeId)) {
            return false;
        }
        return userId == null || !accessControlService.isRegularMemberOrAbove(userId, scopeId, scopeType);
    }
}
