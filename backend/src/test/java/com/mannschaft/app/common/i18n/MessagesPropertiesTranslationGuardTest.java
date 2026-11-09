package com.mannschaft.app.common.i18n;

import com.mannschaft.app.config.I18nConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260909-0302: zh/ko/es/de の辞書に残る日本語と未翻訳コピーを検出する。
 *
 * <p>UTF-8 Reader を Java Properties に渡す既存番人と同じ読込を使う。Unicode escape と
 * 継続行を展開してから値を検査し、中国語の漢字だけでは日本語と判定しない。
 * 保証対象は辞書の固定文言であり、BatchEventListener の cause.getMessage() や
 * PersonalTimetableLinkSyncListener の予定タイトルなど、動的な引数は翻訳しない。</p>
 *
 * <p>既存の通知設定ラベル45鍵と API エラー9鍵の欠落は今回の本文翻訳から除外する。
 * 除外は現在の完全キー54個に閉じ、新しい本文の欠落や空値を免除しない。
 * 広告関連など base にない既存キーも削除せず、仮名・空値・実際の置換を検査する。</p>
 */
@DisplayName("CMP-260909-0302 messages zh/ko/es/de 翻訳番人")
class MessagesPropertiesTranslationGuardTest {

    private static final List<String> LOCALES = List.of("zh", "ko", "es", "de");
    private static final Pattern KANA = Pattern.compile("[ぁ-ゖァ-ヶ]");

    // 実在する6形式だけを扱う。汎用の MessageFormat パーサは自作しない。
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([0-2])(?:,number,#)?}");
    private static final Pattern ARGUMENT_CANDIDATE = Pattern.compile("\\{\\d+[^}]*}");
    private static final Object[] SAMPLE_ARGS = {7301, 8402, 9503};
    private static final Map<String, String> ALLOWED_MISSING = allowedMissing();
    private static final List<SameValueAllowance> ALLOWED_SAME_VALUES = sameValueAllowances();

    @ParameterizedTest(name = "{0}: 日本語と未翻訳コピーがない")
    @ValueSource(strings = {"zh", "ko", "es", "de"})
    void 辞書の固定文言に日本語が残らない(String tag) throws IOException {
        Properties base = loadResource("messages.properties");
        Properties translated = loadResource("messages_" + tag + ".properties");
        List<String> violations = findViolations(
                base, translated, tag, ALLOWED_MISSING, ALLOWED_SAME_VALUES);

        assertThat(violations)
                .as("%s %s: %s", tag, inventory(base, translated), violations)
                .isEmpty();
    }

    @Test
    void 固定欠落54鍵の内訳とJava実パーサによる棚卸を確認する() throws IOException {
        assertThat(ALLOWED_MISSING).hasSize(54);
        assertThat(ALLOWED_MISSING.keySet().stream()
                .filter(key -> key.startsWith("notification.type.")).count()).isEqualTo(45);
        assertThat(ALLOWED_MISSING.keySet().stream()
                .filter(key -> key.startsWith("error.shift.budget.")).count()).isEqualTo(9);
        assertThat(ALLOWED_MISSING.values()).allMatch(reason -> !reason.isBlank());
        assertThat(ALLOWED_SAME_VALUES).extracting(allowance -> allowance.locale() + ":" + allowance.key())
                .as("同じlocale・完全キーの同値例外を重複登録しない").doesNotHaveDuplicates();
        Properties base = loadResource("messages.properties");
        assertThat(base).isNotEmpty();
        for (String tag : LOCALES) {
            Properties translated = loadResource("messages_" + tag + ".properties");
            assertThat(translated).isNotEmpty();
            assertThat(missingKeys(base, translated)).as("%s 固定除外の増減・未使用", tag)
                    .containsExactlyInAnyOrderElementsOf(ALLOWED_MISSING.keySet());
            // 件数を恒久固定しない。正常な鍵追加を拒否せず、RED時の実測を出力する。
            System.out.println("[messages-translation-inventory] " + tag + " " + inventory(base, translated));
        }
    }

    @ParameterizedTest(name = "{0}: placeholder集合と実MessageSource置換")
    @ValueSource(strings = {"zh", "ko", "es", "de"})
    void placeholder集合を維持し実MessageSourceで置換できる(String tag) throws IOException {
        Properties base = loadResource("messages.properties");
        Properties translated = loadResource("messages_" + tag + ".properties");
        MessageSource source = new I18nConfig().messageSource();
        Locale locale = Locale.forLanguageTag(tag);
        for (String key : new TreeSet<>(translated.stringPropertyNames())) {
            String pattern = translated.getProperty(key);
            Set<String> tokens = placeholders(pattern);
            if (base.containsKey(key)) {
                assertThat(tokens).as("%s %s のplaceholder集合", tag, key)
                        .isEqualTo(placeholders(base.getProperty(key)));
            }
            String resolved = source.getMessage(key, tokens.isEmpty() ? null : SAMPLE_ARGS, locale);
            assertThat(resolved).as("%s %s の実MessageSource解決", tag, key).isNotBlank();
            if (!tokens.isEmpty()) {
                assertThat(resolved).as("%s %s は当該localeの実資源を解決", tag, key)
                        .isEqualTo(new MessageFormat(pattern, locale).format(SAMPLE_ARGS));
                for (String token : tokens) {
                    // 引用符でplaceholderがliteral化しても、実引数の挿入を要求して検出する。
                    assertThat(resolved).as("%s %s の %s が実際に置換される", tag, key, token)
                            .contains(new MessageFormat(token, locale).format(SAMPLE_ARGS));
                }
                assertThat(ARGUMENT_CANDIDATE.matcher(resolved).find())
                        .as("%s %s に未解決placeholderがない", tag, key).isFalse();
            }
        }
    }

    @Test
    void 自己検証_UnicodeEscapeと継続行の仮名も検出する() throws IOException {
        Properties base = fixture("frame=base\n");
        // JavaソースのUnicode変換より後、Properties.load に escape を渡す。
        Properties translated = fixture("frame=\\u30ab\\\n  \\u30ca\n");
        assertThat(translated.getProperty("frame")).isEqualTo("カナ");
        assertThat(findViolations(base, translated, "zh", Map.of(), List.of()))
                .containsExactly("仮名: frame");
    }

    @Test
    void 自己検証_仮名のないbase同値を検出し中国語の漢字を誤検出しない() throws IOException {
        Properties base = fixture("frame=欠席連絡\n");
        assertThat(findViolations(base, fixture("frame=欠席連絡\n"), "zh", Map.of(), List.of()))
                .containsExactly("base同値: frame");
        assertThat(findViolations(base, fixture("frame=缺席通知\n"), "zh", Map.of(), List.of()))
                .isEmpty();
    }

    @Test
    void 自己検証_空値と新規本文欠落と古くなった欠落除外を拒否する() throws IOException {
        Properties base = fixture("notification.new.body=本文\n");
        assertThat(findViolations(base, fixture("notification.new.body=  \n"), "de", Map.of(), List.of()))
                .contains("空値: notification.new.body");
        assertThat(findViolations(base, fixture(""), "de", Map.of(), List.of()))
                .containsExactly("欠落: notification.new.body");
        assertThat(findViolations(base, fixture("notification.new.body=Body\n"), "de",
                Map.of("notification.new.body", "過去の欠落"), List.of()))
                .containsExactly("未使用の欠落除外: notification.new.body");
    }

    @Test
    void 自己検証_同値例外はlocaleと完全キーと期待値と理由を要求する() throws IOException {
        Properties base = fixture("frame={0}\n");
        SameValueAllowance allowance = new SameValueAllowance("de", "frame", "{0}", "引数だけの本文");
        assertThat(findViolations(base, fixture("frame={0}\n"), "de", Map.of(), List.of(allowance)))
                .isEmpty();
        assertThat(findViolations(base, fixture("frame={0}\n"), "es", Map.of(), List.of(allowance)))
                .containsExactly("base同値: frame");
        assertThat(findViolations(base, fixture("frame=Body {0}\n"), "de", Map.of(), List.of(allowance)))
                .containsExactly("未使用の同値例外: frame");
        assertThat(findViolations(base, fixture("frame={0}\n"), "de", Map.of(),
                List.of(new SameValueAllowance("de", "frame", "{1}", "引数だけの本文"))))
                .contains("base同値: frame", "未使用の同値例外: frame");
        assertThatThrownBy(() -> new SameValueAllowance("de", "frame", "{0}", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SameValueAllowance("de", "frame", "カナ", "例外化不可"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 自己検証_Java実パーサでescapeされた重複キーも拒否する() {
        assertThatThrownBy(() -> fixture("frame=first\n\\u0066rame=second\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重複キー: frame");
    }

    private static List<String> findViolations(Properties base, Properties translated, String tag,
                                               Map<String, String> allowedMissing,
                                               List<SameValueAllowance> allowances) {
        List<String> violations = new ArrayList<>();
        Set<String> missing = missingKeys(base, translated);
        missing.stream().filter(key -> !allowedMissing.containsKey(key))
                .forEach(key -> violations.add("欠落: " + key));
        allowedMissing.keySet().stream().filter(key -> !missing.contains(key))
                .forEach(key -> violations.add("未使用の欠落除外: " + key));
        for (String key : new TreeSet<>(translated.stringPropertyNames())) {
            String value = translated.getProperty(key);
            if (value.isBlank()) {
                violations.add("空値: " + key);
            }
            if (KANA.matcher(value).find()) {
                violations.add("仮名: " + key);
            }
            if (value.equals(base.getProperty(key)) && allowances.stream().noneMatch(
                    allowance -> allowance.matches(tag, key, value))) {
                violations.add("base同値: " + key);
            }
        }
        for (SameValueAllowance allowance : allowances) {
            if (allowance.locale().equals(tag)
                    && (!allowance.expected().equals(translated.getProperty(allowance.key()))
                    || !allowance.expected().equals(base.getProperty(allowance.key())))) {
                violations.add("未使用の同値例外: " + allowance.key());
            }
        }
        return violations;
    }

    private static Set<String> missingKeys(Properties base, Properties translated) {
        Set<String> missing = new TreeSet<>(base.stringPropertyNames());
        missing.removeAll(translated.stringPropertyNames());
        return missing;
    }

    private static String inventory(Properties base, Properties translated) {
        long same = translated.stringPropertyNames().stream()
                .filter(key -> translated.getProperty(key).equals(base.getProperty(key))).count();
        long kana = translated.stringPropertyNames().stream()
                .filter(key -> KANA.matcher(translated.getProperty(key)).find()).count();
        Set<String> extra = new TreeSet<>(translated.stringPropertyNames());
        extra.removeAll(base.stringPropertyNames());
        return "base=" + base.size() + " locale=" + translated.size() + " same=" + same
                + " kana=" + kana + " missing=" + missingKeys(base, translated).size() + " extra=" + extra.size();
    }

    private static Set<String> placeholders(String pattern) {
        Set<String> tokens = new TreeSet<>();
        var candidates = ARGUMENT_CANDIDATE.matcher(pattern);
        while (candidates.find()) {
            String token = candidates.group();
            assertThat(PLACEHOLDER.matcher(token).matches())
                    .as("既存6形式以外のplaceholderは実引数に合う検証の追加が必要: %s", token).isTrue();
            tokens.add(token);
        }
        return tokens;
    }

    private static Properties loadResource(String resourceName) throws IOException {
        try (var input = MessagesPropertiesTranslationGuardTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertThat(input).as("必須資源 %s", resourceName).isNotNull();
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return load(reader);
            }
        }
    }

    private static Properties fixture(String text) throws IOException {
        try (Reader reader = new StringReader(text)) {
            return load(reader);
        }
    }

    private static Properties load(Reader reader) throws IOException {
        Properties properties = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalArgumentException("重複キー: " + key);
                }
                return super.put(key, value);
            }
        };
        properties.load(reader);
        return properties;
    }

    private static Map<String, String> allowedMissing() {
        Map<String, String> missing = new LinkedHashMap<>();
        // NotificationTypeLabelGuardTest が明記する既存fallback設計。将来のenum追加へ追随しない。
        List<String> labels = List.of(
                "ADMIN_SUCCESSION_FORCED", "ANNOUNCEMENT", "ATTENDANCE_REMINDER", "ATTENDANCE_RESPONDED",
                "BLOG_PUBLISHED", "CHAT_DM", "CHAT_MENTION", "DAILY_DIGEST", "INQUIRY_RECEIVED",
                "JOIN_REQUEST_APPROVED", "JOIN_REQUEST_RECEIVED", "JOIN_REQUEST_REJECTED", "MEMBER_JOINED",
                "MODULE_AVAILABLE", "NEW_DEVICE_LOGIN", "OWNERSHIP_TRANSFER_DECLINED", "OWNERSHIP_TRANSFER_OFFERED",
                "RECRUITMENT_PENALTY_APPLIED", "RECRUITMENT_PENALTY_LIFTED", "RESERVATION_CANCELLED",
                "RESERVATION_CANCELLED_BY_MEMBER", "RESERVATION_CONFIRMED", "RESERVATION_PENDING_APPROVAL",
                "RESERVATION_PENDING_EXPIRED", "RESERVATION_RECEIVED", "RESERVATION_REMINDER",
                "RESERVATION_WAITLIST_OPENING", "SAFETY_CHECK", "SCHEDULE_CANCELLED", "SCHEDULE_CREATED",
                "SCHEDULE_UPDATED", "SURVEY_CREATED", "SYSTEM_NOTICE", "TEAM_ORG_APPLICATION_APPROVED",
                "TEAM_ORG_APPLICATION_RECEIVED", "TEAM_ORG_APPLICATION_REJECTED", "TEAM_ORG_INVITE_ACCEPTED",
                "TEAM_ORG_INVITE_RECEIVED", "TEAM_ORG_MEMBERSHIP_LEFT", "TEAM_ORG_MEMBERSHIP_REMOVED",
                "TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM", "TEAM_ORG_PENDING_EXPIRED", "TIMELINE_MENTION",
                "TIMELINE_REPLY", "TODO_HANDED_OFF");
        for (String label : labels) {
            missing.put("notification.type." + label + ".label",
                    "NotificationPreferenceService用の既存通知設定ラベル。本文翻訳の範囲外");
        }
        for (String number : List.of("001", "002", "003", "004", "005", "006", "007", "008", "009")) {
            missing.put("error.shift.budget." + number,
                    "GlobalExceptionHandlerが返す既存APIエラー。通知本文ではない");
        }
        return Map.copyOf(missing);
    }

    private static List<SameValueAllowance> sameValueAllowances() {
        List<SameValueAllowance> allowances = new ArrayList<>();
        for (String tag : LOCALES) {
            allowances.add(new SameValueAllowance(tag, "notification.admin.batchFailed.body", "{0}",
                    "BatchEventListenerが渡す例外内容だけの本文。動的な例外文自体は本番人の保証対象外"));
            for (String key : List.of("notification.timetable.personalLink.revoked.body",
                    "notification.timetable.personalLink.synced.body")) {
                allowances.add(new SameValueAllowance(tag, key, "{0}（{1}）",
                        "予定名・日時の動的引数と括弧だけ。固定の日本語文言を含まない"));
            }
        }
        for (String tag : List.of("zh", "ko")) {
            allowances.add(new SameValueAllowance(tag, "notification.reservation.forceCancelled.reasonSuffix",
                    "（{0}）", "動的な取消理由を囲む括弧だけ。固定の日本語文言を含まない"));
        }
        for (String key : List.of("notification.onboarding.reminder.deadlinePattern",
                "notification.repairplan.termReminder.datePattern")) {
            allowances.add(new SameValueAllowance("zh", key, "yyyy年M月d日",
                    "中国語でも年・月・日を使う日付書式。漢字の一致だけでは日本語と判定しない"));
        }
        allowances.add(new SameValueAllowance("zh", "notification.reservation.forceCancelled.slotAtPattern",
                "M月d日 HH:mm", "中国語でも月・日を使う日時書式"));
        allowances.add(new SameValueAllowance("zh", "notification.survey.deadlineExtended.deadlinePattern",
                "yyyy年M月d日 HH:mm", "中国語でも年・月・日を使う日時書式"));
        return List.copyOf(allowances);
    }

    /** 正当な同値だけをlocale・完全キー・値・理由の組で許す。仮名の免除には使えない。 */
    private record SameValueAllowance(String locale, String key, String expected, String reason) {
        SameValueAllowance {
            if (!LOCALES.contains(locale) || key == null || key.isBlank() || expected == null
                    || expected.isBlank() || reason == null || reason.isBlank() || KANA.matcher(expected).find()) {
                throw new IllegalArgumentException("同値例外にはlocale・完全キー・仮名なし期待値・理由が必要");
            }
        }

        boolean matches(String tag, String propertyKey, String value) {
            return locale.equals(tag) && key.equals(propertyKey) && expected.equals(value);
        }
    }
}
