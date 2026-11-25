package com.mannschaft.app.role;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.PendingInviteAdminQueryService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.role.service.InviteReissueRequestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261107-1833 PR3: 再招待依頼の受け手側の目録（通知種別・監査種別・文言・境界の宣言）。A1・A2・h・k の BE 部分。
 */
@DisplayName("CMP-261107-1833 PR3 再招待の目録（通知・監査・文言・境界）")
class InviteReissueCatalogTest {

    private static final List<String> MESSAGE_FILES = List.of("messages.properties", "messages_ja.properties",
            "messages_en.properties", "messages_zh.properties", "messages_ko.properties", "messages_es.properties",
            "messages_de.properties");

    private static Properties load(String file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = InviteReissueCatalogTest.class.getClassLoader().getResourceAsStream(file)) {
            assertThat(in).as(file).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVITE_REISSUED", "INVITE_REISSUE_DECLINED"})
    @DisplayName("h: 依頼者への通知種別 INVITE_REISSUED / INVITE_REISSUE_DECLINED（NORMAL・USER）")
    void notificationTypes(String name) {
        Optional<NotificationType> type = NotificationType.fromValue(name);
        assertThat(type).as("NotificationType." + name).isPresent();
        assertThat(type.get().getPriority()).isEqualTo(NotificationPriority.NORMAL);
        assertThat(type.get().getSourceType()).isEqualTo("USER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM_INVITE_REISSUED", "ORGANIZATION_INVITE_REISSUED", "TEAM_INVITE_REISSUE_DECLINED",
            "ORGANIZATION_INVITE_REISSUE_DECLINED"})
    @DisplayName("h: 監査種別 {TEAM|ORGANIZATION}_INVITE_REISSUED / _INVITE_REISSUE_DECLINED")
    void auditEventTypes(String name) {
        assertThat(Arrays.stream(AuditEventType.values()).map(Enum::name)).contains(name);
    }

    @Test
    @DisplayName("k: BE の messages 7 ファイルに、通知種別ラベル ×2 と inviteReissued.* / inviteReissueDeclined.* の文言がある")
    void messagesExist() throws IOException {
        List<String> keys = List.of("notification.type.INVITE_REISSUED.label", "notification.type.INVITE_REISSUE_DECLINED.label",
                "notification.inviteReissued.title", "notification.inviteReissued.body",
                "notification.inviteReissueDeclined.title", "notification.inviteReissueDeclined.body");
        for (String file : MESSAGE_FILES) {
            Properties p = load(file);
            for (String key : keys) {
                assertThat(p.getProperty(key)).as(file + " の " + key).isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("A1: 新しい司令塔・集計は @Transactional を持たない（越境 TX を張らない）")
    void noTransactionalOnOrchestrators() {
        for (Class<?> type : List.of(InviteReissueRequestService.class, PendingInviteAdminQueryService.class)) {
            assertThat(type.isAnnotationPresent(Transactional.class)).as(type.getSimpleName()).isFalse();
            for (Method m : type.getDeclaredMethods()) {
                assertThat(m.isAnnotationPresent(Transactional.class)).as(type.getSimpleName() + "#" + m.getName()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("i: InviteReissueRequestController は TEAM 用 3 本と ORG 用 3 本を別メソッドで持つ（計 6 本）")
    void controllerHasSixHandlers() throws ClassNotFoundException {
        Class<?> controller = Class.forName("com.mannschaft.app.role.controller.InviteReissueRequestController");
        long gets = Arrays.stream(controller.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(GetMapping.class)).count();
        long posts = Arrays.stream(controller.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(PostMapping.class)).count();
        assertThat(gets).isEqualTo(2);
        assertThat(posts).isEqualTo(4);
    }
}
