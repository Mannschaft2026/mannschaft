package com.mannschaft.app.supportaccess.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.supportaccess.SupportAccessNotificationKind;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第3回検分対応: 依頼のメール本文が、理由1000字が全部エスケープの対象になる記号でも、
 * メール outbox の payload 上限（{@code EmailOutboxServiceImpl.PAYLOAD_MAX_BYTES} = 8000 バイト）に収まること。
 */
@DisplayName("SupportAccessNoticeComposer: メール payload の上限の境界")
class SupportAccessNoticeComposerPayloadTest {

    /** EmailOutboxServiceImpl.PAYLOAD_MAX_BYTES（mail ドメインの package-private 定数と同じ値）。 */
    private static final int PAYLOAD_MAX_BYTES = 8000;
    private static final int REASON_MAX_LENGTH = 1000;
    private static final int DISPLAY_NAME_MAX_LENGTH = 50;
    private static final List<String> LOCALES = List.of("ja", "en", "zh", "ko", "es", "de");

    /**
     * 1文字あたりの膨らみが最大の文字: HTML の特殊文字（{@code "}→{@code &quot;} の6バイト、{@code '}→{@code &#39;}）、
     * HTML 4.01 の長い名前付き参照を持つ文字（{@code ϑ}→{@code &thetasym;}）、JSON で {@code \\u0001} になる制御文字、
     * UTF-8 で3バイトの文字。
     */
    static Stream<Arguments> worstCases() {
        List<Arguments> cases = new ArrayList<>();
        for (String ch : List.of("\"", "'", "&", "<", "ϑ", "\u0001", "漢")) {
            for (String locale : LOCALES) {
                for (SupportAccessNotificationKind kind : SupportAccessNoticeComposer.EMAIL_KINDS) {
                    cases.add(Arguments.of(ch, locale, kind));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "[{index}] 文字={0} 言語={1} 種別={2}")
    @MethodSource("worstCases")
    @DisplayName("理由1000字・表示名50字がすべて膨らむ文字でも、payload は 8000 バイト以下")
    void 理由が全部エスケープ対象でも上限内(String ch, String locale, SupportAccessNotificationKind kind) throws Exception {
        UserLocaleCache localeCache = mock(UserLocaleCache.class);
        when(localeCache.getLocale(any())).thenReturn(locale);
        SupportAccessUserDirectory directory = mock(SupportAccessUserDirectory.class);
        String longName = ch.repeat(DISPLAY_NAME_MAX_LENGTH);
        when(directory.findUser(any())).thenAnswer(inv ->
                Optional.of(new SupportAccessUserSnapshot(inv.getArgument(0), longName, "ACTIVE", false, false)));
        when(directory.findDeliverableEmail(any())).thenReturn(Optional.of("target@example.invalid"));
        ReloadableResourceBundleMessageSource messages = new ReloadableResourceBundleMessageSource();
        messages.setBasenames("classpath:messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        SupportAccessNoticeComposer composer = new SupportAccessNoticeComposer(localeCache, messages, directory,
                "https://mannschaft.app");

        SupportAccessNoticeComposer.PreparedEmail email = composer.prepareEmail(42L, kind, 9001L, 42L,
                ch.repeat(REASON_MAX_LENGTH)).orElseThrow();
        Map<String, String> payload = email.toRequest(UUID.randomUUID(), null).payloadVars();
        byte[] json = new ObjectMapper().writeValueAsBytes(payload);

        assertThat(json.length).as("payload のバイト数").isLessThanOrEqualTo(PAYLOAD_MAX_BYTES);
        // HTML の特殊文字は引き続きエスケープされる（XSS の防止は保つ）
        assertThat(payload.get("body")).doesNotContain("<script").doesNotContain("\"\"");
    }
}
