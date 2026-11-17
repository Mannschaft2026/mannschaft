package com.mannschaft.app.search;

import java.util.List;

/**
 * 検索結果の抜粋・強調位置の生成（設計書 §4 補足 {@code snippet} / {@code title}）。
 *
 * <p>試練（CMP-261116-0722 PR-1）で置いた骨格。実装は出陣で行う。</p>
 */
public final class SearchTextHighlighter {

    /** 抜粋の最大文字数（{@code <mark>} タグ分を除く）。 */
    public static final int SNIPPET_MAX_LENGTH = 200;

    private SearchTextHighlighter() {
    }

    /**
     * 本文を HTML エスケープしてからキーワード周辺を最大 {@value #SNIPPET_MAX_LENGTH} 文字抜粋し、
     * 一致箇所のみ {@code <mark>} で囲む（大文字小文字を区別せず、元の表記を保つ）。
     */
    public static String snippet(String rawText, List<String> terms) {
        throw new UnsupportedOperationException("未実装（CMP-261116-0722 PR-1 出陣で実装）");
    }

    /**
     * プレーンテキストの題名で最初に一致した語の位置 {@code [start, end)} を返す。一致なしは null。
     */
    public static List<Integer> titleRange(String plainTitle, List<String> terms) {
        throw new UnsupportedOperationException("未実装（CMP-261116-0722 PR-1 出陣で実装）");
    }
}
