package com.mannschaft.app.supportaccess.listener;

import com.mannschaft.app.supportaccess.service.SupportAccessGrantService;
import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;

/** F10.9 退会申請で本人の grant を即時取消する。試練スタブ。 */
public class SupportAccessWithdrawalListener {

    private final SupportAccessGrantService grantService;

    public SupportAccessWithdrawalListener(SupportAccessGrantService grantService) {
        this.grantService = grantService;
    }

    public void onWithdrawalRequested(WithdrawalRequestedEvent event) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
