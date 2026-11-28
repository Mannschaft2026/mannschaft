package com.mannschaft.app.mail.outbox;

import com.mannschaft.app.common.EmailTemplateRenderer;
import com.mannschaft.app.common.EncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261123-1124 実機 D-1: 保護者同意系の templateKind がメール Worker で描画できる（"Unsupported templateKind" で
 * 永遠に再試行されない）。auth が enqueue する PARENTAL_CONSENT_* は全てスルー方式（subject/body を payload に持つ）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("保護者同意系 templateKind の描画対応（CMP-261123-1124 D-1）")
class ParentalConsentTemplateKindRenderTest {

    @Mock private EmailOutboxRepository repository;
    @Mock private EncryptionService encryption;
    @Mock private EmailTemplateRenderer renderer;
    @Mock private EmailTransport emailTransport;
    @Mock private SesExceptionClassifier classifier;
    @Spy private IdempotencyKeyGenerator keyGen = new IdempotencyKeyGenerator();
    @Spy private io.micrometer.core.instrument.MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @Mock private EmailOutboxMicrometerMetrics metrics;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks private EmailOutboxServiceImpl service;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "PARENTAL_CONSENT_INVITATION",
            "PARENTAL_CONSENT_APPROVED",
            "PARENTAL_CONSENT_REJECTED",
            "PARENTAL_CONSENT_EXPIRED_ACCOUNT_DELETED",
            "PARENTAL_CONSENT_RELEASED"})
    @DisplayName("subject と body を持つ payload は件名・本文として描画される")
    void rendersPassThrough(String kind) {
        Object rendered = ReflectionTestUtils.invokeMethod(service, "renderTemplate", kind, "ja",
                Map.of("subject", "件名", "body", "<p>本文</p>"));

        assertThat(rendered).isInstanceOf(EmailOutboxServiceImpl.RenderedEmail.class);
        EmailOutboxServiceImpl.RenderedEmail email = (EmailOutboxServiceImpl.RenderedEmail) rendered;
        assertThat(email.subject()).isEqualTo("件名");
        assertThat(email.html()).isEqualTo("<p>本文</p>");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "PARENTAL_CONSENT_INVITATION",
            "PARENTAL_CONSENT_APPROVED",
            "PARENTAL_CONSENT_REJECTED",
            "PARENTAL_CONSENT_EXPIRED_ACCOUNT_DELETED",
            "PARENTAL_CONSENT_RELEASED"})
    @DisplayName("EmailTemplateKind にも登録されている（骨格の三者同時更新）")
    void registeredInEnum(String kind) {
        assertThat(EmailTemplateKind.valueOf(kind)).isNotNull();
    }
}
