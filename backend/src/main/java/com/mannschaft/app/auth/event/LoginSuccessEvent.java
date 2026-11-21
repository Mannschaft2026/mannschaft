package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ログイン成功イベント。監査ログ記録等の後続処理をトリガーする。
 */
@Getter
public class LoginSuccessEvent extends BaseEvent {

    private final Long userId;
    private final String ipAddress;
    private final String userAgent;
    private final String method;
    /** SHA-256(refresh_token_jti)。監査ログの session_hash に記録する。 */
    private final String sessionHash;
    /**
     * 2FA で使用した要素（TOTP / EMAIL / BACKUP_CODE）。2FA を経ないログインは null（CMP-261012-0911 判断②）。
     */
    private final String mfaFactor;

    public LoginSuccessEvent(Long userId, String ipAddress, String userAgent, String method) {
        super();
        this.userId = userId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.method = method;
        this.sessionHash = null;
        this.mfaFactor = null;
    }

    public LoginSuccessEvent(Long userId, String ipAddress, String userAgent, String method, String sessionHash) {
        super();
        this.userId = userId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.method = method;
        this.sessionHash = sessionHash;
        this.mfaFactor = null;
    }

    public LoginSuccessEvent(Long userId, String ipAddress, String userAgent, String method, String sessionHash,
                             String mfaFactor) {
        super();
        this.userId = userId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.method = method;
        this.sessionHash = sessionHash;
        this.mfaFactor = mfaFactor;
    }
}
