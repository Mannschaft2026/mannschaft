package com.mannschaft.app.role;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.role.dto.CreateInviteTokenRequest;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.team.TeamErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261124-0159 PR-E: 非公開チーム（{@code MEMBERS_AND_ABOVE}）へのサポーター招待の関所（実 MySQL）。
 *
 * <p>F01.2 03「サポーター入口の拒否」・AC-0159-40 / 41 / 46 / 91。招待の発行・承諾は本番の入口
 * （{@code InviteService#createInviteToken}・{@code InviteAcceptanceFacade#joinByInvite}）をそのまま呼ぶ。
 * 公開チームの期待値は本戦役前（origin/main）の挙動（SUPPORTER 招待の発行・承諾が成功する）を固定する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 非公開チームへのサポーター招待の関所（AC-0159-40/41/46/91）")
class MembersOnlyTeamInviteEntryIT extends InviteAcceptanceItSupport {

    private static final String MEMBERS_ONLY = "MEMBERS_AND_ABOVE";
    private static final Pattern TEAMS_LOCK =
            Pattern.compile("\\bfrom\\s+teams\\b.*\\bfor\\s+(share|update)\\b", Pattern.DOTALL);

    private void setVisibility(Scope team, String visibility) {
        jdbc.update("UPDATE teams SET visibility = ? WHERE id = ?", visibility, team.id());
    }

    /** SUPPORTER ロールの ID（role 行の確保と後始末登録は別スコープの使い捨て利用者で行う）。 */
    private Long supporterRoleId() {
        return roleId(scope("TEAM"), "SUPPORTER", 5);
    }

    private long tokenCount(Scope team) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM invite_tokens WHERE team_id = ?", Long.class, team.id());
    }

    private long closureGeneration(Scope team) {
        return jdbc.queryForObject("SELECT closure_generation FROM teams WHERE id = ?", Long.class, team.id());
    }

    private record IssuedSupporterToken(Long id, String plaintext) { }

    /** 公開時に発行された有効な SUPPORTER 共有トークン（revoked_at は NULL のまま残す）。 */
    private IssuedSupporterToken supporterToken(Scope team, Long supporterRoleId) {
        String plain = UUID.randomUUID().toString();
        long generation = closureGeneration(team);
        InviteTokenEntity saved = inTx(() -> tokens.saveAndFlush(InviteTokenEntity.builder()
                .tokenHash(new SecretTokenVault().hash(plain))
                .roleId(supporterRoleId).teamId(team.id()).scopeClosureGeneration(generation)
                .usedCount(0).build()));
        return new IssuedSupporterToken(saved.getId(), plain);
    }

    private List<Claim> claimsOf(Long tokenId) {
        return claimsTableExists() ? claims(tokenId) : List.of();
    }

    private long grantMarkedClaims(Long tokenId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM invite_token_claims WHERE token_id = ? "
                + "AND grant_committed_at IS NOT NULL", Long.class, tokenId);
    }

    // ───────── AC-0159-40 発行 ─────────

    @Test
    @DisplayName("AC-0159-40: 非公開チームで ADMIN が SUPPORTER 招待を発行すると 403 TEAM_073・トークン行は作られない。MEMBER 招待は発行できる")
    void 非公開チームのSUPPORTER招待発行は拒否() {
        Scope team = scope("TEAM");
        Long admin = admin(team);
        Long supporterRole = supporterRoleId();
        setVisibility(team, MEMBERS_ONLY);

        BusinessException e = business(() -> inviteService.createInviteToken(team.id(), "TEAM",
                new CreateInviteTokenRequest(supporterRole, null, null), admin));

        assertError(e, TeamErrorCode.TEAM_073, null);
        assertThat(tokenCount(team)).as("SUPPORTER トークン行は作られない").isZero();

        var member = inviteService.createInviteToken(team.id(), "TEAM",
                new CreateInviteTokenRequest(team.memberRoleId(), null, null), admin).getData();
        assertThat(member.invite().revokedAt()).isNull();
        assertThat(tokenCount(team)).as("MEMBER トークンは発行される").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-0159-40/46: 公開チームでは SUPPORTER 招待も発行できる（本戦役前と同一）")
    void 公開チームのSUPPORTER招待発行は従来どおり() {
        Scope team = scope("TEAM");
        Long admin = admin(team);
        Long supporterRole = supporterRoleId();

        var issued = inviteService.createInviteToken(team.id(), "TEAM",
                new CreateInviteTokenRequest(supporterRole, null, null), admin).getData();

        assertThat(issued.invite().revokedAt()).isNull();
        assertThat(issued.invite().roleName()).isEqualTo("SUPPORTER");
        assertThat(tokenCount(team)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-0159-40: 招待権限の無い MEMBER・サポーター・部外者・他チームの ADMIN には TEAM_073 でなく従来の拒否（公開チームと同一）が返る")
    void 招待権限が無い者は従来の拒否() {
        Scope team = scope("TEAM");
        Scope otherTeam = scope("TEAM");
        Long supporterRole = supporterRoleId();
        Long member = user();
        insertMembership(team, member, "MEMBER");
        Long supporter = user();
        insertMembership(team, supporter, "SUPPORTER");
        Long outsider = user();
        Long otherTeamAdmin = admin(otherTeam);

        for (Long actor : List.of(member, supporter, outsider, otherTeamAdmin)) {
            setVisibility(team, "SUPPORTERS_AND_ABOVE");
            BusinessException publicDenial = business(() -> inviteService.createInviteToken(team.id(), "TEAM",
                    new CreateInviteTokenRequest(supporterRole, null, null), actor));
            setVisibility(team, MEMBERS_ONLY);
            BusinessException privateDenial = business(() -> inviteService.createInviteToken(team.id(), "TEAM",
                    new CreateInviteTokenRequest(supporterRole, null, null), actor));

            assertThat(privateDenial.getErrorCode()).as("actor=%s は TEAM_073 にならない", actor)
                    .isNotEqualTo(TeamErrorCode.TEAM_073)
                    .isEqualTo(publicDenial.getErrorCode());
            assertThat(privateDenial.getHttpStatusOverride()).isEqualTo(publicDenial.getHttpStatusOverride());
        }
        assertThat(tokenCount(team)).isZero();
    }

    // ───────── AC-0159-41 / 91 承諾 ─────────

    @Test
    @DisplayName("AC-0159-41/91: 公開時の SUPPORTER トークンで非公開チームに参加すると 403 TEAM_073。在籍・user_roles は作られず、claim は戻り（付与印なし）used_count は承諾前の値")
    void 非公開チームのSUPPORTER招待承諾は拒否() {
        Scope team = scope("TEAM");
        admin(team);
        IssuedSupporterToken token = supporterToken(team, supporterRoleId());
        setVisibility(team, MEMBERS_ONLY);
        Long joiner = user();
        int usedBefore = usedCount(token.id());

        BusinessException e = business(() -> accept(token.plaintext(), joiner));

        assertError(e, TeamErrorCode.TEAM_073, null);
        assertThat(membershipCount(joiner, team)).as("在籍は作られない").isZero();
        assertThat(userRoleCount(joiner, team)).as("user_roles は作られない").isZero();
        assertThat(usedCount(token.id())).as("used_count は承諾前の値に戻る").isEqualTo(usedBefore);
        assertThat(revokedAt(token.id())).as("招待そのものは失効させない（入口で拒否するだけ）").isNull();
        if (claimsTableExists()) {
            assertThat(claimsOf(token.id())).extracting(Claim::status).as("claim は戻る").containsOnly("RELEASED");
            assertThat(grantMarkedClaims(token.id())).as("付与印は立たない").isZero();
        }
    }

    @Test
    @DisplayName("AC-0159-91: 拒否は grant TX（claim のロックの後・在籍付与の前）で行い、claim TX はチーム行をロックしない（SQL の順序で検証）")
    void 拒否はgrantTXで行いclaimTXはteamsを取らない() {
        Scope team = scope("TEAM");
        admin(team);
        IssuedSupporterToken token = supporterToken(team, supporterRoleId());
        setVisibility(team, MEMBERS_ONLY);
        Long joiner = user();

        List<String> sqls;
        try (SqlIntentCounter.CurrentThreadCapture capture = SqlIntentCounter.captureCurrentThread()) {
            business(() -> accept(token.plaintext(), joiner));
            sqls = capture.capturedSqls().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        }

        int claimInsert = firstIndex(sqls, s -> s.contains("insert into invite_token_claims"));
        int claimLock = firstIndex(sqls, s -> s.contains("from invite_token_claims") && s.contains("for update")
                && s.contains("grant_committed_at"));
        List<Integer> teamLocks = IntStream.range(0, sqls.size())
                .filter(i -> TEAMS_LOCK.matcher(sqls.get(i)).find()).boxed().toList();

        assertThat(claimInsert).as("claim TX の確保記録の INSERT が捕捉される").isNotNegative();
        assertThat(claimLock).as("grant TX の claim ロックが捕捉される").isGreaterThan(claimInsert);
        assertThat(teamLocks).as("公開範囲の判定はチーム行のロック付き読み取りで行う").isNotEmpty();
        assertThat(teamLocks).as("claim TX（claim ロックより前）はチーム行をロックしない").allMatch(i -> i > claimLock);
        int membershipWrite = firstIndex(sqls, s -> s.contains("insert into memberships"));
        int userRoleWrite = firstIndex(sqls, s -> s.contains("insert into user_roles"));
        assertThat(membershipWrite).as("在籍は作られない").isNegative();
        assertThat(userRoleWrite).as("user_roles は作られない").isNegative();
        int firstScopeRowLock = firstIndex(sqls, s -> s.contains("for update")
                && (s.contains("from user_roles") || s.contains("from memberships")));
        if (firstScopeRowLock >= 0) {
            assertThat(teamLocks.get(0)).as("チーム行は user_roles・memberships のロックより前に取る").isLessThan(firstScopeRowLock);
        }
    }

    @Test
    @DisplayName("AC-0159-46: 公開チームでは SUPPORTER 招待の承諾で SUPPORTER として在籍し、used_count が進む（本戦役前と同一）")
    void 公開チームのSUPPORTER招待承諾は従来どおり() {
        Scope team = scope("TEAM");
        admin(team);
        IssuedSupporterToken token = supporterToken(team, supporterRoleId());
        Long joiner = user();

        accept(token.plaintext(), joiner);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id = ? AND scope_type = 'TEAM' "
                + "AND scope_id = ? AND role_kind = 'SUPPORTER' AND left_at IS NULL", Long.class, joiner, team.id()))
                .isEqualTo(1);
        assertThat(userRoleCount(joiner, team)).as("SUPPORTER は user_roles を作らない").isZero();
        assertThat(usedCount(token.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-0159-41: 非公開チームでも MEMBER 招待の承諾は従来どおり在籍を作る")
    void 非公開チームのMEMBER招待承諾は通る() {
        Scope team = scope("TEAM");
        admin(team);
        setVisibility(team, MEMBERS_ONLY);
        InviteTokenEntity token = token(team, closureGeneration(team));
        Long joiner = user();

        accept(plaintext(token), joiner);

        assertThat(membershipCount(joiner, team)).isEqualTo(1);
        assertThat(usedCount(token.getId())).isEqualTo(1);
    }

    private static int firstIndex(List<String> sqls, java.util.function.Predicate<String> predicate) {
        for (int i = 0; i < sqls.size(); i++) {
            if (predicate.test(sqls.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
