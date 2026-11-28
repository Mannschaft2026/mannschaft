package com.mannschaft.app.signage.websocket;

import com.mannschaft.app.common.websocket.StompErrorCode;
import com.mannschaft.app.common.websocket.StompRejectedException;
import com.mannschaft.app.signage.service.SignageAccessTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * デジタルサイネージ配信トピックの STOMP 購読認可インターセプタ（CMP-261124-0159・F00 §20.5.5）。
 *
 * <p>対象は {@code /topic/signage/} で始まる宛先すべて。配信元 {@link SignageWebSocketPublisher} が使う
 * {@code /topic/signage/{screenId}/emergency}・{@code /topic/signage/{screenId}/update} だけを購読でき、
 * それ以外の {@code /topic/signage/} 配下は宛先不正として拒否する（fail-closed）。</p>
 *
 * <h2>判定（すべて満たすときだけ許可）</h2>
 * <ol>
 *   <li>STOMP CONNECT のネイティブヘッダ {@value #TOKEN_HEADER} でサイネージのアクセストークンを受け取っていること
 *       （CONNECT 時にハッシュへ変換してセッション属性に保持する。平文はセッションに置かない）。</li>
 *   <li>そのトークンが今有効（存在・未失効・期限内）で、トークンの画面と購読先の画面が一致すること
 *       （別画面の購読＝画面間 IDOR を拒否。AC-0159-99）。</li>
 *   <li>画面の所属チームが非公開チーム（{@code MEMBERS_AND_ABOVE}）でないこと（AC-0159-26b）。</li>
 * </ol>
 * <p>判定は SUBSCRIBE のたびに {@link SignageAccessTokenService#canSubscribe} で DB を引き直す（失効・非公開化は次の購読から即時に効く）。
 * 拒否理由（トークン不在・失効・画面違い・非公開）は区別せず同一の {@code FORBIDDEN}（トークンなしだけは
 * {@code UNAUTHENTICATED}）を返し、理由から公開範囲の段やトークンの状態を推測させない。
 * 既に確立した購読の失効（非公開化コミット直後の再認可）は PR-WS の送出ガードが担う。</p>
 *
 * <p>登録は {@link com.mannschaft.app.config.WebSocketConfig} の inbound channel に、認証インターセプタの後段で行う。
 * STOMP のサブプロトコルハンドラとチャネルは {@code /ws} と {@code /ws/signage} で共有されるため、どちらの
 * エンドポイントから接続しても本インターセプタを通る。</p>
 */
@Component("signageSubscriptionInterceptor")
@RequiredArgsConstructor
@Slf4j
public class SignageSubscriptionInterceptor implements ChannelInterceptor {

    /** サイネージ端末が CONNECT フレームに載せるトークンのヘッダ名。 */
    public static final String TOKEN_HEADER = "signage-token";

    /** セッション属性に保持するトークンハッシュのキー。 */
    static final String SESSION_TOKEN_HASH = "signageTokenHash";

    private static final String SIGNAGE_PREFIX = "/topic/signage/";

    /** 配信元と一字一句一致する購読先。screenId は数値。 */
    private static final Pattern SIGNAGE_DESTINATION =
            Pattern.compile("^/topic/signage/(\\d+)/(emergency|update)$");

    private static final String DENIED_MESSAGE = "サイネージの購読権限がありません";

    private final SignageAccessTokenService tokenService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command) || StompCommand.STOMP.equals(command)) {
            rememberToken(accessor);
            return message;
        }
        if (!StompCommand.SUBSCRIBE.equals(command)) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(SIGNAGE_PREFIX)) {
            return message;
        }
        Matcher matcher = SIGNAGE_DESTINATION.matcher(destination);
        if (!matcher.matches()) {
            throw new StompRejectedException(StompErrorCode.INVALID_DESTINATION,
                    "サイネージの購読先が不正です: " + destination);
        }
        long screenId;
        try {
            screenId = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new StompRejectedException(StompErrorCode.INVALID_DESTINATION,
                    "サイネージの購読先が不正です: " + destination);
        }
        String tokenHash = tokenHash(accessor);
        if (tokenHash == null) {
            log.debug("サイネージ購読を拒否（トークンなし）: destination={}", destination);
            throw new StompRejectedException(StompErrorCode.UNAUTHENTICATED, DENIED_MESSAGE);
        }
        if (!tokenService.canSubscribe(tokenHash, screenId)) {
            log.debug("サイネージ購読を拒否: destination={}", destination);
            throw new StompRejectedException(StompErrorCode.FORBIDDEN, DENIED_MESSAGE);
        }
        return message;
    }

    private void rememberToken(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            return;
        }
        String token = accessor.getFirstNativeHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            attributes.remove(SESSION_TOKEN_HASH);
            return;
        }
        attributes.put(SESSION_TOKEN_HASH, tokenService.hashForSession(token));
    }

    private static String tokenHash(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            return null;
        }
        Object value = attributes.get(SESSION_TOKEN_HASH);
        return value instanceof String s ? s : null;
    }
}
