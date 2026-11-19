package com.mannschaft.app.search;

import java.util.ArrayList;
import java.util.List;

/**
 * 検索結果の抜粋・強調位置の生成（設計書 §4 補足 {@code snippet} / {@code title}）。
 *
 * <p>一致判定は元のプレーンテキスト上で大文字小文字を区別せずに行い、出力時に一致箇所以外も含めて
 * すべて HTML エスケープしてから一致箇所だけを {@code <mark>} で囲む。本文中の {@code <mark>} 等の
 * 文字列はエスケープされるため、強調を偽装できない。</p>
 */
public final class SearchTextHighlighter {

    /** 抜粋の最大文字数（{@code <mark>} タグ分を除く。エスケープ後の文字数）。 */
    public static final int SNIPPET_MAX_LENGTH = 200;

    /** 一致箇所の前に残す文脈の文字数。 */
    private static final int LEADING_CONTEXT = 40;

    private static final String MARK_OPEN = "<mark>";
    private static final String MARK_CLOSE = "</mark>";

    private SearchTextHighlighter() {
    }

    /**
     * 本文を HTML エスケープしてからキーワード周辺を最大 {@value #SNIPPET_MAX_LENGTH} 文字抜粋し、
     * 一致箇所のみ {@code <mark>} で囲む（大文字小文字を区別せず、元の表記を保つ）。
     *
     * @param rawText 元の本文（null 可）
     * @param terms   検索語
     * @return 抜粋（本文が null なら null）
     */
    public static String snippet(String rawText, List<String> terms) {
        if (rawText == null) {
            return null;
        }
        int first = firstMatch(rawText, terms);
        int start = first < 0 ? 0 : Math.max(0, first - LEADING_CONTEXT);
        int end = Math.min(rawText.length(), start + SNIPPET_MAX_LENGTH);
        // エスケープで文字数が増える場合は、収まるまで末尾（一致箇所より後ろ）から削る
        while (end > start && escape(rawText.substring(start, end)).length() > SNIPPET_MAX_LENGTH) {
            end--;
        }
        return highlight(rawText.substring(start, end), terms);
    }

    /**
     * プレーンテキストの題名で最初に一致した語の位置 {@code [start, end)} を返す。一致なしは null。
     */
    public static List<Integer> titleRange(String plainTitle, List<String> terms) {
        if (plainTitle == null || terms == null) {
            return null;
        }
        int bestStart = -1;
        int bestEnd = -1;
        for (String term : terms) {
            int pos = indexOfIgnoreCase(plainTitle, term, 0);
            if (pos >= 0 && (bestStart < 0 || pos < bestStart)) {
                bestStart = pos;
                bestEnd = pos + term.length();
            }
        }
        return bestStart < 0 ? null : List.of(bestStart, bestEnd);
    }

    /** 抜粋範囲の中で一致箇所を囲みながら全体をエスケープする。 */
    private static String highlight(String window, List<String> terms) {
        List<int[]> ranges = matchRanges(window, terms);
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (int[] r : ranges) {
            sb.append(escape(window.substring(cursor, r[0])));
            sb.append(MARK_OPEN).append(escape(window.substring(r[0], r[1]))).append(MARK_CLOSE);
            cursor = r[1];
        }
        sb.append(escape(window.substring(cursor)));
        return sb.toString();
    }

    /** 重ならない一致範囲を左から順に列挙する（同じ位置では長い語を優先）。 */
    private static List<int[]> matchRanges(String text, List<String> terms) {
        List<int[]> ranges = new ArrayList<>();
        if (terms == null || terms.isEmpty()) {
            return ranges;
        }
        int pos = 0;
        while (pos < text.length()) {
            int bestStart = -1;
            int bestLen = 0;
            for (String term : terms) {
                if (term == null || term.isEmpty()) {
                    continue;
                }
                int found = indexOfIgnoreCase(text, term, pos);
                if (found >= 0 && (bestStart < 0 || found < bestStart
                        || (found == bestStart && term.length() > bestLen))) {
                    bestStart = found;
                    bestLen = term.length();
                }
            }
            if (bestStart < 0) {
                break;
            }
            ranges.add(new int[]{bestStart, bestStart + bestLen});
            pos = bestStart + bestLen;
        }
        return ranges;
    }

    private static int firstMatch(String text, List<String> terms) {
        List<int[]> ranges = matchRanges(text, terms);
        return ranges.isEmpty() ? -1 : ranges.get(0)[0];
    }

    private static int indexOfIgnoreCase(String text, String term, int from) {
        if (term == null || term.isEmpty()) {
            return -1;
        }
        int last = text.length() - term.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (text.regionMatches(true, i, term, 0, term.length())) {
                return i;
            }
        }
        return -1;
    }

    /** HTML エスケープ（{@code & < > " '}）。 */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
