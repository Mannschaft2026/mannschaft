package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftDetailResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftSaveRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftSummaryResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftUpdateRequest;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * CMP-261126-1558: 確認通知の下書きサービス。
 *
 * <p>スコープ×作成者ごとに上限10件（11件目は {@code DRAFT_LIMIT_EXCEEDED}・409）。
 * 更新は {@code baseVersion} による楽観ロック（不一致は {@code DRAFT_VERSION_CONFLICT}・409）。
 * 他人・他スコープの draftId は存在しない ID と同じく {@code DRAFT_NOT_FOUND}（404）。</p>
 *
 * <p>試練（red）段階の骨格。本体は出陣で実装する。</p>
 */
@Service
@RequiredArgsConstructor
public class ConfirmableNotificationDraftService {

    /** スコープ×作成者ごとの下書きの上限件数。 */
    public static final int MAX_DRAFTS_PER_SCOPE_AND_USER = 10;

    /** 一覧に出す本文の冒頭の文字数。 */
    public static final int BODY_EXCERPT_LENGTH = 100;

    private final ConfirmableNotificationDraftRepository draftRepository;

    public List<ConfirmableNotificationDraftSummaryResponse> list(ScopeType scopeType, Long scopeId, Long userId) {
        throw new UnsupportedOperationException("CMP-261126-1558: 未実装（試練）");
    }

    public ConfirmableNotificationDraftDetailResponse create(
            ScopeType scopeType, Long scopeId, Long userId, ConfirmableNotificationDraftSaveRequest request) {
        throw new UnsupportedOperationException("CMP-261126-1558: 未実装（試練）");
    }

    public ConfirmableNotificationDraftDetailResponse get(
            ScopeType scopeType, Long scopeId, Long userId, UUID draftId) {
        throw new UnsupportedOperationException("CMP-261126-1558: 未実装（試練）");
    }

    public ConfirmableNotificationDraftDetailResponse update(
            ScopeType scopeType, Long scopeId, Long userId, UUID draftId,
            ConfirmableNotificationDraftUpdateRequest request) {
        throw new UnsupportedOperationException("CMP-261126-1558: 未実装（試練）");
    }

    public void delete(ScopeType scopeType, Long scopeId, Long userId, UUID draftId) {
        throw new UnsupportedOperationException("CMP-261126-1558: 未実装（試練）");
    }
}
