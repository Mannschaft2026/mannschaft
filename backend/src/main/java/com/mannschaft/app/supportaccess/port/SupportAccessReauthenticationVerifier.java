package com.mannschaft.app.supportaccess.port;

import com.mannschaft.app.supportaccess.dto.SupportAccessApproveRequest;

/** F10.9 許可時の本人の再認証（パスワード／パスキー）。メールのワンクリックは不可。 */
public interface SupportAccessReauthenticationVerifier {

    boolean verify(Long userId, SupportAccessApproveRequest request);
}
