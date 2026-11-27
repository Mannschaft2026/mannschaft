package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.AttachmentDownloadUrlResponse;
import com.mannschaft.app.bulletin.dto.AttachmentResponse;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.visibility.BulletinThreadVisibilityResolver;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 関連詳細/添付の最新 F00 と準備中判定を leaf の読取 TX の外で行う。
 * 旧来の所属認可と他 scope の閲覧認可は leaf に残し、作成・更新・一覧を変更しない。
 * 大会/ディビジョン連絡スペースの閲覧判定は leaf から撤去し、本 Facade が逆引き済みのスコープで行う
 * （tx 外・存在秘匿つき。CMP-261116-0726）。
 */
@Service
@RequiredArgsConstructor
public class BulletinReadFacade {
    private final BulletinThreadService threadService;
    private final BulletinAttachmentService attachmentService;
    private final BulletinAccessGuard accessGuard;
    private final ContentVisibilityChecker visibilityChecker;
    private final TeamService teamService;
    private final OrganizationService organizationService;
    private final BulletinThreadVisibilityResolver threadVisibilityResolver;
    private final BulletinAnnouncementPreviewReader announcementPreviewReader;
    /** 大会/ディビジョン連絡スペースの閲覧判定（tx 外・存在秘匿つき。CMP-261116-0726）。 */
    private final BulletinTournamentScopeAccessGuard tournamentScopeAccessGuard;

    public ThreadResponse getThread(ScopeType type, Long scopeId, Long threadId, Long viewer) {
        var metadata = threadService.getReadMetadata(threadId);
        if (!metadata.scopeType().equals(type.name()) || !Objects.equals(metadata.scopeId(), scopeId)) throw notFound();
        assertReadable(metadata, viewer);
        return threadService.getThread(type, scopeId, threadId, viewer);
    }

    public ThreadResponse getThreadGlobal(Long threadId, Long viewer) {
        var metadata = threadService.getReadMetadata(threadId);
        assertTournamentReadable(metadata, viewer, BulletinErrorCode.THREAD_NOT_FOUND);
        assertReadable(metadata, viewer);
        return threadService.getThreadGlobal(threadId, viewer);
    }

    public BulletinThreadService.PreviewMetadata getPreviewMetadata(Long threadId, Long viewer) {
        var metadata = threadService.getReadMetadata(threadId);
        if (!isManaged(metadata)) throw notFound();
        assertReadable(metadata, viewer);
        return metadata;
    }

    /**
     * 組織告知の本文プレビューの委任版（宛先チームのメンバーへの委任。ALIC-1 §2.3）。組織の掲示板への所属チェックは行わず、
     * 所有スコープでの役割を委任役割（"MEMBER" / "SUPPORTER"）に差し替えて F00 を評価する。準備中の組織・チームは 404。
     * 呼び出しは {@code AnnouncementPreviewSourceService} に限る（ArchUnit F02）。通常の閲覧 API へ流用しない。
     */
    public BulletinThreadService.PreviewMetadata getPreviewMetadataDelegated(Long threadId, Long viewer,
                                                                            String delegatedRole) {
        var metadata = threadService.getReadMetadata(threadId);
        if (!isManaged(metadata)) throw notFound();
        assertReadableDelegated(metadata, viewer, delegatedRole);
        return metadata;
    }

    /** {@link #getPreviewMetadataDelegated} で認可した上で本文を返す。添付は返さない（組織のメンバーだけが開ける）。 */
    public ThreadResponse getThreadDelegated(ScopeType type, Long scopeId, Long threadId, Long viewer,
                                             String delegatedRole) {
        var metadata = threadService.getReadMetadata(threadId);
        if (!isManaged(metadata) || !metadata.scopeType().equals(type.name())
                || !Objects.equals(metadata.scopeId(), scopeId)) throw notFound();
        assertReadableDelegated(metadata, viewer, delegatedRole);
        return announcementPreviewReader.readThread(type, scopeId, threadId, viewer);
    }

    public List<AttachmentResponse> listThreadAttachments(Long threadId, Long viewer) {
        var metadata = threadService.getReadMetadata(threadId);
        assertTournamentReadable(metadata, viewer, BulletinErrorCode.THREAD_NOT_FOUND);
        assertReadable(metadata, viewer);
        return attachmentService.listThreadAttachments(threadId, viewer);
    }

    public List<AttachmentResponse> listReplyAttachments(Long replyId, Long viewer) {
        var metadata = attachmentService.getReplyReadMetadata(replyId);
        assertTournamentReadable(metadata, viewer, BulletinErrorCode.ATTACHMENT_TARGET_NOT_FOUND);
        assertReadable(metadata, viewer);
        return attachmentService.listReplyAttachments(replyId, viewer);
    }

    public AttachmentDownloadUrlResponse generateDownloadUrl(Long attachmentId, Long viewer) {
        var metadata = attachmentService.getAttachmentReadMetadata(attachmentId);
        assertTournamentReadable(metadata, viewer, BulletinErrorCode.ATTACHMENT_NOT_FOUND);
        assertReadable(metadata, viewer);
        return attachmentService.generateDownloadUrl(attachmentId, viewer);
    }

    /**
     * 大会/ディビジョン連絡スペースのスレッドなら閲覧を要求する（CMP-261116-0726）。閲覧権なしは入口の不在コード
     * （{@code notFound}）と同一応答にする。leaf の読取 tx は大会の閲覧判定を持たないため、ここが唯一の閲覧門である。
     */
    private void assertTournamentReadable(BulletinThreadService.PreviewMetadata metadata, Long viewer,
                                          BulletinErrorCode notFound) {
        tournamentScopeAccessGuard.checkScopeViewConcealed(
                ScopeType.valueOf(metadata.scopeType()), metadata.scopeId(), viewer, notFound);
    }

    private void assertReadable(BulletinThreadService.PreviewMetadata metadata, Long viewer) {
        if (!isManaged(metadata)) return;
        ScopeType type = ScopeType.valueOf(metadata.scopeType());
        try {
            accessGuard.checkScopeContentAccess(viewer, type, metadata.scopeId());
            if ((type == ScopeType.TEAM && teamService.isProvisioned(metadata.scopeId()))
                    || (type == ScopeType.ORGANIZATION && organizationService.isProvisioned(metadata.scopeId()))) throw notFound();
            visibilityChecker.assertCanView(ReferenceType.BULLETIN_THREAD, metadata.threadId(), viewer);
        } catch (BusinessException e) {
            String code = e.getErrorCode().getCode();
            if (code.equals("COMMON_002") || code.equals("VISIBILITY_001") || code.equals("VISIBILITY_004")) throw notFound();
            throw e;
        }
    }

    private void assertReadableDelegated(BulletinThreadService.PreviewMetadata metadata, Long viewer,
                                         String delegatedRole) {
        ScopeType type = ScopeType.valueOf(metadata.scopeType());
        try {
            if ((type == ScopeType.TEAM && teamService.isProvisioned(metadata.scopeId()))
                    || (type == ScopeType.ORGANIZATION && organizationService.isProvisioned(metadata.scopeId()))) throw notFound();
            threadVisibilityResolver.assertCanViewDelegated(metadata.threadId(), viewer, delegatedRole);
        } catch (BusinessException e) {
            String code = e.getErrorCode().getCode();
            if (code.equals("COMMON_002") || code.equals("VISIBILITY_001") || code.equals("VISIBILITY_004")) throw notFound();
            throw e;
        }
    }

    private static boolean isManaged(BulletinThreadService.PreviewMetadata metadata) {
        return "TEAM".equals(metadata.scopeType()) || "ORGANIZATION".equals(metadata.scopeType());
    }
    private static BusinessException notFound() {
        return new BusinessException(BulletinErrorCode.THREAD_NOT_FOUND);
    }
}
