package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.dashboard.controller.DashboardController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.util.Locale;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261113-1507: DashboardController のローカル {@code @ExceptionHandler} が返す message が
 * 利用者の言語（訳 properties）で解決されることを固定する。
 */
@DisplayName("GET /dashboard/activity 型変換エラーの message は利用者の言語で返る")
class DashboardActivityErrorLocaleTest {

    private static final String PATH = "/api/v1/dashboard/activity";

    private MockMvc build(Locale locale) {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("error.schedule.feed.001", Locale.ENGLISH, "The cursor is invalid");
        DashboardController controller = new DashboardController(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(controller, "messageSource", messages);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(messages))
                .setLocaleResolver(new FixedLocaleResolver(locale))
                .build();
    }

    @Test
    @DisplayName("en: cursor=abc の message は英訳（コードは SCHEDULE_FEED_001 のまま）")
    void en_returnsTranslatedMessage() throws Exception {
        build(Locale.ENGLISH).perform(get(PATH).param("cursor", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_FEED_001"))
                .andExpect(jsonPath("$.error.message").value("The cursor is invalid"));
    }

    @Test
    @DisplayName("ja: cursor=abc の message は enum の日本語文言")
    void ja_returnsEnumMessage() throws Exception {
        build(Locale.JAPANESE).perform(get(PATH).param("cursor", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_FEED_001"))
                .andExpect(jsonPath("$.error.message").value(
                        com.mannschaft.app.dashboard.ScheduleFeedErrorCode.INVALID_CURSOR.getMessage()));
    }
}
