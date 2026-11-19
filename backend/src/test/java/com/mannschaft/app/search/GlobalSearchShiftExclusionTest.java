package com.mannschaft.app.search;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.architecture.ProductionClasses;
import com.mannschaft.app.search.service.GlobalSearchRequest;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * グローバル検索にシフト表が出ないことを固定する（CMP-260826-2127 AC-6 / AC-11 の証拠の付け替え先）。
 *
 * <p>旧 GlobalSearchService はシフト表を検索種別に含め、非管理者に未公開シフト表を出さない述語を持っていた
 * （CMP-260826-2127）。CMP-261116-0722 の作り直しで旧サービスは撤去され、シフト表は検索対象種別から外れた。
 * 本テストはその性質——シフト表はどの閲覧者（非管理者・SYSTEM_ADMIN を含む）の検索結果にも件数にも
 * 現れない——を、種別の列挙・入力検証・Contributor の実装の 3 点で固定する。将来シフトを検索対象に戻すときは
 * 本テストが赤になり、未公開シフト表の可視性述語（CMP-260826-2127 AC-6/AC-11）を改めて試練に載せることになる。</p>
 */
@DisplayName("グローバル検索にシフト表が出ない（CMP-260826-2127 AC-6 / AC-11）")
class GlobalSearchShiftExclusionTest {

    @Test
    @DisplayName("検索種別にシフト・スケジュール系の種別が無い（typeCounts のキーにも結果の type にも現れない）")
    void 検索種別にシフトが無い() {
        List<String> names = Arrays.stream(SearchType.values())
                .map(t -> t.name().toUpperCase(Locale.ROOT))
                .toList();
        assertThat(names).noneMatch(n -> n.contains("SHIFT") || n.contains("SCHEDULE"));
    }

    @ParameterizedTest(name = "type={0} は SEARCH_007")
    @ValueSource(strings = {"SHIFT", "SHIFTS", "SHIFT_SCHEDULE", "shifts", "POST,SHIFT"})
    @DisplayName("type にシフト系を指定しても検索は実行されず SEARCH_007（400）になる")
    void シフト種別の指定は400(String type) {
        assertThatThrownBy(() -> GlobalSearchRequest.parse("ab", type, null, null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SearchErrorCode.INVALID_TYPE);
    }

    @Test
    @DisplayName("shift ドメインに SearchContributor の実装が無い（検索経路からシフト表に届かない）")
    void shiftドメインにContributorが無い() {
        JavaClasses productionClasses = ProductionClasses.get();
        assertThat(productionClasses.stream()
                .filter(c -> !c.isInterface() && c.isAssignableTo(SearchContributor.class)))
                .as("対照: 実装の検出が空振りしていない（TEAM 等の Contributor は見つかる）")
                .isNotEmpty();
        List<String> contributorsInShift = productionClasses.stream()
                .filter(c -> c.getPackageName().startsWith("com.mannschaft.app.shift"))
                .filter(c -> c.isAssignableTo(SearchContributor.class))
                .map(JavaClass::getName)
                .toList();
        assertThat(contributorsInShift).isEmpty();
    }
}
