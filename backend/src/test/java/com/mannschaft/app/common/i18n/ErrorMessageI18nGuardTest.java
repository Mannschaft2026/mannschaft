package com.mannschaft.app.common.i18n;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CMP-261113-1507 試練（BE エラーメッセージ多言語化・案A）の番人群。
 *
 * <h2>何を保証するか</h2>
 * <ul>
 *   <li>AC-6: 全 ErrorCode 実装 enum の全コードについて、{@code messages_{en,de,zh,ko,es}.properties} に
 *       {@code GlobalExceptionHandler.resolveMessage} と<b>同じ規則で導出したキー</b>が存在する。
 *       あわせて、同一 properties 内で導出キーが重複定義されていない（後勝ちで訳が黙って入れ替わる事故の防止）。</li>
 *   <li>AC-7: en/de/zh/ko/es の {@code error.*} 値にひらがな・カタカナ（日本語の取り残し）が無い。zh 以外は漢字も無い。</li>
 *   <li>AC-8: 訳のプレースホルダ（{@code {0}} 等）が原文（enum 文言）と一致する。原文に無いプレースホルダは訳にも出ない。
 *       アポストロフィの扱いが MessageFormat 適用の有無と整合する。</li>
 *   <li>AC-9: 末尾が数字でないコード（{@code AD_CAMPAIGN_NOT_FOUND} 等）も含め、全コードの導出キーが互いに衝突しない。</li>
 * </ul>
 *
 * <h2>キー導出は本番の挙動から取る</h2>
 * <p>キー規則（{@code TEAM_001 → error.team.001}）を試験側に複製すると、実装が規則を変えた瞬間に番人と本番が
 * 食い違う。そこで {@link GlobalExceptionHandler#handleBusinessException(BusinessException)} を
 * 記録用 {@link MessageSource} 付きで呼び、本番が実際に問い合わせたキーを採取する（{@link #deriveKey}）。</p>
 *
 * <h2>対象の限定（翻訳準備隊の実測に基づく）</h2>
 * <p>properties には旧形式の孤児キー（{@code error.ad_campaign.*} 等・{@code error.not_found} 等の一般キー）が
 * 各言語に残る。重複番人（AC-6 後半）は「ErrorCode から導出したキー」だけを対象にし、孤児キーで誤検知しない。
 * プレースホルダ番人（AC-8）は {@code {0}} の一律禁止にせず、原文と訳の一致で判定する
 * （ja 原文で {@code {0}} を持つ {@code VILLAGE_012} は訳も {@code {0}} を保持するのが正しい）。</p>
 *
 * <p>本テストはファイルを読むだけで書き換えない。ArchUnit 凍結ストアにも触れない。</p>
 */
@DisplayName("CMP-261113-1507 エラーメッセージ多言語化の番人（AC-6〜AC-9）")
class ErrorMessageI18nGuardTest {

    /** 翻訳を持つべき言語（ja は enum 文言が正本のため対象外）。 */
    static final List<String> TRANSLATED_LANGS = List.of("en", "de", "zh", "ko", "es");

    /** 走査が壊れて 0 件合格する偽 green の防止（実測: 139 enum / 2,419 定数）。 */
    private static final int MIN_ENUMS = 130;
    private static final int MIN_CONSTANTS = 2_300;

    private static final Pattern KANA = Pattern.compile("[\\u3040-\\u30FF\\u31F0-\\u31FF\\uFF66-\\uFF9F]");
    private static final Pattern CJK_IDEOGRAPH = Pattern.compile("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)(?:,[^}]*)?}");

    /** 全 ErrorCode 定数（enum 名付き）。 */
    private static List<CodeEntry> entries;
    /** 言語 → 導出キーの論理行出現回数（重複検出用）。 */
    private static final Map<String, Map<String, Integer>> KEY_OCCURRENCES = new LinkedHashMap<>();
    /** 言語 → Properties（値の解釈は MessageSource と同じく UTF-8 + \\uXXXX 展開）。 */
    private static final Map<String, Properties> PROPERTIES = new LinkedHashMap<>();

    record CodeEntry(String enumName, String code, String message, String key) { }

    @BeforeAll
    static void loadAll() {
        entries = scanErrorCodes();
        for (String lang : TRANSLATED_LANGS) {
            String file = "messages_" + lang + ".properties";
            PROPERTIES.put(lang, loadProperties(file));
            KEY_OCCURRENCES.put(lang, countKeyOccurrences(file));
        }
    }

    // =========================================================================
    // 偽 green 防止
    // =========================================================================

    @Test
    @DisplayName("AC-6前提: クラスパス走査で ErrorCode 実装 enum と定数が下限以上見つかる（偽 green 防止）")
    void AC6前提_走査そのものが機能している() {
        long enumCount = entries.stream().map(CodeEntry::enumName).distinct().count();
        assertThat(enumCount).as("ErrorCode 実装 enum の検出数").isGreaterThanOrEqualTo(MIN_ENUMS);
        assertThat(entries.size()).as("ErrorCode 定数の検出数").isGreaterThanOrEqualTo(MIN_CONSTANTS);
        assertThat(entries).as("導出キーが採取できていない定数がある")
                .allSatisfy(e -> assertThat(e.key()).startsWith("error."));
    }

    // =========================================================================
    // AC-6 キーの網羅と重複禁止
    // =========================================================================

    @Test
    @DisplayName("AC-6: 全 ErrorCode の導出キーが messages_{en,de,zh,ko,es}.properties に存在する")
    void AC6_全ErrorCodeの導出キーが5言語に存在する() {
        Map<String, List<String>> missingByLang = new LinkedHashMap<>();
        for (String lang : TRANSLATED_LANGS) {
            Properties props = PROPERTIES.get(lang);
            List<String> missing = new ArrayList<>();
            for (String key : distinctKeys()) {
                if (!props.containsKey(key)) missing.add(key);
            }
            if (!missing.isEmpty()) missingByLang.put(lang, missing);
        }
        if (!missingByLang.isEmpty()) {
            StringBuilder sb = new StringBuilder("ErrorCode の導出キーが訳 properties に無い（日本語へフォールバックしてしまう）:\n");
            missingByLang.forEach((lang, keys) -> sb.append("  ").append(lang).append(": ").append(keys.size())
                    .append(" 件 / ").append(distinctKeys().size()).append(" 件中。例: ")
                    .append(keys.subList(0, Math.min(10, keys.size()))).append('\n'));
            fail(sb.toString());
        }
    }

    @Test
    @DisplayName("AC-6: 同一 properties 内で ErrorCode 導出キーが重複定義されていない（孤児キーは対象外）")
    void AC6_導出キーが同一properties内で重複しない() {
        Set<String> derived = distinctKeys();
        List<String> failures = new ArrayList<>();
        for (String lang : TRANSLATED_LANGS) {
            KEY_OCCURRENCES.get(lang).forEach((key, count) -> {
                if (count > 1 && derived.contains(key)) {
                    failures.add(lang + ": " + key + " が " + count + " 回定義されている");
                }
            });
        }
        assertThat(failures).as("導出キーの重複定義（後勝ちで訳が黙って入れ替わる）").isEmpty();
    }

    // =========================================================================
    // AC-7 日本語の取り残し禁止
    // =========================================================================

    @Test
    @DisplayName("AC-7: en/de/zh/ko/es の error.* 値にひらがな・カタカナを含まない。zh 以外は漢字も含まない")
    void AC7_訳にひらがなカタカナ_zh以外は漢字も含まない() {
        List<String> failures = new ArrayList<>();
        for (String lang : TRANSLATED_LANGS) {
            Properties props = PROPERTIES.get(lang);
            for (String key : new TreeSet<>(props.stringPropertyNames())) {
                if (!key.startsWith("error.")) continue;
                String value = props.getProperty(key);
                if (KANA.matcher(value).find()) {
                    failures.add(lang + ": " + key + " にかなが残っている: " + value);
                } else if (!"zh".equals(lang) && CJK_IDEOGRAPH.matcher(value).find()) {
                    failures.add(lang + ": " + key + " に漢字が残っている: " + value);
                }
            }
        }
        assertThat(failures).as("訳 properties の日本語取り残し").isEmpty();
    }

    // =========================================================================
    // AC-8 プレースホルダとアポストロフィ
    // =========================================================================

    @Test
    @DisplayName("AC-8: 訳のプレースホルダ集合が原文（enum 文言）と一致し、原文に無いプレースホルダが訳に現れない")
    void AC8_訳のプレースホルダが原文と一致する() {
        List<String> failures = new ArrayList<>();
        Map<String, CodeEntry> byKey = firstEntryByKey();
        for (String lang : TRANSLATED_LANGS) {
            Properties props = PROPERTIES.get(lang);
            byKey.forEach((key, entry) -> {
                String value = props.getProperty(key);
                if (value == null) return; // 欠落は AC-6 の管轄
                List<String> expected = placeholders(entry.message());
                List<String> actual = placeholders(value);
                if (!expected.equals(actual)) {
                    failures.add(lang + ": " + key + " 原文=" + expected + " 訳=" + actual + " 値=" + value);
                }
            });
        }
        assertThat(failures).as("プレースホルダの不一致（未解決の {0} がそのまま利用者に出る）").isEmpty();
    }

    @Test
    @DisplayName("AC-8: アポストロフィが MessageFormat 適用の有無と整合する"
            + "（引数なし訳に '' を書くと二重表示、引数あり訳に単独 ' を書くと消える）")
    void AC8_アポストロフィの書き方がMessageFormat適用有無と整合する() {
        List<String> failures = new ArrayList<>();
        Map<String, CodeEntry> byKey = firstEntryByKey();
        for (String lang : TRANSLATED_LANGS) {
            Properties props = PROPERTIES.get(lang);
            byKey.forEach((key, entry) -> {
                String value = props.getProperty(key);
                if (value == null) return;
                boolean formatted = !placeholders(entry.message()).isEmpty();
                if (formatted && value.replace("''", "").contains("'")) {
                    failures.add(lang + ": " + key + " は MessageFormat を通るのに単独の ' がある（表示で消える）: " + value);
                }
                if (!formatted && value.contains("''")) {
                    failures.add(lang + ": " + key + " は MessageFormat を通らないのに '' がある（二重に表示される）: " + value);
                }
            });
        }
        assertThat(failures).isEmpty();
    }

    // =========================================================================
    // AC-9 導出キーの衝突禁止
    // =========================================================================

    @Test
    @DisplayName("AC-9: 末尾が数字でないコードを含め、異なるコードの導出キーが衝突しない")
    void AC9_異なるコードの導出キーが衝突しない() {
        Map<String, Set<String>> codesByKey = new TreeMap<>();
        for (CodeEntry e : entries) {
            codesByKey.computeIfAbsent(e.key(), k -> new TreeSet<>()).add(e.code());
        }
        List<String> collisions = new ArrayList<>();
        codesByKey.forEach((key, codes) -> {
            if (codes.size() > 1) collisions.add(key + " ← " + codes);
        });
        assertThat(collisions).as("導出キーの衝突（別コードの訳が返る）").isEmpty();
    }

    @Test
    @DisplayName("AC-9: 同じコード文字列を複数 enum が定義する場合は原文も同一である（同じキーに別文言が畳まれない）")
    void AC9_同一コード文字列の原文が一致する() {
        Map<String, Set<String>> messagesByCode = new TreeMap<>();
        Map<String, Set<String>> enumsByCode = new TreeMap<>();
        for (CodeEntry e : entries) {
            messagesByCode.computeIfAbsent(e.code(), k -> new LinkedHashSet<>()).add(e.message());
            enumsByCode.computeIfAbsent(e.code(), k -> new TreeSet<>()).add(e.enumName());
        }
        List<String> failures = new ArrayList<>();
        messagesByCode.forEach((code, messages) -> {
            if (messages.size() > 1) failures.add(code + " " + enumsByCode.get(code) + " 原文=" + messages);
        });
        assertThat(failures).as("同一コードで原文が異なる（1 つの訳キーでは両方を正しく訳せない）").isEmpty();
    }

    @Test
    @DisplayName("AC-9: 末尾が数字でないコード AD_CAMPAIGN_NOT_FOUND の導出キーが 1 つに定まり、他コードと衝突しない")
    void AC9_末尾非数字コードの導出キーが一意() {
        CodeEntry adCampaign = entries.stream().filter(e -> "AD_CAMPAIGN_NOT_FOUND".equals(e.code()))
                .findFirst().orElseThrow(() -> new AssertionError("AD_CAMPAIGN_NOT_FOUND が見つからない（検体の前提崩れ）"));
        long sameKey = entries.stream().filter(e -> e.key().equals(adCampaign.key()))
                .map(CodeEntry::code).distinct().count();
        assertThat(sameKey).isEqualTo(1);
    }

    // =========================================================================
    // 補助
    // =========================================================================

    static Set<String> distinctKeys() {
        Set<String> keys = new TreeSet<>();
        entries.forEach(e -> keys.add(e.key()));
        return keys;
    }

    private static Map<String, CodeEntry> firstEntryByKey() {
        Map<String, CodeEntry> byKey = new TreeMap<>();
        entries.forEach(e -> byKey.putIfAbsent(e.key(), e));
        return byKey;
    }

    private static List<String> placeholders(String text) {
        List<String> found = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text == null ? "" : text);
        while (m.find()) found.add(m.group(1));
        Collections.sort(found);
        return found;
    }

    /** 全 ErrorCode 実装 enum をクラスパス走査で列挙する（ソース書式に依存しない）。 */
    static List<CodeEntry> scanErrorCodes() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isIndependent() && !beanDefinition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(ErrorCode.class));
        List<CodeEntry> result = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.mannschaft.app")) {
            Class<?> type;
            try {
                type = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            if (!type.isEnum() || !ErrorCode.class.isAssignableFrom(type)) continue;
            for (Object constant : type.getEnumConstants()) {
                ErrorCode code = (ErrorCode) constant;
                result.add(new CodeEntry(type.getName(), code.getCode(), code.getMessage(), deriveKey(code)));
            }
        }
        return result;
    }

    /**
     * 本番の {@code GlobalExceptionHandler.resolveMessage} が実際に問い合わせるキーを採取する。
     * 規則を試験側へ複製しないため、記録用 MessageSource を差して業務例外ハンドラを通す。
     */
    static String deriveKey(ErrorCode code) {
        RecordingMessageSource recorder = new RecordingMessageSource();
        new GlobalExceptionHandler(recorder).handleBusinessException(new BusinessException(code));
        return recorder.lastCode;
    }

    /** 問い合わせられたキーを記録し、常に「キー無し」を返す MessageSource。 */
    static final class RecordingMessageSource implements MessageSource {
        String lastCode;

        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            lastCode = code;
            return defaultMessage;
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            lastCode = code;
            throw new NoSuchMessageException(code, locale);
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
            String[] codes = resolvable.getCodes();
            lastCode = codes == null || codes.length == 0 ? null : codes[0];
            throw new NoSuchMessageException(lastCode, locale);
        }
    }

    static Properties loadProperties(String file) {
        Properties props = new Properties();
        try (Reader reader = new InputStreamReader(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return props;
    }

    /** properties の論理行（継続行を連結）からキーを取り出し、出現回数を数える。 */
    private static Map<String, Integer> countKeyOccurrences(String file) {
        List<String> lines;
        try {
            lines = List.of(new String(new ClassPathResource(file).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).split("\\r?\\n", -1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Integer> counts = new TreeMap<>();
        StringBuilder logical = new StringBuilder();
        for (String raw : lines) {
            String line = raw.stripLeading();
            if (logical.length() == 0 && (line.isEmpty() || line.startsWith("#") || line.startsWith("!"))) continue;
            if (endsWithOddBackslashes(line)) {
                logical.append(line, 0, line.length() - 1);
                continue;
            }
            logical.append(line);
            String key = extractKey(logical.toString());
            if (!key.isEmpty()) counts.merge(key, 1, Integer::sum);
            logical.setLength(0);
        }
        return counts;
    }

    private static boolean endsWithOddBackslashes(String line) {
        int n = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) n++;
        return n % 2 == 1;
    }

    private static String extractKey(String logicalLine) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < logicalLine.length(); i++) {
            char c = logicalLine.charAt(i);
            if (c == '\\' && i + 1 < logicalLine.length()) {
                key.append(logicalLine.charAt(++i));
                continue;
            }
            if (c == '=' || c == ':' || Character.isWhitespace(c)) break;
            key.append(c);
        }
        return key.toString();
    }
}
