package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.effortrecord.dto.EffortTemplateSettingsResponse;
import com.mannschaft.app.effortrecord.dto.UpdateEffortTemplateSettingsRequest;
import org.springframework.stereotype.Service;

/**
 * F06.4.1 チームテンプレートの「個人の頑張り記録の指標」設定（API 契約 §7、AC-21/22）。
 *
 * <p>試練骨格（PR-2）。業務ロジックは出陣で実装する。契約:</p>
 * <ul>
 *   <li>認可は既存 TEAM テンプレート管理権限（ADMIN / DEPUTY_ADMIN）＋ template の scope 一致。
 *       MEMBER・非所属は 403 COMMON_002、path の team に属さない template は 404 EFFORT_001。</li>
 *   <li>保存先は effort_definition_revisions.definition_json。activity テンプレートの既存列へ属性を足さない。</li>
 *   <li>全 field の設定必須。未知 key / 重複 / 不互換 semantic / duration・subject 二重指定は 400、version 競合 409。</li>
 * </ul>
 */
@Service
public class EffortTemplateSettingsService {

    /** 現在の指標設定を返す。 */
    public EffortTemplateSettingsResponse get(Long userId, Long teamId, Long templateId) {
        throw new UnsupportedOperationException("F06.4.1 PR-2 試練骨格: template-settings GET は未実装");
    }

    /** 指標設定を全置換で更新する。 */
    public EffortTemplateSettingsResponse update(Long userId, Long teamId, Long templateId,
                                                 UpdateEffortTemplateSettingsRequest request) {
        throw new UnsupportedOperationException("F06.4.1 PR-2 試練骨格: template-settings PUT は未実装");
    }
}
