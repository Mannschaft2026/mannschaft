package com.mannschaft.app.auth.service;

import org.springframework.web.util.HtmlUtils;

import java.util.Map;

/**
 * F01.9 保護者同意系メール（スルー方式）の件名・本文を組み立てる（CMP-261123-1124）。
 *
 * <p>メール Worker は subject / body を持つ payload をそのまま送る（MFA_RECOVERY と同じ流儀）。
 * 本文は日本語固定（既存の auth スルー方式メールと同じ）。利用者入力（表示名）は HTML エスケープする。</p>
 */
final class ParentalConsentMailContent {

    /** 保護者の承認画面（FE: pages/parental-consent/approve.vue）。token はクエリで受ける。 */
    static final String APPROVE_PATH = "/parental-consent/approve";

    private ParentalConsentMailContent() {
    }

    /** 保護者への同意依頼。{@code approveUrl} は承認画面への完全な URL（token 付き）。 */
    static Map<String, String> invitation(String childName, String approveUrl, int expiresDays) {
        String body = "<p>" + esc(childName) + " さんが Mannschaft を利用するには、保護者の方の同意が必要です。</p>"
                + "<p>内容をご確認のうえ、以下のリンクから同意または拒否をお選びください"
                + "（有効期限 " + expiresDays + " 日間・1 回限り）。</p>"
                + "<p><a href=\"" + esc(approveUrl) + "\">" + esc(approveUrl) + "</a></p>"
                + "<p>このメールにお心当たりがない場合は、破棄してください。リンクを開かなければ何も起こりません。</p>";
        return Map.of("subject", "【Mannschaft】" + childName + " さんの保護者の同意をお願いします", "body", body);
    }

    /** 子への「同意が得られた」通知。 */
    static Map<String, String> approved(String childName) {
        String body = "<p>" + esc(childName) + " さん、保護者の方の同意が確認できました。</p>"
                + "<p>Mannschaft をすべての機能でご利用いただけます。</p>";
        return Map.of("subject", "【Mannschaft】保護者の同意が確認できました", "body", body);
    }

    /** 子への「同意が拒否された」通知。 */
    static Map<String, String> rejected() {
        String body = "<p>保護者の方が同意を拒否したため、同意が得られませんでした。</p>"
                + "<p>すべての保護者が拒否した場合、アカウントは削除されます。ご不明な点は保護者の方とご相談ください。</p>";
        return Map.of("subject", "【Mannschaft】保護者の同意が得られませんでした", "body", body);
    }

    /** 子への「期限内に同意が得られずアカウントを削除した」通知。 */
    static Map<String, String> expiredAccountDeleted() {
        String body = "<p>期限内に保護者の方の同意が得られなかったため、アカウントを削除しました。</p>"
                + "<p>引き続きご利用の場合は、保護者の方と相談のうえ、改めて登録してください。</p>";
        return Map.of("subject", "【Mannschaft】保護者の同意が得られず、アカウントを削除しました", "body", body);
    }

    /** 子への「成人到達により保護者との連携を自動解除した」通知。 */
    static Map<String, String> released(String displayName) {
        String body = "<p>" + esc(displayName) + " さん、18 歳に到達したため、保護者の方との連携を自動的に解除しました。</p>"
                + "<p>今後は保護者の同意なしでご利用いただけます。</p>";
        return Map.of("subject", "【Mannschaft】保護者との連携を解除しました", "body", body);
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
