package com.mannschaft.app.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SearchQueryText} の単体テスト（CMP-261116-0722 PR-1 試練・red 先行）。
 * AC-C11（演算子・ワイルドカードを文字として扱う）と AC-C14（履歴キーの正規化）の純粋関数部分を固定する。
 */
@DisplayName("SearchQueryText 正規化・エスケープ（AC-C11 / AC-C14 試練）")
class SearchQueryTextTest {

    @Test
    @DisplayName("AC-C14: trim し、連続する半角／全角スペースを半角1つへ畳み込む")
    void AC_C14_正規化() {
        assertThat(SearchQueryText.normalize("  グラウンド   利用　　予約 ")).isEqualTo("グラウンド 利用 予約");
        assertThat(SearchQueryText.normalize("　ab　")).isEqualTo("ab");
        assertThat(SearchQueryText.normalize("ab")).isEqualTo("ab");
    }

    @Test
    @DisplayName("AC-C11: 正規化済みキーワードをスペースで語に分割する（AND 条件）")
    void AC_C11_語の分割() {
        assertThat(SearchQueryText.terms("グラウンド 利用")).containsExactly("グラウンド", "利用");
        assertThat(SearchQueryText.terms("ab")).containsExactly("ab");
    }

    @Test
    @DisplayName("AC-C11: LIKE 用に \\ % _ をエスケープする（\\ は先に二重化）")
    void AC_C11_LIKEエスケープ() {
        // 期待値はすべて Java リテラル。"\\" が 1 文字の \ を表す。
        assertThat(SearchQueryText.escapeLike("50%")).isEqualTo("50\\%");
        assertThat(SearchQueryText.escapeLike("a_b")).isEqualTo("a\\_b");
        assertThat(SearchQueryText.escapeLike("c:\\dir")).isEqualTo("c:\\\\dir");
        assertThat(SearchQueryText.escapeLike("\\%_")).isEqualTo("\\\\\\%\\_");
        assertThat(SearchQueryText.escapeLike("普通の語")).isEqualTo("普通の語");
    }

    @Test
    @DisplayName("AC-C11: BOOLEAN MODE は各語を +\"語\" の必須フレーズにし、演算子を演算子として解釈させない")
    void AC_C11_BOOLEANMODE() {
        assertThat(SearchQueryText.toBooleanModeQuery(List.of("グラウンド", "利用")))
                .isEqualTo("+\"グラウンド\" +\"利用\"");
        assertThat(SearchQueryText.toBooleanModeQuery(List.of("-ab", "c*d", "(x)", "~y", "<z>", "@w")))
                .isEqualTo("+\"-ab\" +\"c*d\" +\"(x)\" +\"~y\" +\"<z>\" +\"@w\"");
    }

    @Test
    @DisplayName("AC-C11: 語中の \" は取り除き、空になった語は捨てる（フレーズを閉じさせない）")
    void AC_C11_ダブルクォート除去() {
        assertThat(SearchQueryText.toBooleanModeQuery(List.of("a\"b", "\"\"", "cd")))
                .isEqualTo("+\"ab\" +\"cd\"");
    }
}
