package com.mannschaft.app.search;

import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.common.visibility.StandardVisibility;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * TEAM / ORGANIZATION 種別で、スコープ本体（チーム・組織そのもの）の公開範囲の<b>段</b>を SQL の WHERE 句へ落とす述語
 * （CMP-261116-0722 アリシゼーション指摘の根治）。
 *
 * <p><b>正準との一致</b>: 機能側の visibility 値は各ドメインの正準マッパ（{@code TeamVisibilityMapper} /
 * {@code OrganizationVisibilityMapper}）で {@link StandardVisibility} に写し、段ごとの必要ロールは
 * {@code AbstractContentVisibilityResolver#visibleByLevel}（チーム詳細・組織詳細の {@code ContentVisibilityChecker}
 * が使う判定）と同じ閾値にする。閲覧者のロールは {@link SearchViewer} の<b>スコープ別</b>ロールで判定する
 * （全所属先の和集合では判定しない）。</p>
 * <ul>
 *   <li>{@code PUBLIC} → 誰でも</li>
 *   <li>{@code SCOPE_AFFILIATED} → そのスコープの所属者（検索の母集団は SUPPORTER 以上のため、母集団にあれば可）</li>
 *   <li>{@code SUPPORTERS_AND_ABOVE} / {@code MEMBERS_AND_ABOVE} / {@code DEPUTY_ADMINS_AND_ABOVE} /
 *       {@code ADMINS_AND_ABOVE} → そのスコープでのロールが閾値以上</li>
 *   <li>それ以外（スコープ本体の段として定義されていない値）→ 誰にも出さない（fail-closed）</li>
 * </ul>
 *
 * <p>GUEST のみのスコープは {@link SearchViewer} の母集団に入らないため、正準では GUEST が見える段
 * （SCOPE_AFFILIATED）でも検索には出ない（設計書 §6「GUEST ロールしか持たないスコープは母集団外」）。
 * 正準より広く出すことはない。</p>
 *
 * <p>件数と取得は同一インスタンスから同一の WHERE 断片を作る（{@link #appendTo}）。値はすべてバインドする。</p>
 *
 * @param <V> 機能側の visibility 列挙
 */
public final class SearchScopeVisibilityPredicate<V extends Enum<V>> {

    private final List<V> publicValues;
    private final Map<V, List<Long>> visibleIdsByValue;

    private SearchScopeVisibilityPredicate(List<V> publicValues, Map<V, List<Long>> visibleIdsByValue) {
        this.publicValues = List.copyOf(publicValues);
        this.visibleIdsByValue = visibleIdsByValue;
    }

    /**
     * 閲覧者のスコープ別ロールから述語を組み立てる。
     *
     * @param valueType 機能側 visibility 列挙の型（全値を網羅する）
     * @param toStandard 機能側の正準マッパ（{@code XxxVisibilityMapper::toStandard}）
     * @param viewer    閲覧者（スコープ指定時は {@code narrowTo} 済みでよい）
     * @param scopeType {@link SearchViewer#TEAM} / {@link SearchViewer#ORGANIZATION}
     */
    public static <V extends Enum<V>> SearchScopeVisibilityPredicate<V> of(
            Class<V> valueType, Function<V, StandardVisibility> toStandard, SearchViewer viewer, String scopeType) {
        List<V> publicValues = new ArrayList<>();
        Map<V, List<Long>> idsByValue = new EnumMap<>(valueType);
        for (V value : valueType.getEnumConstants()) {
            StandardVisibility level = toStandard.apply(value);
            if (level == StandardVisibility.PUBLIC) {
                publicValues.add(value);
                continue;
            }
            String minRole = minimumRole(level);
            if (minRole == null) {
                continue;
            }
            List<Long> ids = viewer.scopes().stream()
                    .filter(s -> s.scopeType().equals(scopeType))
                    .filter(s -> RolePriority.isAtLeast(s.roleName(), minRole))
                    .map(SearchViewer.ViewerScope::scopeId)
                    .toList();
            if (!ids.isEmpty()) {
                idsByValue.put(value, ids);
            }
        }
        return new SearchScopeVisibilityPredicate<>(publicValues, idsByValue);
    }

    /**
     * スコープ本体の段に対する必要ロール（{@code AbstractContentVisibilityResolver#visibleByLevel} と同じ閾値）。
     * スコープ本体の段として定義されていない値は null（fail-closed）。
     */
    static String minimumRole(StandardVisibility level) {
        return switch (level) {
            case SCOPE_AFFILIATED -> "GUEST";
            case SUPPORTERS_AND_ABOVE -> "SUPPORTER";
            case MEMBERS_AND_ABOVE -> "MEMBER";
            case DEPUTY_ADMINS_AND_ABOVE -> "DEPUTY_ADMIN";
            case ADMINS_AND_ABOVE -> "ADMIN";
            default -> null;
        };
    }

    /**
     * {@code AND (alias.visibility IN :公開値 OR (alias.visibility = :値1 AND alias.id IN :閲覧可ID1) OR ...)} を追記する。
     *
     * @param sb     WHERE 句
     * @param alias  JPQL のエンティティ別名
     * @param params バインド値の格納先
     */
    public void appendTo(StringBuilder sb, String alias, Map<String, Object> params) {
        List<String> disjuncts = new ArrayList<>();
        if (!publicValues.isEmpty()) {
            disjuncts.add(alias + ".visibility IN :publicVisibilities");
            params.put("publicVisibilities", publicValues);
        }
        int i = 0;
        for (Map.Entry<V, List<Long>> entry : visibleIdsByValue.entrySet()) {
            String vis = "scopeVisibility" + i;
            String ids = "scopeVisibleIds" + i;
            disjuncts.add("(" + alias + ".visibility = :" + vis + " AND " + alias + ".id IN :" + ids + ")");
            params.put(vis, entry.getKey());
            params.put(ids, entry.getValue());
            i++;
        }
        if (disjuncts.isEmpty()) {
            sb.append(" AND 1 = 0");
            return;
        }
        sb.append(" AND (").append(String.join(" OR ", disjuncts)).append(")");
    }
}
