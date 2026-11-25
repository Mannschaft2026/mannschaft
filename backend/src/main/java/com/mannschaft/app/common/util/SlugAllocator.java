package com.mannschaft.app.common.util;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import java.util.function.Predicate;

/**
 * 自動採番 slug の一意候補を探す共通ロジック（チーム・組織・イベント共通）。
 *
 * <p>論理削除済み・slug 履歴の slug は永久予約（再利用不可）のため、{@code occupied} には
 * 「論理削除済みを含む存在確認」と「履歴予約」を併せた判定を渡す。</p>
 */
public final class SlugAllocator {

    /** 連番サフィックスを試す回数。 */
    private static final int SEQUENTIAL_ATTEMPTS = 100;

    /** ランダムサフィックスを試す回数。 */
    private static final int RANDOM_ATTEMPTS = 20;

    private SlugAllocator() {}

    /**
     * 使用中でない slug を採番する。
     *
     * <p>順序: ベース → 連番 1..100 → ランダムサフィックス（最大20回）。ベースが固定フォールバック
     * （{@code "team"}。日本語名など）のときは同時作成の衝突を避けるためランダムサフィックスを先に試す。
     * 全て使用中なら {@code exhausted} の業務例外（4xx）を投げる（500 にしない）。</p>
     *
     * @param base      {@link SlugGenerator#generate(String)} の結果
     * @param occupied  使用中（現役・論理削除済み・履歴予約）か判定する述語
     * @param exhausted 採番を諦める場合のエラーコード
     * @return 一意な slug
     */
    public static String allocate(String base, Predicate<String> occupied, ErrorCode exhausted) {
        boolean generic = SlugGenerator.isFallback(base);
        if (!generic) {
            String found = baseOrSequential(base, occupied);
            if (found != null) {
                return found;
            }
        }
        for (int i = 0; i < RANDOM_ATTEMPTS; i++) {
            String candidate = SlugGenerator.withRandomSuffix(base);
            if (!occupied.test(candidate)) {
                return candidate;
            }
        }
        if (generic) {
            String found = baseOrSequential(base, occupied);
            if (found != null) {
                return found;
            }
        }
        throw new BusinessException(exhausted);
    }

    private static String baseOrSequential(String base, Predicate<String> occupied) {
        if (!occupied.test(base)) {
            return base;
        }
        for (int i = 1; i <= SEQUENTIAL_ATTEMPTS; i++) {
            String candidate = SlugGenerator.withSuffix(base, i);
            if (!occupied.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 例外の原因連鎖に、指定した一意索引名の違反が含まれるか判定する。
     *
     * @param e         捕捉した例外
     * @param indexName 一意索引名（例: {@code uq_teams_slug}）
     * @return その一意制約の違反なら true
     */
    public static boolean isUniqueViolation(Throwable e, String indexName) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(indexName)) {
                return true;
            }
        }
        return false;
    }
}
