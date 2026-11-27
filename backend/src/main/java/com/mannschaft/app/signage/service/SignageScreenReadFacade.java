package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ScopeContentAccessGate;
import com.mannschaft.app.signage.service.SignageScreenService.SignageScreenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 認証ユーザー向け画面詳細の認可入口。認可を業務TXの外で行うためTransactionalを付けない。
 * 自ドメインで存在確認→scalar scopeで既存ACL→業務TXで再取得の順とする。
 * TEAM会員の閲覧と、別入口でトークン認証する端末表示は変更しない。
 */
@Service
@RequiredArgsConstructor
public class SignageScreenReadFacade {

    private final SignageScreenService screenService;
    private final AccessControlService accessControlService;
    /** CMP-261124-0159: 中身の読み取りは共通の門で非公開チームの上限を課す（F00 §20.3）。 */
    private final ScopeContentAccessGate scopeContentAccessGate;

    /** 組織では管理者以上、TEAMでは従来どおり会員へ画面設定を返す。 */
    public SignageScreenResponse getScreenForActor(Long id, Long actor) {
        SignageScreenService.ScreenReadScope scope = screenService.resolveManagementReadScope(id);
        if ("ORGANIZATION".equals(scope.scopeType())) {
            accessControlService.checkAdminOrAbove(actor, scope.scopeId(), scope.scopeType());
        } else {
            scopeContentAccessGate.checkScopeContentAccess(actor, scope.scopeId(), scope.scopeType());
        }
        return screenService.getScreen(id);
    }
}
