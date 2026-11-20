package com.mannschaft.app.search;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 検索結果 1 件（設計書 §4 レスポンス {@code results[]}）。
 *
 * @param type       種別
 * @param id         対象 ID
 * @param title      プレーンテキストの題名（{@code <mark>} なし。題名を持たない種別は null）
 * @param snippet    HTML エスケープ後に一致箇所のみ {@code <mark>} で囲んだ抜粋（最大 200 文字）
 * @param highlights 一致位置（例 {@code title -> [start, end]}、プレーンテキスト基準）
 * @param relevance  FULLTEXT 種別の関連度（LIKE 種別は null）
 * @param author     作成者（無い種別は null）
 * @param scope      所属スコープ（無い種別は null）
 * @param createdAt  作成日時（起きた瞬間なので OffsetDateTime。時刻方針 datetime_policy_utc_instant_vs_wallclock §1）
 * @param url        遷移先の実在 FE ルート（リンク不可は null）
 * @param actions    実行可能アクション
 */
public record SearchHit(
        SearchType type,
        Long id,
        String title,
        String snippet,
        Map<String, List<Integer>> highlights,
        Double relevance,
        Author author,
        ScopeRef scope,
        OffsetDateTime createdAt,
        String url,
        List<String> actions) {

    /**
     * エンティティの作成日時（アプリ層の {@code LocalDateTime}）を {@code createdAt} の瞬間へ直す。
     *
     * <p>Hibernate が読み戻す {@code LocalDateTime} は DB の UTC 格納値ではなく、アプリ層の壁時計
     * （{@link UserZoneLocalDateTimeParser#SERVER_ZONE} = JST）である（.claudecode.md §20 の二層モデル）。
     * したがって JST として解釈してから瞬間へ変換する。UTC 固定の {@code Clock} のゾーンで解釈すると
     * 9 時間未来へずれる（CMP-261116-0722 PR-2 検分 P2-1）。</p>
     *
     * @param appWallClock アプリ層の壁時計（null 可）
     * @return 同一瞬間の {@link OffsetDateTime}（オフセットは SERVER_ZONE のもの）。入力が null なら null
     */
    public static OffsetDateTime createdAtOf(LocalDateTime appWallClock) {
        return appWallClock == null ? null
                : appWallClock.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toOffsetDateTime();
    }

    /** 作成者。 */
    @Schema(name = "SearchHitAuthor")
    public record Author(Long id, String displayName, String avatarUrl) {
    }

    /** 所属スコープ。 */
    @Schema(name = "SearchHitScope")
    public record ScopeRef(String type, Long id, String name) {
    }
}
