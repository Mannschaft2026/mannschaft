package com.mannschaft.app.supportaccess.listener;

import com.mannschaft.app.supportaccess.service.SupportAccessRetentionService;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;

/** F10.9 退会30日後の強消去（AccountPurgeService の purgeTargetDomains に "supportaccess" を登録する）。試練スタブ。 */
public class SupportAccessPurgeEventListener {

    /** account_purge_completion_status の domain 識別子。 */
    public static final String DOMAIN = "supportaccess";

    private final SupportAccessRetentionService retentionService;
    private final AccountPurgeCompletionService completion;

    public SupportAccessPurgeEventListener(SupportAccessRetentionService retentionService,
                                           AccountPurgeCompletionService completion) {
        this.retentionService = retentionService;
        this.completion = completion;
    }

    public void onAccountPurged(AccountPurgedEvent event) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
