package com.mannschaft.app.role.service;

import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 再招待要請の通知先（招待権限者）を解決する（CMP-261107-1833 設計 §3・§9-2）。{@code @Transactional} を持たない。
 *
 * <p>通知先は当該スコープの ADMIN/DEPUTY_ADMIN（{@link RoleService#getAdminUserIdsByTeamId} 等）を
 * {@link InviteIssuerPolicy} で絞った集合（ADMIN、または INVITE_MEMBERS 権限を持つ DEPUTY_ADMIN）。
 * 一般 MEMBER や他テナントへは代替配送しない。</p>
 */
@Component
@RequiredArgsConstructor
public class InviteReissueRecipientResolver {

    /** 招待管理画面の再招待依頼の欄（CMP-261107-1833 PR3 設計 h）。 */
    static final String REISSUE_REQUESTS_PATH = "/invites#reissue-requests";

    private final RoleService roleService;
    private final InviteIssuerPolicy policy;
    private final TeamService teamService;
    private final OrganizationService organizationService;

    public List<Long> findInviterUserIds(String scopeType, Long scopeId) {
        List<Long> admins = "TEAM".equals(scopeType)
                ? roleService.getAdminUserIdsByTeamId(scopeId)
                : roleService.getAdminUserIdsByOrganizationId(scopeId);
        return admins.stream()
                .filter(userId -> policy.canIssue(userId, scopeId, scopeType))
                .toList();
    }

    /** 招待管理画面の再招待依頼の欄（{@code /teams/{slug}/invites#reissue-requests} 等）。招待先が引けなければ空。 */
    public Optional<String> findInviteManagementPath(String scopeType, Long scopeId) {
        if ("TEAM".equals(scopeType)) {
            return teamService.findJoinabilitySummary(scopeId)
                    .map(TeamService.JoinabilitySummary::slug)
                    .map(slug -> "/teams/" + slug + REISSUE_REQUESTS_PATH);
        }
        return organizationService.findJoinabilitySummary(scopeId)
                .map(OrganizationService.JoinabilitySummary::slug)
                .map(slug -> "/organizations/" + slug + REISSUE_REQUESTS_PATH);
    }
}
