package com.mannschaft.app.supportaccess.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.PersonalDataCategoryCollectEvent;
import com.mannschaft.app.supportaccess.service.SupportAccessPersonalDataService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * F10.9 GDPR エクスポートの「本人の閲覧記録」（support_access_logs）と「依頼・許可」（support_access_grants。
 * 依頼理由・運営者の表示名・状態と時刻。本人宛と、保護者として関わったもの）のカテゴリを収集して返す。
 *
 * <p>PersonalDataCollector は収集依頼の同期イベントを publish するだけで、supportaccess の Service を直接呼ばない
 * （エクスポートのトランザクションから supportaccess の Repository へ静的に到達させない。D-3T）。
 * 同期の {@link EventListener} なので、収集はエクスポートと同じスレッドで完了する。</p>
 */
@Component
public class SupportAccessExportListener {

    /** PersonalDataCollector のカテゴリキー（閲覧記録）。 */
    public static final String CATEGORY = "support_access_logs";
    /** PersonalDataCollector のカテゴリキー（依頼・許可）。 */
    public static final String GRANTS_CATEGORY = "support_access_grants";

    private final SupportAccessPersonalDataService personalDataService;

    public SupportAccessExportListener(SupportAccessPersonalDataService personalDataService) {
        this.personalDataService = personalDataService;
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "本人の個人データ開示（GDPR エクスポート）に閲覧記録と依頼・許可を含める。停止すると本人が自分の記録を受け取れず開示が欠落する")
    @EventListener
    public void onCollect(PersonalDataCategoryCollectEvent event) {
        if (CATEGORY.equals(event.getCategory())) {
            event.respond(personalDataService.listLogsForTarget(event.getUserId()));
        } else if (GRANTS_CATEGORY.equals(event.getCategory())) {
            event.respond(personalDataService.listGrantsForExport(event.getUserId()));
        }
    }
}
