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
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.service.ContactAccessLevel;
import com.mannschaft.app.tournament.service.TournamentContactSpaceAccessGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 大会連絡スペース掲示板の存在秘匿つき認可門（CMP-261116-0726）。
 *
 * <p>入口のリソース種別に応じた不在コードをゲートへ渡すこと、本人短絡・ロック中編集・readers の参照水準、
 * 大会スコープ以外では何もしないことを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinTournamentScopeAccessGuard 大会連絡スペース掲示板の存在秘匿つき認可")
class BulletinTournamentScopeAccessGuardTest {

    private static final Long SCOPE_ID = 500L;
    private static final Long USER_ID = 10L;
    private static final Long OTHER_USER_ID = 11L;
    private static final Long THREAD_ID = 100L;
    private static final Long REPLY_ID = 200L;
    private static final Long ATTACHMENT_ID = 300L;

    @Mock private TournamentContactSpaceAccessGate gate;
    @Mock private BulletinThreadRepository threadRepository;
    @Mock private BulletinReplyRepository replyRepository;
    @Mock private BulletinAttachmentRepository attachmentRepository;
    @InjectMocks private BulletinTournamentScopeAccessGuard guard;

    private static BulletinThreadEntity thread(ScopeType scopeType, Long authorId, boolean locked) {
        return BulletinThreadEntity.builder()
                .scopeType(scopeType)
                .scopeId(SCOPE_ID)
                .authorId(authorId)
                .title("t")
                .body("b")
                .isLocked(locked)
                .build();
    }

    private static BulletinReplyEntity reply(Long authorId) {
        return BulletinReplyEntity.builder().threadId(THREAD_ID).authorId(authorId).body("r").build();
    }

    private void threadIs(BulletinThreadEntity thread) {
        given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
    }

    private void replyIs(BulletinReplyEntity reply) {
        given(replyRepository.findById(REPLY_ID)).willReturn(Optional.of(reply));
    }

    @Nested
    @DisplayName("スコープ指定")
    class Scope {

        @Test
        @DisplayName("一覧は大会の閲覧を TOUR_029 で要求する")
        void 一覧は閲覧をTOUR029で要求() {
            guard.checkListView(ScopeType.TOURNAMENT, SCOPE_ID, USER_ID);

            verify(gate).requireView(ContactSpaceScopeType.TOURNAMENT, SCOPE_ID, ContactSpaceKind.BULLETIN, USER_ID,
                    TournamentErrorCode.CONTACT_SPACE_NOT_FOUND);
        }

        @Test
        @DisplayName("ディビジョンの作成は投稿を TOUR_029 で要求する")
        void ディビジョン作成は投稿を要求() {
            guard.checkScopePost(ScopeType.TOURNAMENT_DIVISION, SCOPE_ID, USER_ID);

            verify(gate).requirePost(ContactSpaceScopeType.TOURNAMENT_DIVISION, SCOPE_ID, ContactSpaceKind.BULLETIN,
                    USER_ID, TournamentErrorCode.CONTACT_SPACE_NOT_FOUND);
        }

        @Test
        @DisplayName("ゲートの拒否はそのまま伝播する")
        void 拒否は伝播() {
            doThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND))
                    .when(gate).requireView(any(), any(), any(), any(), any());

            assertThatThrownBy(() -> guard.checkListView(ScopeType.TOURNAMENT, SCOPE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND);
        }

        @Test
        @DisplayName("大会以外のスコープでは何もしない")
        void 大会以外は何もしない() {
            guard.checkListView(ScopeType.TEAM, SCOPE_ID, USER_ID);
            guard.checkScopePost(ScopeType.ORGANIZATION, SCOPE_ID, USER_ID);
            guard.checkScopeViewConcealed(ScopeType.VILLAGE, 0L, USER_ID, BulletinErrorCode.THREAD_NOT_FOUND);

            verifyNoInteractions(gate);
        }
    }

    @Nested
    @DisplayName("スレッド ID 指定")
    class Thread {

        @Test
        @DisplayName("閲覧系は BULLETIN_002 で閲覧を要求する")
        void 閲覧はBULLETIN002() {
            threadIs(thread(ScopeType.TOURNAMENT, OTHER_USER_ID, false));

            guard.checkThreadView(THREAD_ID, USER_ID);

            verify(gate).requireView(ContactSpaceScopeType.TOURNAMENT, SCOPE_ID, ContactSpaceKind.BULLETIN, USER_ID,
                    BulletinErrorCode.THREAD_NOT_FOUND);
        }

        @Test
        @DisplayName("管理系は BULLETIN_002 で投稿を要求する")
        void 管理はBULLETIN002で投稿() {
            threadIs(thread(ScopeType.TOURNAMENT_DIVISION, OTHER_USER_ID, false));

            guard.checkThreadPost(THREAD_ID, USER_ID);

            verify(gate).requirePost(ContactSpaceScopeType.TOURNAMENT_DIVISION, SCOPE_ID, ContactSpaceKind.BULLETIN,
                    USER_ID, BulletinErrorCode.THREAD_NOT_FOUND);
        }

        @Test
        @DisplayName("更新・削除は投稿者本人なら isAuthor=true でゲートへ渡す")
        void 本人はisAuthor() {
            threadIs(thread(ScopeType.TOURNAMENT, USER_ID, false));

            guard.checkThreadAuthorOrPost(THREAD_ID, USER_ID);

            verify(gate).requireAuthorOrPost(ContactSpaceScopeType.TOURNAMENT, SCOPE_ID, ContactSpaceKind.BULLETIN,
                    USER_ID, true, BulletinErrorCode.THREAD_NOT_FOUND);
        }

        @Test
        @DisplayName("未ログインは本人扱いしない")
        void 未ログインは本人でない() {
            threadIs(thread(ScopeType.TOURNAMENT, null, false));

            guard.checkThreadAuthorOrPost(THREAD_ID, null);

            verify(gate).requireAuthorOrPost(ContactSpaceScopeType.TOURNAMENT, SCOPE_ID, ContactSpaceKind.BULLETIN,
                    null, false, BulletinErrorCode.THREAD_NOT_FOUND);
        }

        @Test
        @DisplayName("不在スレッドは大会以外でも BULLETIN_002 を投げ、ゲートを呼ばない")
        void 不在はBULLETIN002() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.checkThreadPost(THREAD_ID, USER_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.THREAD_NOT_FOUND);
            verifyNoInteractions(gate);
        }

        @Test
        @DisplayName("大会以外のスレッドではゲートを呼ばない")
        void 大会以外は何もしない() {
            threadIs(thread(ScopeType.TEAM, OTHER_USER_ID, false));

            guard.checkThreadView(THREAD_ID, USER_ID);
            guard.checkThreadAuthorOrPost(THREAD_ID, USER_ID);

            verifyNoInteractions(gate);
        }

        @Test
        @DisplayName("readers は投稿権ありなら canModerate=true、閲覧のみなら false、大会以外は null")
        void readersの参照水準() {
            threadIs(thread(ScopeType.TOURNAMENT, OTHER_USER_ID, false));
            given(gate.requireViewResolvingLevel(ContactSpaceScopeType.TOURNAMENT, SCOPE_ID, ContactSpaceKind.BULLETIN,
                    USER_ID, BulletinErrorCode.THREAD_NOT_FOUND)).willReturn(ContactAccessLevel.POST);
            given(gate.requireViewResolvingLevel(ContactSpaceScopeType.TOURNAMENT, SCOPE_ID, ContactSpaceKind.BULLETIN,
                    OTHER_USER_ID, BulletinErrorCode.THREAD_NOT_FOUND)).willReturn(ContactAccessLevel.VIEW);

            assertThat(guard.checkThreadReaders(THREAD_ID, USER_ID).canModerate()).isTrue();
            assertThat(guard.checkThreadReaders(THREAD_ID, OTHER_USER_ID).canModerate()).isFalse();
        }

        @Test
        @DisplayName("readers は大会以外のスレッドなら null（従来経路）")
        void readers大会以外はnull() {
            threadIs(thread(ScopeType.ORGANIZATION, OTHER_USER_ID, false));

            assertThat(guard.checkThreadReaders(THREAD_ID, USER_ID)).isNull();
            verifyNoInteractions(gate);
        }
    }

    @Nested
    @DisplayName("返信 ID 指定")
    class Reply {

        @Test
        @DisplayName("返信への返信は BULLETIN_003 で投稿を要求する")
        void ネスト返信はBULLETIN003で投稿() {
            replyIs(reply(OTHER_USER_ID));
            threadIs(thread(ScopeType.TOURNAMENT, OTHER_USER_ID, false));

            guard.checkReplyPost(REPLY_ID, USER_ID);

            verify(gate).requirePost(ContactSpaceScopeType.TOURNAMENT, SCOPE_ID, ContactSpaceKind.BULLETIN, USER_ID,
                    BulletinErrorCode.REPLY_NOT_FOUND);
        }

        @Test
        @DisplayName("本人の更新は閲覧のみ要求する（isAuthor=true）")
        void 本人の更新() {
            replyIs(reply(USER_ID));
            threadIs(thread(ScopeType.TOURNAMENT, OTHER_USER_ID, false));

            guard.checkReplyAuthorOrPost(REPLY_ID, USER_ID, true);

            verify(gate).requireAuthorOrPost(ContactSpaceScopeType.TOURNAMENT, SCOPE_ID, ContactSpaceKind.BULLETIN,
                    USER_ID, true, BulletinErrorCode.REPLY_NOT_FOUND);
        }

        @Test
        @DisplayName("ロック中スレッドでの本人の更新は投稿権を要求する（isAuthor=false）")
        void ロック中の本人更新() {
            replyIs(reply(USER_ID));
            threadIs(thread(ScopeType.TOURNAMENT, OTHER_USER_ID, true));

            guard.checkReplyAuthorOrPost(REPLY_ID, USER_ID, true);

            verify(gate).requireAuthorOrPost(ContactSpaceScopeType.TOURNAMENT, SCOPE_ID, ContactSpaceKind.BULLETIN,
                    USER_ID, false, BulletinErrorCode.REPLY_NOT_FOUND);
        }

        @Test
        @DisplayName("ロック中スレッドでも本人の削除は閲覧のみ要求する")
        void ロック中の本人削除() {
            replyIs(reply(USER_ID));
            threadIs(thread(ScopeType.TOURNAMENT, OTHER_USER_ID, true));

            guard.checkReplyAuthorOrPost(REPLY_ID, USER_ID, false);

            verify(gate).requireAuthorOrPost(ContactSpaceScopeType.TOURNAMENT, SCOPE_ID, ContactSpaceKind.BULLETIN,
                    USER_ID, true, BulletinErrorCode.REPLY_NOT_FOUND);
        }

        @Test
        @DisplayName("不在返信は BULLETIN_003")
        void 不在はBULLETIN003() {
            given(replyRepository.findById(REPLY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.checkReplyAuthorOrPost(REPLY_ID, USER_ID, false))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.REPLY_NOT_FOUND);
            verifyNoInteractions(gate);
        }

        @Test
        @DisplayName("親スレッド欠落でも返信への返信・更新・削除は不在返信と同じ BULLETIN_003（存在オラクル解消）")
        void 親欠落はBULLETIN003() {
            replyIs(reply(USER_ID));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.checkReplyPost(REPLY_ID, USER_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.REPLY_NOT_FOUND);
            assertThatThrownBy(() -> guard.checkReplyAuthorOrPost(REPLY_ID, USER_ID, true))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.REPLY_NOT_FOUND);
            assertThatThrownBy(() -> guard.checkReplyAuthorOrPost(REPLY_ID, USER_ID, false))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.REPLY_NOT_FOUND);
            verifyNoInteractions(gate);
        }
    }

    @Nested
    @DisplayName("リアクション")
    class Reaction {

        @Test
        @DisplayName("THREAD は BULLETIN_002、REPLY は BULLETIN_003 で閲覧を要求する")
        void 種別ごとの不在コード() {
            threadIs(thread(ScopeType.TOURNAMENT, OTHER_USER_ID, false));
            replyIs(reply(OTHER_USER_ID));

            guard.checkReactionTarget("THREAD", THREAD_ID, USER_ID);
            guard.checkReactionTarget("REPLY", REPLY_ID, USER_ID);

            verify(gate).requireView(ContactSpaceScopeType.TOURNAMENT, SCOPE_ID, ContactSpaceKind.BULLETIN, USER_ID,
                    BulletinErrorCode.THREAD_NOT_FOUND);
            verify(gate).requireView(ContactSpaceScopeType.TOURNAMENT, SCOPE_ID, ContactSpaceKind.BULLETIN, USER_ID,
                    BulletinErrorCode.REPLY_NOT_FOUND);
        }

        @Test
        @DisplayName("REPLY 対象の親スレッド欠落は不在返信と同じ BULLETIN_003")
        void 返信の親欠落はBULLETIN003() {
            replyIs(reply(USER_ID));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.checkReactionTarget("REPLY", REPLY_ID, USER_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.REPLY_NOT_FOUND);
            verifyNoInteractions(gate);
        }

        @Test
        @DisplayName("定数名と一致しない targetType は何もしない（サービスが入力エラーにする）")
        void 未知の種別は素通し() {
            guard.checkReactionTarget("thread", THREAD_ID, USER_ID);
            guard.checkReactionTarget(null, THREAD_ID, USER_ID);

            verifyNoInteractions(gate, threadRepository, replyRepository);
        }
    }

    @Nested
    @DisplayName("添付")
    class Attachment {

        @Test
        @DisplayName("返信への添付投稿は BULLETIN_025 で投稿を要求する")
        void 添付投稿はBULLETIN025() {
            replyIs(reply(OTHER_USER_ID));
            threadIs(thread(ScopeType.TOURNAMENT, OTHER_USER_ID, false));

            guard.checkAttachmentUpload(TargetType.REPLY, REPLY_ID, USER_ID);

            verify(gate).requirePost(ContactSpaceScopeType.TOURNAMENT, SCOPE_ID, ContactSpaceKind.BULLETIN, USER_ID,
                    BulletinErrorCode.ATTACHMENT_TARGET_NOT_FOUND);
        }

        @Test
        @DisplayName("添付対象の不在は BULLETIN_025")
        void 対象不在はBULLETIN025() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.checkAttachmentUpload(TargetType.THREAD, THREAD_ID, USER_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.ATTACHMENT_TARGET_NOT_FOUND);
        }

        @Test
        @DisplayName("添付削除は作成者本人なら isAuthor=true、BULLETIN_007 で要求する")
        void 添付削除は本人短絡() {
            BulletinAttachmentEntity attachment = BulletinAttachmentEntity.builder()
                    .targetType(TargetType.THREAD).targetId(THREAD_ID).createdBy(USER_ID).build();
            given(attachmentRepository.findById(ATTACHMENT_ID)).willReturn(Optional.of(attachment));
            threadIs(thread(ScopeType.TOURNAMENT, OTHER_USER_ID, false));

            guard.checkAttachmentDelete(ATTACHMENT_ID, USER_ID);

            verify(gate).requireAuthorOrPost(eq(ContactSpaceScopeType.TOURNAMENT), eq(SCOPE_ID),
                    eq(ContactSpaceKind.BULLETIN), eq(USER_ID), eq(true), eq(BulletinErrorCode.ATTACHMENT_NOT_FOUND));
        }

        @Test
        @DisplayName("添付削除は添付対象（スレッド/返信の親）が欠落しても BULLETIN_007")
        void 対象欠落でも添付削除はBULLETIN007() {
            BulletinAttachmentEntity onThread = BulletinAttachmentEntity.builder()
                    .targetType(TargetType.THREAD).targetId(THREAD_ID).createdBy(USER_ID).build();
            given(attachmentRepository.findById(ATTACHMENT_ID)).willReturn(Optional.of(onThread));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.checkAttachmentDelete(ATTACHMENT_ID, USER_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.ATTACHMENT_NOT_FOUND);

            BulletinAttachmentEntity onReply = BulletinAttachmentEntity.builder()
                    .targetType(TargetType.REPLY).targetId(REPLY_ID).createdBy(USER_ID).build();
            given(attachmentRepository.findById(301L)).willReturn(Optional.of(onReply));
            given(replyRepository.findById(REPLY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.checkAttachmentDelete(301L, USER_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.ATTACHMENT_NOT_FOUND);
            verifyNoInteractions(gate);
        }

        @Test
        @DisplayName("不在添付は BULLETIN_007")
        void 不在添付はBULLETIN007() {
            given(attachmentRepository.findById(ATTACHMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.checkAttachmentDelete(ATTACHMENT_ID, USER_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.ATTACHMENT_NOT_FOUND);
            verifyNoInteractions(gate);
        }
    }
}
