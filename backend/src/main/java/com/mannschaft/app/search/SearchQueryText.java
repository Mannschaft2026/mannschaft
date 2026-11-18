package com.mannschaft.app.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 検索キーワードの正規化・分割・エスケープ（設計書 §4 補足「FULLTEXT / LIKE の入力処理」）。
 *
 * <p>LIKE 用のエスケープは MySQL の既定エスケープ文字 {@code \} を前提とする（SQL 側で
 * {@code ESCAPE} 句を付けない。値はパラメータバインドで渡すため文字列リテラルの
 * バックスラッシュ解釈を受けず、LIKE だけが {@code \%} / {@code \_} / {@code \\} を文字として扱う）。</p>
 */
public final class SearchQueryText {

    /** 半角・全角スペース（タブ等の空白も含む）の連続。 */
    private static final Pattern SPACES = Pattern.compile("[\\s\\u3000]+");

    private SearchQueryText() {
    }

    /**
     * trim し、連続する半角／全角スペースを半角スペース 1 つへ畳み込む。履歴の UPSERT キーにも使う。
     *
     * @param raw 入力（null は空文字として扱う）
     * @return 正規化済みキーワード
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return SPACES.matcher(raw).replaceAll(" ").trim();
    }

    /** 正規化済みキーワードをスペースで語に分割する（AND 条件）。 */
    public static List<String> terms(String normalized) {
        List<String> result = new ArrayList<>();
        if (normalized == null) {
            return result;
        }
        for (String term : normalized.split(" ")) {
            if (!term.isEmpty()) {
                result.add(term);
            }
        }
        return result;
    }

    /** LIKE 用に {@code \} {@code %} {@code _} を {@code \} でエスケープする（{@code \} を先に二重化）。 */
    public static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 部分一致用の LIKE パターン（{@code %語%}、語はエスケープ済み）。 */
    public static String containsPattern(String term) {
        return "%" + escapeLike(term) + "%";
    }

    /** 前方一致用の LIKE パターン（{@code 語%}、語はエスケープ済み）。 */
    public static String prefixPattern(String term) {
        return escapeLike(term) + "%";
    }

    /**
     * FULLTEXT BOOLEAN MODE 用の検索式を作る。各語を必須のフレーズ {@code +"語"} にし、
     * 語中の {@code "} は取り除く（BOOLEAN 演算子を演算子として解釈させない）。空になった語は捨てる。
     */
    public static String toBooleanModeQuery(List<String> terms) {
        List<String> phrases = new ArrayList<>();
        for (String term : terms) {
            String stripped = term.replace("\"", "");
            if (!stripped.isEmpty()) {
                phrases.add("+\"" + stripped + "\"");
            }
        }
        return String.join(" ", phrases);
    }
}
