package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.dto.InviteClaimLock;
import com.mannschaft.app.role.service.InviteRolePolicy.GrantTarget;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.TeamErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * スコープ（TEAM/ORGANIZATION）へのロール付与＋メンバーシップ入会を行う共通経路。
 *
 * <p>招待承諾（{@link InviteAcceptanceFacade}）と参加申請承認（柱③-A・CMP-260901-1538）の双方が
 * 本サービスを経由することで、メンバーシップ付与ロジックを二重実装しない
 * （CLAUDE.md 障害対応の原則・根治治療）。</p>
 *
 * <p>越境トランザクション方針（原則5）: 本サービスは role ドメイン（{@link UserRoleRepository}）と
 * membership ドメイン（{@link MembershipService}）にまたがるため、公開メソッドに {@code @Transactional}
 * を持ち、呼び出し元のトランザクションに相乗りできる。クラス単位の {@code @Transactional} は付けない
 * （付けると private ヘルパ {@code grant} が D-3T の入口になる）。</p>
 *
 * <h2>ロック順（CMP-260826-1457 §7）</h2>
 * <p>全経路で「user 行 → スコープ管理者行 → user_roles → memberships」の順に取る
 * （{@code RoleService#removeMemberWithoutAdminCheck} と同じ）。最初のクエリを user 行の
 * {@code FOR UPDATE} にするので、待たされた後の在籍判定はロック取得後に始まる。
 * 招待承諾で付与の元の claim があるときは、user 行の次に claim 行をロックする（下記）。</p>
 *
 * <h2>付与の元の claim（CMP-261107-1833・Codex 検分 P1/P2）</h2>
 * <p>{@link InviteAcceptanceFacade} が {@link InviteClaimGrantScope} の範囲内で {@link #grantRole} を呼んだときは、
 * 同じトランザクションで claim 行をロックし、次の順に判定する。</p>
 * <ol>
 *   <li>この claim の付与印が既にある（別 attempt がこの claim で付与をコミット済み）→ 何も書かず {@code ALREADY_JOINED}</li>
 *   <li>在籍済み（別経路の参加）→ 何も書かず {@code ALREADY_JOINED}（印は立てないので、呼び出し元は claim を戻せる）</li>
 *   <li>claim が CLAIMED でない（別 attempt が戻した）→ ROLE_002（付与しない）</li>
 *   <li>付与し、同じトランザクションで claim に付与印を立てて {@code GRANTED}</li>
 * </ol>
 * <p>付与と印は一緒にコミット（またはロールバック）されるので、claim を戻すか確定するかは印だけで決まる。</p>
 *
 * <h2>書き先（CMP-261124-0157）</h2>
 * <p>{@code memberships.role_kind} は SUPPORTER 招待なら SUPPORTER、それ以外は MEMBER とし、SUPPORTER は
 * {@code user_roles} を作らない（F01.2 03_business_logic.md「招待参加フロー」手順8。SUPPORTER の user_roles 行は
 * V60.010 で廃止済み）。権限ロール（ADMIN / DEPUTY_ADMIN）は {@code user_roles} に割り当てる。MEMBER は暫定で
 * 従来どおり {@code user_roles} にも MEMBER 行を作る（除名・ロール変更が行を前提にしているため。{@link InviteRolePolicy}）。
 * 所属ロールにも権限ロールにも当たらないロール（GUEST / SYSTEM_ADMIN 等）は付与せず 422 ROLE_009 とする。</p>
 *
 * <h2>2つの入口の差（現状維持の範囲）</h2>
 * <ul>
 *   <li>{@link #grantRole}（招待）: user が ACTIVE であることを確認し（違えば ROLE_002 でトランザクションごと
 *       巻き戻す）、user_roles ∪ memberships の在籍を現在読みで判定する。既に在籍なら
 *       {@link GrantOutcome#ALREADY_JOINED} を返す。</li>
 *   <li>{@link #grantMemberRole}（参加申請承認）: 現行どおり ACTIVE を問わず、memberships のみを
 *       スナップショット読みで判定する。競合時の一意制約違反・user_roles のみ残存時の挙動も現行のまま
 *       （改善は別戦役 (c)）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MembershipGrantService {

    /** 付与の結果。{@code ALREADY_JOINED} のときは何も書いていない。 */
    public enum GrantOutcome {
        GRANTED,
        ALREADY_JOINED
    }

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final MembershipService membershipService;
    private final UserRowLockService userRowLockService;
    private final InviteTokenRepository inviteTokenRepository;
    private final PlatformAccountAffiliationPolicy platformAccountAffiliationPolicy;

    private static final String MEMBER_ROLE_NAME = "MEMBER";

    /**
     * MEMBER ロールを付与して入会させる（他ドメインが role ドメインの {@link RoleRepository} を
     * 直接注入せずに済む Service 経路。D-5 ArchUnit 準拠: クラスは別ドメイン Repository に
     * 直接依存しない）。柱③-A 参加申請承認から使用する。
     *
     * @param scopeType "TEAM" または "ORGANIZATION"
     * @param scopeId   スコープ ID
     * @param userId    付与対象ユーザー ID
     * @param grantedBy 承認者ユーザー ID
     * @param source    入会経路（例: JOIN_REQUEST）
     */
    @Transactional
    public void grantMemberRole(String scopeType, Long scopeId, Long userId, Long grantedBy, String source) {
        // 1. user 行を先にロックする（最初のクエリ）。参加申請承認は現行どおり ACTIVE を問わない。
        userRowLockService.lock(userId);
        // 運営アカウント（SYSTEM_ADMIN）はテナントに所属できない（戦役 Z-A PR2）。
        platformAccountAffiliationPolicy.assertAffiliatable(userId);
        ScopeType st = toScopeType(scopeType);
        // 2. 冪等性チェック（P1-2）。
        if (isAlreadyJoined(Mode.JOIN_REQUEST, st, scopeId, userId)) {
            logSkipped(scopeType, scopeId, userId, source);
            return;
        }
        Long grantRoleId = memberRoleId();
        grant(scopeType, scopeId, userId, grantRoleId, memberTarget(), grantedBy, source, false);
        logGranted(scopeType, scopeId, userId, grantRoleId, source);
    }

    /**
     * 招待ロールに応じて入会させる（招待承諾用）。SUPPORTER は {@code memberships} に SUPPORTER として入会させるだけで
     * {@code user_roles} は作らない。MEMBER（暫定）と権限ロール（ADMIN / DEPUTY_ADMIN）は {@code user_roles} に割当て、
     * {@code memberships} へは MEMBER として入会させる（クラス javadoc「書き先」）。
     *
     * <p>{@link InviteClaimGrantScope} の範囲内で呼ばれたときは、付与の元の claim を同じトランザクションで判定し、
     * 付与したら付与印を立てる（クラス javadoc「付与の元の claim」）。参加申請承認（{@link #grantMemberRole}）は
     * claim に触れない（招待の確保記録へ到達する経路を参加申請側の越境TXに持ち込まない）。</p>
     *
     * @param scopeType  "TEAM" または "ORGANIZATION"
     * @param scopeId    スコープ ID
     * @param userId     付与対象ユーザー ID
     * @param roleId     付与するロール ID（呼出側で招待可能であることを検証済みであること。null は MEMBER）
     * @param grantedBy  付与を行った操作者ユーザー ID（招待者・承認者等）
     * @param source     入会経路（{@code MembershipCreateRequest#source}。例: INVITE_TOKEN）
     * @return 付与した（{@code GRANTED}）か、既に在籍で何もしなかった（{@code ALREADY_JOINED}）か
     * @throws BusinessException ROLE_002（user が ACTIVE でない、または付与の元の claim が既に戻されていた）、
     *                           ROLE_001（ロールが存在しない）、422 ROLE_009（付与できないロール）、
     *                           TEAM_003（競合して在籍が先に確定した）
     */
    @Transactional
    public GrantOutcome grantRole(String scopeType, Long scopeId, Long userId, Long roleId, Long grantedBy,
                                  String source) {
        // 1. user 行を先にロックする（最初のクエリ。ロック待ちの後に在籍判定が始まるようにする）。
        // 2. ACTIVE を確認する。退会競合は user ロック下のこの確認で直列化される。
        if (userRowLockService.lock(userId) != UserRowLockService.UserState.ACTIVE) {
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }
        // 運営アカウント（SYSTEM_ADMIN）はテナントに所属できない（戦役 Z-A PR2）。claim に触れる前に拒否する。
        platformAccountAffiliationPolicy.assertAffiliatable(userId);

        // 2b. 付与の元の claim を、user 行の次にロックして読む（クラス javadoc「付与の元の claim」）。
        InviteClaimLock claim = lockGrantingClaim().orElse(null);
        if (claim != null && claim.grantCommitted()) {
            log.info("ロール付与をスキップ（この claim による付与は別 attempt がコミット済み）: claimId={}, userId={}",
                    claim.claimId(), userId);
            return GrantOutcome.ALREADY_JOINED;
        }

        // 3. 冪等性チェック（P1-2）: 既に当該スコープのメンバーであれば二重割当・重複入会を行わない。
        if (isAlreadyJoined(Mode.INVITE, toScopeType(scopeType), scopeId, userId)) {
            logSkipped(scopeType, scopeId, userId, source);
            return GrantOutcome.ALREADY_JOINED;
        }

        if (claim != null && !claim.claimed()) {
            // 付与の前に別 attempt がこの claim を戻した（招待の失効・期限切れ等）。使用回数の裏付けが無いので付与しない。
            log.info("ロール付与を中止（付与の元の claim が既に {}）: claimId={}, userId={}", claim.status(),
                    claim.claimId(), userId);
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }

        Long grantRoleId = roleId != null ? roleId : memberRoleId();
        GrantTarget target = roleId != null ? resolveGrantTarget(roleId) : memberTarget();
        try {
            grant(scopeType, scopeId, userId, grantRoleId, target, grantedBy, source, true);
        } catch (DataIntegrityViolationException e) {
            // user ロック下の現在読みを通った後の一意制約違反は、並行して在籍が確定した競合。
            // TX は巻き戻る。業務エラーに変換するので、呼び出し側は確保した使用回数を戻せる。
            log.warn("ロール付与で一意制約違反（並行して在籍が確定）: scopeType={}, scopeId={}, userId={}",
                    scopeType, scopeId, userId);
            throw new BusinessException(TeamErrorCode.TEAM_003, HttpStatus.CONFLICT);
        }

        if (claim != null && inviteTokenRepository.markClaimGrantCommitted(claim.claimId()) != 1) {
            // claim 行はロック済みで CLAIMED・未付与を確かめているので起こりえない。付与ごと巻き戻す。
            throw new IllegalStateException("付与の元の claim に付与印を立てられない: claimId=" + claim.claimId());
        }

        logGranted(scopeType, scopeId, userId, grantRoleId, source);
        return GrantOutcome.GRANTED;
    }

    private Long memberRoleId() {
        return roleRepository.findByName(MEMBER_ROLE_NAME)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001)).getId();
    }

    private static GrantTarget memberTarget() {
        return InviteRolePolicy.grantTarget(MEMBER_ROLE_NAME).orElseThrow();
    }

    /** 付与ロールの書き先を決める。所属ロールにも権限ロールにも当たらなければ 422 ROLE_009（何も書かない）。 */
    private GrantTarget resolveGrantTarget(Long roleId) {
        String roleName = roleRepository.findById(roleId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001)).getName();
        return InviteRolePolicy.grantTarget(roleName)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_009, HttpStatus.UNPROCESSABLE_ENTITY));
    }

    private enum Mode {
        /** 招待承諾: ACTIVE 必須・在籍は user_roles ∪ memberships の現在読み・競合は業務エラーへ変換。 */
        INVITE,
        /** 参加申請承認: 現行維持（ACTIVE を問わず、memberships のスナップショット読み）。 */
        JOIN_REQUEST
    }

    private static ScopeType toScopeType(String scopeType) {
        return "TEAM".equals(scopeType) ? ScopeType.TEAM : ScopeType.ORGANIZATION;
    }

    private static void logSkipped(String scopeType, Long scopeId, Long userId, String source) {
        log.info("ロール付与をスキップ（既にメンバー・冪等）: scopeType={}, scopeId={}, userId={}, source={}",
                scopeType, scopeId, userId, source);
    }

    private static void logGranted(String scopeType, Long scopeId, Long userId, Long roleId, String source) {
        log.info("ロール付与・入会完了: scopeType={}, scopeId={}, userId={}, roleId={}, source={}",
                scopeType, scopeId, userId, roleId, source);
    }

    /** {@link InviteClaimGrantScope} の claim をロック付きで読む。範囲外なら空。claim が無ければ不変式違反。 */
    private Optional<InviteClaimLock> lockGrantingClaim() {
        Optional<UUID> claimId = InviteClaimGrantScope.current();
        if (claimId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(inviteTokenRepository.lockClaim(claimId.get())
                .orElseThrow(() -> new IllegalStateException("付与の元の claim が存在しない: claimId=" + claimId.get())));
    }

    private boolean isAlreadyJoined(Mode mode, ScopeType st, Long scopeId, Long userId) {
        if (mode == Mode.JOIN_REQUEST) {
            return membershipService.isActiveMember(userId, st, scopeId);
        }
        boolean hasUserRole = st == ScopeType.TEAM
                ? userRoleRepository.findByUserIdAndTeamIdForUpdate(userId, scopeId).isPresent()
                : userRoleRepository.findByUserIdAndOrganizationIdForUpdate(userId, scopeId).isPresent();
        return hasUserRole || membershipService.isActiveMemberForUpdate(userId, st, scopeId);
    }

    private void grant(String scopeType, Long scopeId, Long userId, Long roleId, GrantTarget target, Long grantedBy,
                       String source, boolean flushAtEnd) {
        if (target.writesUserRole()) {
            // 権限ロール（ADMIN / DEPUTY_ADMIN）と暫定の MEMBER だけ user_roles に割り当てる。SUPPORTER は作らない
            // （CMP-261124-0157）。
            var roleBuilder = UserRoleEntity.builder()
                    .userId(userId)
                    .roleId(roleId)
                    .grantedBy(grantedBy);
            if ("TEAM".equals(scopeType)) {
                roleBuilder.teamId(scopeId);
            } else {
                roleBuilder.organizationId(scopeId);
            }
            userRoleRepository.save(roleBuilder.build());
        }

        // F00.5 認可基盤: 所属は memberships が真実の源。SUPPORTER 招待は SUPPORTER、それ以外は MEMBER として入会させる。
        MembershipCreateRequest membershipReq = new MembershipCreateRequest();
        membershipReq.setUserId(userId);
        membershipReq.setScopeType("TEAM".equals(scopeType) ? ScopeType.TEAM : ScopeType.ORGANIZATION);
        membershipReq.setScopeId(scopeId);
        membershipReq.setRoleKind(target.roleKind());
        membershipReq.setInvitedBy(grantedBy);
        membershipReq.setSource(source);
        // memberships の合流点は判定結果だけを見る（membership の取引を role へ越境させない。D-3T）。
        // role の取引の中なので、ここで判定してから呼ぶ。
        PlatformAccountAffiliationPolicy.runJudged(
                platformAccountAffiliationPolicy.judge(List.of(userId)), () -> membershipService.join(membershipReq));

        if (flushAtEnd) {
            // 制約違反をこのメソッドの中で（呼び出し側のコミットを待たず）確定させる。flush は永続化コンテキスト全体に
            // 効くので、user_roles を書かない SUPPORTER の付与でも memberships の一意制約違反がここで出る。
            userRoleRepository.flush();
        }
    }
}
