package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.service.AuthOAuthService.LinkConsumption;
import com.mannschaft.app.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OAuth連携確認の入口（CMP-261012-0911）。<b>自身は @Transactional を持たない</b>。
 *
 * <p>連携確認は「連携の確定（auth のみの TX）」→「roles 解決（role ドメインの読み取り。TX の外）」→
 * 「トークン発行（auth のみの TX）」の 3 段で行う。roles 解決を TX の外へ出すことで、
 * {@code @Transactional} 入口から他ドメイン Repository へ推移的に到達しない（D-3T）。</p>
 *
 * <p>原子性: 連携確定の TX が commit した後にトークン発行が失敗しても、連携は確定済みで連携トークンは
 * 消費済みのままとなる。ただし 2FA 有効ユーザーは元々この時点でトークンを発行しない設計であり、
 * 利用者は通常の OAuth ログインで同じ連携を使って再ログインできる。権限（roles）の解決は
 * 読み取りのみで副作用がなく、連携確定と同一 TX で行う必然性はない。</p>
 */
@Service
@RequiredArgsConstructor
public class AuthOAuthLinkageConfirmService {

    private final AuthOAuthService authOAuthService;
    private final RoleClaimResolver roleClaimResolver;

    /**
     * OAuth連携を確認する。2FA 有効ユーザーには MFA チャレンジ、無効ユーザーにはトークンを返す。
     *
     * @param token     OAuth連携トークン（平文）
     * @param ipAddress リクエスト元IPアドレス
     * @param userAgent ユーザーエージェント
     * @return トークンレスポンスまたは MFA 要求レスポンス
     */
    public ApiResponse<?> confirmOAuthLinkage(String token, String ipAddress, String userAgent) {
        LinkConsumption linked = authOAuthService.consumeLinkToken(token);
        if (linked.challenge() != null) {
            return ApiResponse.of(linked.challenge());
        }
        List<String> roles = roleClaimResolver.resolveRoles(linked.userId());
        return ApiResponse.of(authOAuthService.issueLinkedLoginTokens(
                linked.userId(), linked.loginMethod(), roles, ipAddress, userAgent));
    }
}
