package com.mannschaft.app.gdpr.event;

import java.util.Objects;

/**
 * GDPR エクスポートの1カテゴリ分の収集依頼（同期イベント）。
 *
 * <p>PersonalDataCollector が他ドメインの Service を直接呼ぶと、エクスポートの {@code @Transactional} 入口から
 * そのドメインの Repository へ静的に到達し、D-3T（推移的クロスドメイン Transactional）に違反する。
 * そこで収集を依頼するイベントを同期で publish し、そのカテゴリを持つドメインの {@code @EventListener} が
 * {@link #respond} で結果（JSON 化できるオブジェクト）を返す。収集が同じスレッド・同じ呼び出しの中で完了する
 * 点は直接呼び出しと変わらない。</p>
 */
public final class PersonalDataCategoryCollectEvent {

    private final Long userId;
    private final String category;
    private Object payload;
    private boolean answered;

    public PersonalDataCategoryCollectEvent(Long userId, String category) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.category = Objects.requireNonNull(category, "category");
    }

    public Long getUserId() {
        return userId;
    }

    public String getCategory() {
        return category;
    }

    /** 収集済みの内容を返す（JSON 化は収集側が行う）。 */
    public void respond(Object collected) {
        this.payload = collected;
        this.answered = true;
    }

    public boolean isAnswered() {
        return answered;
    }

    public Object getPayload() {
        return payload;
    }
}
