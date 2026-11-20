package com.mannschaft.app.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SearchTextHighlighter} の単体テスト（AC-C12 試練・red 先行）。
 * snippet は HTML エスケープ後に一致箇所のみ {@code <mark>} で囲み、最大 200 文字（タグ分を除く）。
 */
@DisplayName("SearchTextHighlighter 抜粋と強調（AC-C12 試練）")
class SearchTextHighlighterTest {

    private static String withoutMark(String s) {
        return s.replace("<mark>", "").replace("</mark>", "");
    }

    @Test
    @DisplayName("AC-C12: 本文の <script> は生のまま返らず、エスケープ後の一致箇所だけ <mark> で囲む")
    void AC_C12_エスケープしてからmark() {
        String snippet = SearchTextHighlighter.snippet("<script>alert(1)</script> 練習は グラウンド で", List.of("グラウンド"));

        assertThat(snippet).doesNotContain("<script>").contains("&lt;script&gt;");
        assertThat(snippet).contains("<mark>グラウンド</mark>");
        assertThat(withoutMark(snippet)).doesNotContain("<").doesNotContain(">");
    }

    @Test
    @DisplayName("AC-C12: 大文字小文字を区別せず一致させ、元の表記を保って囲む")
    void AC_C12_大文字小文字() {
        String snippet = SearchTextHighlighter.snippet("Meeting at Ground B", List.of("ground"));
        assertThat(snippet).contains("<mark>Ground</mark>");
    }

    @Test
    @DisplayName("AC-C12: 本文中の <mark> 文字列もエスケープされ、強調として偽装できない")
    void AC_C12_mark偽装の封鎖() {
        String snippet = SearchTextHighlighter.snippet("<mark>偽</mark> 本物 kw", List.of("kw"));
        assertThat(snippet).contains("&lt;mark&gt;偽&lt;/mark&gt;").contains("<mark>kw</mark>");
    }

    @Test
    @DisplayName("AC-C12: 長い本文でも一致箇所の周辺を抜粋し、最大200文字（<mark> を除く）")
    void AC_C12_最大200文字() {
        String body = "a".repeat(600) + "TARGETWORD" + "b".repeat(600);
        String snippet = SearchTextHighlighter.snippet(body, List.of("targetword"));

        assertThat(snippet).contains("<mark>TARGETWORD</mark>");
        assertThat(withoutMark(snippet).length()).isLessThanOrEqualTo(SearchTextHighlighter.SNIPPET_MAX_LENGTH);
    }

    @Test
    @DisplayName("AC-C12: 複数語はそれぞれ囲む")
    void AC_C12_複数語() {
        String snippet = SearchTextHighlighter.snippet("グラウンド 利用 予約", List.of("グラウンド", "予約"));
        assertThat(snippet).contains("<mark>グラウンド</mark>").contains("<mark>予約</mark>")
                .doesNotContain("<mark>利用</mark>");
    }

    @Test
    @DisplayName("AC-C12: title の一致位置はプレーンテキスト基準の [start, end)、一致なしは null")
    void AC_C12_titleRange() {
        String title = "<b>サッカー部</b>";
        assertThat(SearchTextHighlighter.titleRange(title, List.of("サッカー"))).containsExactly(3, 7);
        assertThat(SearchTextHighlighter.titleRange("Soccer Club", List.of("club"))).containsExactly(7, 11);
        assertThat(SearchTextHighlighter.titleRange("野球部", List.of("サッカー"))).isNull();
    }
}
