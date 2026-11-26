package com.mannschaft.app.common.util;

/**
 * チーム・組織名からURL用スラッグを生成するユーティリティ。
 *
 * <p>スラッグのルール: 3〜30文字の英小文字・数字・ハイフンのみ。
 * 先頭・末尾にハイフンは付かない。連続ハイフンは1つに圧縮する。</p>
 */
public final class SlugGenerator {

    /** スラッグの最大長。 */
    private static final int MAX_LENGTH = 30;

    /** サフィックス付与時のベース最大長（"-" + 最大4桁 で +5 バイト確保）。 */
    private static final int BASE_MAX_FOR_SUFFIX = 27;

    /** スラッグの最小長（これ未満はフォールバックを使う）。 */
    private static final int MIN_LENGTH = 3;

    /** ASCII英数字以外の文字パターン。 */
    private static final String NON_ALPHANUMERIC_PATTERN = "[^a-z0-9]+";

    /** 先頭・末尾のハイフンパターン。 */
    private static final String LEADING_TRAILING_HYPHENS = "^-+|-+$";

    /** フォールバック文字列（日本語のみなどでASCII部分が極端に短い場合）。 */
    private static final String FALLBACK_SLUG = "team";

    /** イベント用のフォールバック文字列（副題が空・日本語のみのとき）。 */
    private static final String EVENT_FALLBACK_SLUG = "event";

    /** ランダムサフィックスの桁数（"-" を含め 7 文字）。 */
    private static final int RANDOM_SUFFIX_LENGTH = 6;

    /** ランダムサフィックスに使う文字（英小文字・数字）。 */
    private static final String RANDOM_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private SlugGenerator() {}

    /**
     * {@link #generate(String)} の結果が固定フォールバック（{@code "team"}）か判定する。
     *
     * <p>日本語のみの名称などは全て同じ {@code "team"} に落ちるため、同時作成で衝突しやすい。</p>
     *
     * @param base {@link #generate(String)} の結果
     * @return フォールバック値なら true
     */
    public static boolean isFallback(String base) {
        return FALLBACK_SLUG.equals(base) || EVENT_FALLBACK_SLUG.equals(base);
    }

    /**
     * 名称からスラッグを生成する。
     *
     * <p>変換ルール:
     * <ol>
     *   <li>小文字化</li>
     *   <li>ASCII英数字以外をハイフンに変換</li>
     *   <li>先頭・末尾のハイフンを除去</li>
     *   <li>3文字未満の場合は {@code "team"} にフォールバック</li>
     *   <li>30文字超の場合は30文字に切り詰め</li>
     * </ol>
     * </p>
     *
     * @param name チーム名または組織名（null・空文字可）
     * @return 生成されたスラッグ（3〜30文字）
     */
    public static String generate(String name) {
        return generate(name, FALLBACK_SLUG);
    }

    /**
     * 名称からスラッグを生成する。ASCII 部分が 3 文字未満のときは指定したフォールバックを使う。
     *
     * <p>イベントのように {@code "team"} が不自然な用途では {@code "event"} などを渡す。
     * 変換ルールは {@link #generate(String)} と同じ。</p>
     *
     * @param name     名称（null・空文字可）
     * @param fallback 変換結果が 3 文字未満のときに返す値（3〜30 文字の有効な slug）
     * @return 生成されたスラッグ（3〜30文字）
     */
    public static String generate(String name, String fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String base = name.toLowerCase()
                .replaceAll(NON_ALPHANUMERIC_PATTERN, "-")
                .replaceAll(LEADING_TRAILING_HYPHENS, "");
        if (base.length() < MIN_LENGTH) {
            return fallback;
        }
        return base.substring(0, Math.min(base.length(), MAX_LENGTH));
    }

    /**
     * ベーススラッグに重複回避サフィックスを付与する。
     *
     * <p>例: {@code withSuffix("team-tokyo", 2)} → {@code "team-tokyo-2"}</p>
     *
     * <p>ベースが27文字超の場合は27文字に切り詰めてからサフィックスを付与し、
     * 合計が30文字以内に収まることを保証する。</p>
     *
     * @param base ベーススラッグ
     * @param n    サフィックス番号（1〜）
     * @return サフィックス付きスラッグ
     */
    public static String withSuffix(String base, int n) {
        String suffix = "-" + n;
        int maxBase = MAX_LENGTH - suffix.length();
        String trimmed = base.substring(0, Math.min(base.length(), maxBase));
        return trimmed + suffix;
    }

    /**
     * ベーススラッグにランダムな英小文字・数字のサフィックス（6文字）を付与する。
     *
     * <p>連番では並行作成や大量の予約済み slug に当たり続けるため、衝突時の再採番に使う。
     * 例: {@code "team-k3x9qa"}。合計 30 文字以内を保証する。</p>
     *
     * @param base ベーススラッグ
     * @return ランダムサフィックス付きスラッグ
     */
    public static String withRandomSuffix(String base) {
        StringBuilder sb = new StringBuilder("-");
        for (int i = 0; i < RANDOM_SUFFIX_LENGTH; i++) {
            sb.append(RANDOM_ALPHABET.charAt(RANDOM.nextInt(RANDOM_ALPHABET.length())));
        }
        String suffix = sb.toString();
        String trimmed = base.substring(0, Math.min(base.length(), MAX_LENGTH - suffix.length()));
        return trimmed + suffix;
    }
}
