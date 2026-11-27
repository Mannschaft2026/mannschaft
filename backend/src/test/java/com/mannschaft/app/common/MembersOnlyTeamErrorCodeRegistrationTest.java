package com.mannschaft.app.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261124-0159 PR-01 試練: 本戦役の新規エラーコード 6 件と非公開化通知文の一括登録
 * （F00 §20.8・F01.2 02・F06.4 §15・F05.5・F00 §20.5.3）。
 *
 * <p>以後の PR は {@code GlobalExceptionHandler}・ErrorCode enum・messages を触らない（衝突源を PR-01 に寄せる）ため、
 * ここで「enum の定義・HTTP ステータス・6 言語の訳」を固定する。</p>
 */
@DisplayName("CMP-261124-0159 新規エラーコードと通知文の一括登録")
class MembersOnlyTeamErrorCodeRegistrationTest {

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', textBlock = """
            com.mannschaft.app.team.TeamErrorCode | TEAM_073 | FORBIDDEN | メンバー限定のチームにはサポーターを追加できません
            com.mannschaft.app.team.TeamErrorCode | TEAM_074 | CONFLICT | 公開範囲をメンバー限定にするには、サポーターとゲストが外れることの確認が必要です
            com.mannschaft.app.activity.ActivityErrorCode | ACTIVITY_024 | BAD_REQUEST | 終了日は開始日以降の日付にしてください
            com.mannschaft.app.activity.ActivityErrorCode | ACTIVITY_025 | BAD_REQUEST | 集計期間は 3 年以内で指定してください
            com.mannschaft.app.filesharing.FileSharingErrorCode | FILE_SHARING_021 | FORBIDDEN | メンバー限定のチームのファイルには共有リンクを作れません
            com.mannschaft.app.signage.SignageErrorCode | SIGNAGE_009 | FORBIDDEN | メンバー限定のチームの画面にはアクセストークンを発行できません
            """)
    @DisplayName("enum に定義され、宣言した HTTP ステータスに解決され、5 言語の訳を持つ")
    void 新規エラーコード(String enumClass, String code, HttpStatus status, String ja) throws Exception {
        ErrorCode errorCode = find(enumClass, code)
                .orElseThrow(() -> new AssertionError(enumClass + " に " + code + " が無い"));
        assertThat(errorCode.getMessage()).isEqualTo(ja);

        HttpStatus effective = Optional.ofNullable(statusMap().get(code))
                .orElse(errorCode.getSeverity() == ErrorCode.Severity.WARN ? HttpStatus.BAD_REQUEST : null);
        assertThat(effective).as("%s の実効ステータス", code).isEqualTo(status);

        String key = "error." + code.toLowerCase().replace('_', '.');
        for (String lang : new String[]{"en", "de", "zh", "ko", "es"}) {
            assertThat(load("messages_" + lang + ".properties").getProperty(key))
                    .as("%s の %s 訳", key, lang).isNotBlank().isNotEqualTo(ja);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"messages.properties", "messages_ja.properties", "messages_en.properties",
            "messages_de.properties", "messages_zh.properties", "messages_ko.properties", "messages_es.properties"})
    @DisplayName("非公開化で外したことを知らせる通知文（題名にチーム名 {0}）が 6 言語＋既定にある")
    void 非公開化の通知文(String file) {
        Properties props = load(file);
        assertThat(props.getProperty("notification.team.membersOnlyRemoved.title")).isNotBlank().contains("{0}");
        assertThat(props.getProperty("notification.team.membersOnlyRemoved.body")).isNotBlank();
    }

    @Test
    @DisplayName("既定と ja の通知文は設計書（F01.2 02）の文言どおり")
    void 通知文のja() {
        for (String file : new String[]{"messages.properties", "messages_ja.properties"}) {
            Properties props = load(file);
            assertThat(props.getProperty("notification.team.membersOnlyRemoved.title")).isEqualTo("{0} から外れました");
            assertThat(props.getProperty("notification.team.membersOnlyRemoved.body")).isEqualTo(
                    "チームの公開範囲が「メンバー限定」に変わったため、サポーター（ゲスト）としての登録が解除されました。");
        }
    }

    private static Optional<ErrorCode> find(String enumClass, String code) throws ClassNotFoundException {
        Class<?> type = Class.forName(enumClass);
        return Arrays.stream(type.getEnumConstants()).map(ErrorCode.class::cast)
                .filter(c -> c.getCode().equals(code)).findFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, HttpStatus> statusMap() throws ReflectiveOperationException {
        Field field = GlobalExceptionHandler.class.getDeclaredField("ERROR_CODE_STATUS_MAP");
        field.setAccessible(true);
        return (Map<String, HttpStatus>) field.get(null);
    }

    private static Properties load(String file) {
        Properties props = new Properties();
        try (Reader reader = new InputStreamReader(
                MembersOnlyTeamErrorCodeRegistrationTest.class.getClassLoader().getResourceAsStream(file),
                StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return props;
    }
}
