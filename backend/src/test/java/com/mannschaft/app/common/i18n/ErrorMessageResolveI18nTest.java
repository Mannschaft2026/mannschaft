package com.mannschaft.app.common.i18n;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.config.I18nConfig;
import com.mannschaft.app.todo.exception.MilestoneLockedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * CMP-261113-1507 試練: {@code GlobalExceptionHandler} の文言解決の単体試験（AC-8 応答経由・AC-11・AC-13・AC-14）。
 *
 * <p>本番と同じ {@link I18nConfig#messageSource()}（実 properties）をそのまま使い、
 * {@link LocaleContextHolder} に言語を置いてハンドラを直接呼ぶ。HTTP 境界の言語解決は
 * {@code ErrorMessageLocaleHttpIT} の管轄で、ここでは「解決された言語で正しい文言を作るか」に絞る。</p>
 */
@DisplayName("CMP-261113-1507 エラーメッセージ解決（AC-8/11/13/14）")
class ErrorMessageResolveI18nTest {

    private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uFF66-\\uFF9F]");

    private final MessageSource realMessageSource = new I18nConfig().messageSource();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(realMessageSource);

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    // ---------------------------------------------------------------- AC-8

    @Test
    @DisplayName("AC-8: 英訳のアポストロフィ（’ または '）が BE 応答の message にそのまま出る（引数なしは MessageFormat を通らない）")
    void AC8_英語のアポストロフィが応答にそのまま出る() {
        // 翻訳は typographic な ’ で統一している（ASCII の ' は MessageFormat の引用符と紛らわしいため）。
        Properties en = ErrorMessageI18nGuardTest.loadProperties("messages_en.properties");
        Optional<ErrorCode> sample = ErrorMessageI18nGuardTest.scanErrorCodes().stream()
                .filter(e -> !e.message().matches(".*\\{\\d+.*"))
                .filter(e -> en.getProperty(e.key(), "").matches(".*['’].*"))
                .map(e -> findConstant(e.enumName(), e.code()))
                .findFirst();
        assertThat(sample)
                .as("英訳にアポストロフィ（’ / '）を含む引数なしの error.* が1件も無い（全コードの英訳が未整備）")
                .isPresent();
        ErrorCode code = sample.get();
        String expected = en.getProperty(ErrorMessageI18nGuardTest.deriveKey(code));

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        ResponseEntity<ErrorResponse> response = handler.handleBusinessException(new BusinessException(code));

        assertThat(response.getBody().getError().getMessage()).isEqualTo(expected).containsPattern("['’]")
                .doesNotContain("''").doesNotContain("’’");
    }

    @Test
    @DisplayName("AC-8: ASCII の ' は引数なしなら 1 個のまま、引数ありなら '' と書いたものが 1 個になって出る（二重化も消失もしない）")
    void AC8_ASCIIアポストロフィが引数の有無によらず1個で出る() {
        org.springframework.context.support.StaticMessageSource source =
                new org.springframework.context.support.StaticMessageSource();
        source.addMessage("error.zzi18ntest.997", Locale.ENGLISH, "You can't do this");
        source.addMessage("error.zzi18ntest.996", Locale.ENGLISH, "You can''t join more than {0} villages");
        GlobalExceptionHandler local = new GlobalExceptionHandler(source);
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        ResponseEntity<ErrorResponse> plain = local.handleBusinessException(
                new BusinessException(testCode("ZZI18NTEST_997", "これはできません")));
        ResponseEntity<ErrorResponse> formatted = local.handleBusinessException(BusinessException.withMessageArgs(
                testCode("ZZI18NTEST_996", "参加できる村は {0} 村までです"), null, 5));

        assertThat(plain.getBody().getError().getMessage()).isEqualTo("You can't do this");
        assertThat(formatted.getBody().getError().getMessage()).isEqualTo("You can't join more than 5 villages");
    }

    // ---------------------------------------------------------------- AC-11

    @Test
    @DisplayName("AC-11: ResponseStatusException の日本語 reason は en で日本語を含まず、ステータスとコードは保たれる")
    void AC11_ResponseStatusExceptionの理由文がenで日本語を含まない() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        ResponseEntity<ErrorResponse> response = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "画像は 30 枚までです"));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_001");
        assertThat(response.getBody().getError().getMessage()).isNotBlank().doesNotContainPattern(JAPANESE);
    }

    @Test
    @DisplayName("AC-11: ResponseStatusException の reason は ja では従来どおりそのまま返る（回帰防止）")
    void AC11_ResponseStatusExceptionの理由文はjaで従来どおり() {
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        ResponseEntity<ErrorResponse> response = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "画像は 30 枚までです"));

        assertThat(response.getBody().getError().getMessage()).isEqualTo("画像は 30 枚までです");
    }

    @Test
    @DisplayName("AC-11: マイルストーンロック応答は en で message・解除条件に日本語を含まず、ID とタイトルの埋め込み値は保持する")
    void AC11_マイルストーンロック応答がenで日本語を含まず埋め込み値を保持する() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        ResponseEntity<ErrorResponse> response = handler.handleMilestoneLocked(new MilestoneLockedException(42L, "Phase A"));

        ErrorResponse.ErrorDetail error = response.getBody().getError();
        assertThat(response.getStatusCode().value()).isEqualTo(423);
        assertThat(error.getCode()).isEqualTo("MILESTONE_LOCKED");
        assertThat(error.getMessage()).doesNotContainPattern(JAPANESE).contains("42").contains("Phase A");
        Map<String, String> details = new java.util.LinkedHashMap<>();
        error.getFieldErrors().forEach(f -> details.put(f.getField(), f.getMessage()));
        assertThat(details).containsEntry("milestone_id", "42");
        assertThat(details.get("unlock_condition")).doesNotContainPattern(JAPANESE).contains("Phase A");
    }

    @Test
    @DisplayName("AC-11: マイルストーンロック応答は ja では従来の日本語文言（回帰防止）")
    void AC11_マイルストーンロック応答はjaで従来どおり() {
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        ResponseEntity<ErrorResponse> response = handler.handleMilestoneLocked(new MilestoneLockedException(42L, "第一期"));

        assertThat(response.getBody().getError().getMessage()).contains("42").contains("第一期");
        assertThat(response.getBody().getError().getFieldErrors())
                .anySatisfy(f -> assertThat(f.getMessage()).isEqualTo("前マイルストーン『第一期』を完了"));
    }

    // ---------------------------------------------------------------- AC-13

    @Test
    @DisplayName("AC-13: 訳キーが欠けたコードは en でも enum の日本語文言へフォールバックする")
    void AC13_キー欠落コードはenumの日本語へフォールバックする() {
        ErrorCode missing = testCode("ZZI18NTEST_999", "試練用の欠落コードです");
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        ResponseEntity<ErrorResponse> response = handler.handleBusinessException(new BusinessException(missing));

        assertThat(response.getBody().getError().getMessage()).isEqualTo("試練用の欠落コードです");
    }

    // ---------------------------------------------------------------- AC-14

    @Test
    @DisplayName("AC-14: MessageSource が想定外の RuntimeException を投げても 500 にせず enum の日本語へフォールバックする")
    void AC14_MessageSourceの想定外例外でもenumの日本語へフォールバックする() {
        GlobalExceptionHandler broken = new GlobalExceptionHandler(new ThrowingMessageSource());
        ErrorCode code = testCode("ZZI18NTEST_998", "試練用のフォールバック文言です");
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        assertThatCode(() -> broken.handleBusinessException(new BusinessException(code))).doesNotThrowAnyException();
        ResponseEntity<ErrorResponse> response = broken.handleBusinessException(new BusinessException(code));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getError().getMessage()).isEqualTo("試練用のフォールバック文言です");
    }

    // ---------------------------------------------------------------- 補助

    private static ErrorCode testCode(String code, String message) {
        return new ErrorCode() {
            @Override public String getCode() { return code; }
            @Override public String getMessage() { return message; }
            @Override public Severity getSeverity() { return Severity.WARN; }
        };
    }

    private static ErrorCode findConstant(String enumName, String code) {
        try {
            for (Object c : Class.forName(enumName).getEnumConstants()) {
                if (((ErrorCode) c).getCode().equals(code)) return (ErrorCode) c;
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException(enumName + "#" + code);
    }

    /** 文言解決のたびに想定外の実行時例外を投げる MessageSource（壊れた properties・I/O 障害の模擬）。 */
    private static final class ThrowingMessageSource implements MessageSource {
        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            throw new IllegalStateException("模擬障害");
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            throw new IllegalStateException("模擬障害");
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
            throw new IllegalStateException("模擬障害");
        }
    }
}
