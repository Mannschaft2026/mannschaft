package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.effortrecord.dto.CreateEffortDefinitionSnapshotRequest;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshotResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * F06.4.1 定義 Service（骨格）。標準プリセットの取得と、チーム定義の不変 revision 登録を担う。
 *
 * <p>骨格段階のため全メソッド未実装。後続の出陣で実装する。</p>
 */
@Service
public class EffortDefinitionService {

    /** 標準プリセット 3 種（STUDY_V1 / EXERCISE_V1 / WEIGHT_V1）を返す。所属不要。 */
    public List<EffortDefinitionSnapshot> listPresets() {
        throw new UnsupportedOperationException("F06.4.1 未実装");
    }

    /**
     * チーム定義の snapshot revision を登録（同一 hash なら増やさない）し、署名 token を発行する。
     * 現在所属＋TEAM template の scopeId 一致が必須。廃止は 409。原本は作らない。
     */
    public EffortDefinitionSnapshotResponse registerTeamSnapshot(
            Long userId, CreateEffortDefinitionSnapshotRequest request) {
        throw new UnsupportedOperationException("F06.4.1 未実装");
    }
}
