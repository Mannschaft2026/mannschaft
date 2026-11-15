package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.effortrecord.dto.CreateEffortRecordRequest;
import com.mannschaft.app.effortrecord.dto.EffortDeleteResponse;
import com.mannschaft.app.effortrecord.dto.EffortOwnerRecordResponse;
import com.mannschaft.app.effortrecord.dto.EffortRecordListCriteria;
import com.mannschaft.app.effortrecord.dto.EffortRecordPage;
import com.mannschaft.app.effortrecord.dto.UpdateEffortRecordRequest;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * F06.4.1 個人原本の Owner Service（骨格）。全操作は認証主体 ownerUserId に束縛される。
 *
 * <p>骨格段階のため全メソッド未実装。他人・不在・削除済み・purged は EFFORT_001（404）で区別しない。</p>
 */
@Service
public class EffortRecordOwnerService {

    /** 原本を作成する（Idempotency-Key 必須）。TEAM 定義なら現在所属・同チーム定義を再検証する。 */
    public EffortOwnerRecordResponse create(Long ownerUserId, String idempotencyKey, CreateEffortRecordRequest request) {
        throw new UnsupportedOperationException("F06.4.1 未実装");
    }

    /** 本人の原本一覧（recordedOn DESC, createdAt DESC, id DESC のカーソルページング）。 */
    public EffortRecordPage list(Long ownerUserId, EffortRecordListCriteria criteria) {
        throw new UnsupportedOperationException("F06.4.1 未実装");
    }

    /** 本人の原本詳細。 */
    public EffortOwnerRecordResponse get(Long ownerUserId, UUID id) {
        throw new UnsupportedOperationException("F06.4.1 未実装");
    }

    /** 原本を全置換更新する（version 一致必須。不一致は COMMON_003 / 409）。 */
    public EffortOwnerRecordResponse update(Long ownerUserId, UUID id, UpdateEffortRecordRequest request) {
        throw new UnsupportedOperationException("F06.4.1 未実装");
    }

    /** 原本を論理削除する（Idempotency-Key 必須、version 一致必須）。 */
    public EffortDeleteResponse delete(Long ownerUserId, UUID id, long version, String idempotencyKey) {
        throw new UnsupportedOperationException("F06.4.1 未実装");
    }
}
