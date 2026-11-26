package com.mannschaft.app.supportaccess.listener;

import com.mannschaft.app.gdpr.event.PersonalDataCategoryCollectEvent;
import com.mannschaft.app.supportaccess.service.SupportAccessPersonalDataService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * F10.9 GDPR エクスポートの「本人の閲覧記録」カテゴリ（support_access_logs）を収集して返す。
 *
 * <p>PersonalDataCollector は収集依頼の同期イベントを publish するだけで、supportaccess の Service を直接呼ばない
 * （エクスポートのトランザクションから supportaccess の Repository へ静的に到達させない。D-3T）。
 * 同期の {@link EventListener} なので、収集はエクスポートと同じスレッドで完了する。</p>
 */
@Component
public class SupportAccessExportListener {

    /** PersonalDataCollector のカテゴリキー。 */
    public static final String CATEGORY = "support_access_logs";

    private final SupportAccessPersonalDataService personalDataService;

    public SupportAccessExportListener(SupportAccessPersonalDataService personalDataService) {
        this.personalDataService = personalDataService;
    }

    @EventListener
    public void onCollect(PersonalDataCategoryCollectEvent event) {
        if (CATEGORY.equals(event.getCategory())) {
            event.respond(personalDataService.listLogsForTarget(event.getUserId()));
        }
    }
}
