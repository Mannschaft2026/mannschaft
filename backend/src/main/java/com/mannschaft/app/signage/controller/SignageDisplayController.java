package com.mannschaft.app.signage.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.TrustedProxyClientIpResolver;
import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import com.mannschaft.app.signage.service.SignageAccessTokenService;
import com.mannschaft.app.signage.service.SignageScreenService.SignageScreenResponse;
import com.mannschaft.app.signage.service.SignageScreenService;
import com.mannschaft.app.signage.service.SignageSlotService.SignageSlotResponse;
import com.mannschaft.app.signage.service.SignageSlotService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.security.AuthorizedInService;

import java.util.List;

/**
 * デジタルサイネージ 表示用コントローラー。
 * サイネージ端末がトークンを使ってコンテンツ設定を取得するエンドポイントを提供する。
 * このエンドポイントは認証不要（トークンが認証の代わり）。
 */
@RestController
@RequiredArgsConstructor
public class SignageDisplayController {

    private final SignageAccessTokenService tokenService;
    private final SignageScreenService screenService;
    private final SignageSlotService slotService;
    private final TrustedProxyClientIpResolver clientIpResolver;

    /**
     * 表示設定レスポンス DTO。
     * 画面情報とスロット一覧をまとめて返す。
     */
    public record SignageDisplayResponse(
            SignageScreenResponse screen,
            List<SignageSlotResponse> slots
    ) {}

    /**
     * サイネージ端末向けの表示設定を取得する。
     * トークンを検証し、画面情報とスロット一覧を返す。
     * 認可: 不要（トークンが認証の代わり）
     * レスポンス: 200 OK
     *
     * @param token   サイネージアクセストークン文字列
     * @param request 送信元 IP（許可IP照合用）を取り出す HTTP リクエスト
     * @return 画面情報+スロット一覧
     */
    // capability トークン方式: SignageAccessTokenService#validateToken(token) がトークンの有効性・
    // 有効期限・許可IPを検証し、無効・期限切れ・許可外IPは BusinessException で拒否する。トークン自体が認可を代替する
    // ため、SecurityConfig の permitAll 登録は不要（AuthorizedInService javadoc の capability トークン区分）。
    @AuthorizedInService
    @GetMapping("/signage/{token}")
    public ApiResponse<SignageDisplayResponse> getDisplayConfig(@PathVariable String token,
                                                                HttpServletRequest request) {
        // トークンを検証する（無効・期限切れ・許可外IPの場合は BusinessException をスロー）。
        // 送信元 IP は信頼プロキシ設定に基づいて決める（X-Forwarded-For の左端は詐称できるため使わない）
        SignageAccessTokenEntity tokenEntity =
                tokenService.validateToken(token, clientIpResolver.resolve(request));

        // トークンに紐づく画面IDで画面情報を取得する
        Long screenId = tokenEntity.getScreenId();
        SignageScreenResponse screen = screenService.getScreen(screenId);

        // 画面に紐づくスロット一覧を表示順昇順で取得する
        List<SignageSlotResponse> slots = slotService.listSlots(screenId);

        return ApiResponse.of(new SignageDisplayResponse(screen, slots));
    }
}
