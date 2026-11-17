package com.mannschaft.app.search;

import java.util.List;

/**
 * 検索キーワードの正規化・分割・エスケープ（設計書 §4 補足「FULLTEXT / LIKE の入力処理」）。
 *
 * <p>試練（CMP-261116-0722 PR-1）で置いた骨格。実装は出陣で行う。</p>
 */
public final class SearchQueryText {

    private SearchQueryText() {
    }

    /**
     * trim し、連続する半角／全角スペースを半角スペース 1 つへ畳み込む。履歴の UPSERT キーにも使う。
     */
    public static String normalize(String raw) {
        throw new UnsupportedOperationException("未実装（CMP-261116-0722 PR-1 出陣で実装）");
    }

    /** 正規化済みキーワードをスペースで語に分割する（AND 条件）。 */
    public static List<String> terms(String normalized) {
        throw new UnsupportedOperationException("未実装（CMP-261116-0722 PR-1 出陣で実装）");
    }

    /** LIKE 用に {@code \} {@code %} {@code _} を {@code \} でエスケープする（ESCAPE '\' 前提）。 */
    public static String escapeLike(String term) {
        throw new UnsupportedOperationException("未実装（CMP-261116-0722 PR-1 出陣で実装）");
    }

    /**
     * FULLTEXT BOOLEAN MODE 用の検索式を作る。各語を必須のフレーズ {@code +"語"} にし、
     * 語中の {@code "} は取り除く（BOOLEAN 演算子を演算子として解釈させない）。空になった語は捨てる。
     */
    public static String toBooleanModeQuery(List<String> terms) {
        throw new UnsupportedOperationException("未実装（CMP-261116-0722 PR-1 出陣で実装）");
    }
}
