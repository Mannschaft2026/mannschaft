package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.entity.BulletinAttachmentEntity;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinAttachmentRepository;
import com.mannschaft.app.bulletin.repository.BulletinReplyRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.service.ContactAccessLevel;
import com.mannschaft.app.tournament.service.TournamentContactSpaceAccessGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 大会/ディビジョン連絡スペース（F08.7.1）の掲示板に対する、存在秘匿つきの認可門（CMP-261116-0726）。
 *
 * <p>閲覧権のない者（そのスペースを閲覧できない者）には、入口のリソース種別に応じた不在応答
 * （スコープ指定は {@code TOUR_029}、スレッド ID は {@code BULLETIN_002}、返信 ID は {@code BULLETIN_003}、
 * 添付 ID は {@code BULLETIN_007}、添付対象 ID は {@code BULLETIN_025}）を返し、不在 ID と status・code・message・
 * 本文まで一致させる。閲覧できるが投稿・管理できない者にだけ 403 {@code TOUR_031} を返す。
 * 大会スコープ以外のスコープ・スレッドでは何もしない（所属認可は各サービスが担う）。</p>
 *
 * <p>入口 ID の不在は本部品自身が同じ不在コードで投げる。サービス側の検証順（入力検証が先に走る等）の差で、
 * 秘匿された実在 ID と不在 ID の応答が食い違うのを防ぐためである。</p>
 *
 * <h2>tx 境界（ArchUnit D-3T）</h2>
 * <p>本部品は {@code @Transactional} を持たない。閲覧判定は他ドメイン（tournament / membership / role）の
 * Repository へ到達するため、<b>{@code @Transactional} なクラスから呼んではならない</b>。Controller と、tx を
 * 持たない {@link BulletinReadFacade} からだけ呼ぶ。tx 内の {@code TournamentContactAccessService#checkPost} は
 * 多層防御として各サービスに残している。</p>
 */
@Component
@RequiredArgsConstructor
public class BulletinTournamentScopeAccessGuard {

    /** スコープ指定 EP（一覧・作成・一括既読）の不在コード。 */
    private static final ErrorCode SCOPE_NOT_FOUND = TournamentErrorCode.CONTACT_SPACE_NOT_FOUND;

    private final TournamentContactSpaceAccessGate contactSpaceAccessGate;
    private final BulletinThreadRepository threadRepository;
    private final BulletinReplyRepository replyRepository;
    private final BulletinAttachmentRepository attachmentRepository;

    /**
     * 既読者一覧の参照水準。大会スコープのスレッドでのみ返す。
     *
     * @param canModerate 投稿・管理権（モデレーター相当）を持つか。{@code filter=unread} と、
     *                    既読プライバシーを無視した既読者参照の可否に使う
     */
    public record ReadersAccess(boolean canModerate) {
    }

    // ========================================================================
    // スコープ指定（一覧・作成・一括既読）
    // ========================================================================

    /**
     * 大会/ディビジョンスコープなら閲覧を要求する。閲覧権なし・不在は 404 {@code TOUR_029}。
     */
    public void checkListView(ScopeType scopeType, Long scopeId, Long userId) {
        checkScopeViewConcealed(scopeType, scopeId, userId, SCOPE_NOT_FOUND);
    }

    /**
     * 大会/ディビジョンスコープなら投稿を要求する。閲覧権なし・スペース不在は 404 {@code TOUR_029}、
     * 閲覧可・投稿権なしは 403 {@code TOUR_031}。
     */
    public void checkScopePost(ScopeType scopeType, Long scopeId, Long userId) {
        if (!isTournamentScope(scopeType)) {
            return;
        }
        contactSpaceAccessGate.requirePost(toContactScope(scopeType), scopeId, ContactSpaceKind.BULLETIN, userId,
                SCOPE_NOT_FOUND);
    }

    /**
     * 大会/ディビジョンスコープなら閲覧を要求し、拒否・不在は呼び出し側の入口に合わせた {@code notFound} にする
     * （{@link BulletinReadFacade} 用。逆引き済みのスコープを使い回し、追加の逆引きをしない）。
     */
    public void checkScopeViewConcealed(ScopeType scopeType, Long scopeId, Long userId, ErrorCode notFound) {
        if (!isTournamentScope(scopeType)) {
            return;
        }
        contactSpaceAccessGate.requireView(toContactScope(scopeType), scopeId, ContactSpaceKind.BULLETIN, userId,
                notFound);
    }

    // ========================================================================
    // スレッド ID 指定
    // ========================================================================

    /** スレッドの閲覧を要求する（既読化・返信一覧）。拒否・不在は 404 {@code BULLETIN_002}。 */
    public void checkThreadView(Long threadId, Long userId) {
        BulletinThreadEntity thread = findThreadOrThrow(threadId, BulletinErrorCode.THREAD_NOT_FOUND);
        if (!isTournamentScope(thread.getScopeType())) {
            return;
        }
        contactSpaceAccessGate.requireView(toContactScope(thread.getScopeType()), thread.getScopeId(),
                ContactSpaceKind.BULLETIN, userId, BulletinErrorCode.THREAD_NOT_FOUND);
    }

    /** スレッドへの投稿・管理を要求する（priority・pin・lock・archive・返信投稿）。 */
    public void checkThreadPost(Long threadId, Long userId) {
        BulletinThreadEntity thread = findThreadOrThrow(threadId, BulletinErrorCode.THREAD_NOT_FOUND);
        if (!isTournamentScope(thread.getScopeType())) {
            return;
        }
        contactSpaceAccessGate.requirePost(toContactScope(thread.getScopeType()), thread.getScopeId(),
                ContactSpaceKind.BULLETIN, userId, BulletinErrorCode.THREAD_NOT_FOUND);
    }

    /**
     * スレッドの更新・削除を要求する。投稿者本人は閲覧のみ要求し（資格を失った本人は不在応答・AC-16）、
     * 本人以外は投稿・管理権を要求する。
     */
    public void checkThreadAuthorOrPost(Long threadId, Long userId) {
        BulletinThreadEntity thread = findThreadOrThrow(threadId, BulletinErrorCode.THREAD_NOT_FOUND);
        if (!isTournamentScope(thread.getScopeType())) {
            return;
        }
        contactSpaceAccessGate.requireAuthorOrPost(toContactScope(thread.getScopeType()), thread.getScopeId(),
                ContactSpaceKind.BULLETIN, userId, isSameUser(thread.getAuthorId(), userId),
                BulletinErrorCode.THREAD_NOT_FOUND);
    }

    /**
     * 既読者一覧の閲覧を要求し、参照水準を返す（AC-15）。
     *
     * @return 大会スコープ以外のスレッドなら {@code null}（従来経路で認可する）。大会スコープなら参照水準
     */
    public ReadersAccess checkThreadReaders(Long threadId, Long userId) {
        BulletinThreadEntity thread = findThreadOrThrow(threadId, BulletinErrorCode.THREAD_NOT_FOUND);
        if (!isTournamentScope(thread.getScopeType())) {
            return null;
        }
        ContactAccessLevel level = contactSpaceAccessGate.requireViewResolvingLevel(
                toContactScope(thread.getScopeType()), thread.getScopeId(), ContactSpaceKind.BULLETIN, userId,
                BulletinErrorCode.THREAD_NOT_FOUND);
        return new ReadersAccess(level == ContactAccessLevel.POST);
    }

    // ========================================================================
    // 返信 ID 指定
    // ========================================================================

    /** 返信への返信の投稿を要求する。拒否・不在は 404 {@code BULLETIN_003}。 */
    public void checkReplyPost(Long replyId, Long userId) {
        BulletinReplyEntity reply = findReplyOrThrow(replyId);
        Optional<BulletinThreadEntity> thread = threadRepository.findById(reply.getThreadId());
        if (thread.isEmpty() || !isTournamentScope(thread.get().getScopeType())) {
            // 親スレッド欠落はサービスの不在応答に任せる（大会の存在は漏れない）
            return;
        }
        contactSpaceAccessGate.requirePost(toContactScope(thread.get().getScopeType()), thread.get().getScopeId(),
                ContactSpaceKind.BULLETIN, userId, BulletinErrorCode.REPLY_NOT_FOUND);
    }

    /**
     * 返信の更新・削除を要求する。投稿者本人は閲覧のみ要求し、本人以外は投稿・管理権を要求する。
     * 更新でスレッドがロック中なら、本人にも投稿・管理権を要求する（サービスの tx 内判定と同義）。
     *
     * @param update 更新なら true、削除なら false
     */
    public void checkReplyAuthorOrPost(Long replyId, Long userId, boolean update) {
        BulletinReplyEntity reply = findReplyOrThrow(replyId);
        Optional<BulletinThreadEntity> thread = threadRepository.findById(reply.getThreadId());
        if (thread.isEmpty() || !isTournamentScope(thread.get().getScopeType())) {
            return;
        }
        BulletinThreadEntity t = thread.get();
        boolean isAuthor = isSameUser(reply.getAuthorId(), userId);
        boolean lockedEdit = update && Boolean.TRUE.equals(t.getIsLocked());
        contactSpaceAccessGate.requireAuthorOrPost(toContactScope(t.getScopeType()), t.getScopeId(),
                ContactSpaceKind.BULLETIN, userId, isAuthor && !lockedEdit, BulletinErrorCode.REPLY_NOT_FOUND);
    }

    // ========================================================================
    // リアクション
    // ========================================================================

    /**
     * リアクション対象（スレッド/返信）の閲覧を要求する。拒否・不在は THREAD→{@code BULLETIN_002}、
     * REPLY→{@code BULLETIN_003}。
     *
     * <p>{@code targetType} が {@link TargetType} の定数名と一致しない場合は何もしない（サービスが入力エラーにする。
     * サービスも定数名の完全一致でしか解釈しないため、ここを素通りした値で大会スレッドへ到達することはない）。</p>
     */
    public void checkReactionTarget(String targetType, Long targetId, Long userId) {
        TargetType type = parseTargetType(targetType);
        if (type == null) {
            return;
        }
        BulletinThreadEntity thread;
        ErrorCode notFound;
        if (type == TargetType.THREAD) {
            notFound = BulletinErrorCode.THREAD_NOT_FOUND;
            thread = findThreadOrThrow(targetId, notFound);
        } else {
            notFound = BulletinErrorCode.REPLY_NOT_FOUND;
            BulletinReplyEntity reply = findReplyOrThrow(targetId);
            Optional<BulletinThreadEntity> parent = threadRepository.findById(reply.getThreadId());
            if (parent.isEmpty()) {
                return;
            }
            thread = parent.get();
        }
        if (!isTournamentScope(thread.getScopeType())) {
            return;
        }
        contactSpaceAccessGate.requireView(toContactScope(thread.getScopeType()), thread.getScopeId(),
                ContactSpaceKind.BULLETIN, userId, notFound);
    }

    // ========================================================================
    // 添付
    // ========================================================================

    /**
     * 添付の presign・確定（投稿）を要求する。対象（スレッド/返信）の拒否・不在は 404 {@code BULLETIN_025}、
     * 閲覧可・投稿権なしは 403 {@code TOUR_031}。対象の解決は {@code BulletinAttachmentService} と同じ。
     */
    public void checkAttachmentUpload(TargetType targetType, Long targetId, Long userId) {
        if (targetType == null || targetId == null) {
            return;
        }
        BulletinThreadEntity thread = resolveAttachmentTarget(targetType, targetId);
        if (!isTournamentScope(thread.getScopeType())) {
            return;
        }
        contactSpaceAccessGate.requirePost(toContactScope(thread.getScopeType()), thread.getScopeId(),
                ContactSpaceKind.BULLETIN, userId, BulletinErrorCode.ATTACHMENT_TARGET_NOT_FOUND);
    }

    /**
     * 添付の削除を要求する。添付の拒否・不在は 404 {@code BULLETIN_007}（添付対象の欠落は {@code BULLETIN_025}）。
     * 作成者本人は閲覧のみ要求し、本人以外は投稿・管理権を要求する。
     */
    public void checkAttachmentDelete(Long attachmentId, Long userId) {
        BulletinAttachmentEntity attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.ATTACHMENT_NOT_FOUND));
        BulletinThreadEntity thread = resolveAttachmentTarget(attachment.getTargetType(), attachment.getTargetId());
        if (!isTournamentScope(thread.getScopeType())) {
            return;
        }
        contactSpaceAccessGate.requireAuthorOrPost(toContactScope(thread.getScopeType()), thread.getScopeId(),
                ContactSpaceKind.BULLETIN, userId, isSameUser(attachment.getCreatedBy(), userId),
                BulletinErrorCode.ATTACHMENT_NOT_FOUND);
    }

    // ========================================================================
    // 共通
    // ========================================================================

    /** bulletin の scope_type が大会/ディビジョン連絡スペースか。 */
    public static boolean isTournamentScope(ScopeType scopeType) {
        return scopeType == ScopeType.TOURNAMENT || scopeType == ScopeType.TOURNAMENT_DIVISION;
    }

    private static ContactSpaceScopeType toContactScope(ScopeType scopeType) {
        return scopeType == ScopeType.TOURNAMENT
                ? ContactSpaceScopeType.TOURNAMENT
                : ContactSpaceScopeType.TOURNAMENT_DIVISION;
    }

    private static boolean isSameUser(Long ownerId, Long userId) {
        return userId != null && userId.equals(ownerId);
    }

    private static TargetType parseTargetType(String value) {
        for (TargetType type : TargetType.values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }

    private BulletinThreadEntity findThreadOrThrow(Long threadId, ErrorCode notFound) {
        return threadRepository.findById(threadId).orElseThrow(() -> new BusinessException(notFound));
    }

    private BulletinReplyEntity findReplyOrThrow(Long replyId) {
        return replyRepository.findById(replyId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.REPLY_NOT_FOUND));
    }

    /** 添付対象（スレッド/返信）から所属スレッドを逆引きする。欠落は {@code BULLETIN_025}。 */
    private BulletinThreadEntity resolveAttachmentTarget(TargetType targetType, Long targetId) {
        Long threadId = targetId;
        if (targetType == TargetType.REPLY) {
            threadId = replyRepository.findById(targetId)
                    .map(BulletinReplyEntity::getThreadId)
                    .orElseThrow(() -> new BusinessException(BulletinErrorCode.ATTACHMENT_TARGET_NOT_FOUND));
        }
        return findThreadOrThrow(threadId, BulletinErrorCode.ATTACHMENT_TARGET_NOT_FOUND);
    }
}
