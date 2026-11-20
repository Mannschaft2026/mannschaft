package com.mannschaft.app.common.i18n;

import com.mannschaft.app.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Set;

/**
 * エラーメッセージを利用者の言語へ解決する正本（CMP-261113-1507）。
 *
 * <h2>規則</h2>
 * <ul>
 *   <li><b>ja は enum 文言が正本</b>。{@link ErrorCode#getMessage()} をそのまま返す（引数があれば
 *       {@link MessageFormat} で差し込む）。ja 以外の未対応言語（fr 等）も同じく ja に倒す。
 *       {@code messages.properties}/{@code messages_ja.properties} に残る旧来の {@code error.*} 値は参照しない
 *       （enum と食い違う古い文言が出るのを防ぐ）。</li>
 *   <li>en/de/zh/ko/es は {@code messages_{lang}.properties} のキー {@link #messageKey(ErrorCode)} を引く。</li>
 *   <li>キーが無い・{@link MessageSource} が想定外の例外を投げた場合は enum 文言へフォールバックする。
 *       想定外の例外は握りつぶさず WARN で記録する（応答を 500 にしないことを優先）。</li>
 *   <li>引数なしのときは MessageFormat を通さない（{@code alwaysUseMessageFormat=false}）。訳文の
 *       アポストロフィは引数ありのときだけ {@code ''} と書く（番人 {@code ErrorMessageI18nGuardTest}）。</li>
 * </ul>
 *
 * <p>{@code GlobalExceptionHandler} と、DispatcherServlet より前で応答を書く早期拒否経路
 * （{@link EarlyErrorResponseWriter}）の双方から使う。</p>
 */
@Slf4j
public class ErrorMessageResolver {

    /** 訳 properties を持つ言語（ja は enum 文言が正本のため含めない）。 */
    public static final Set<String> TRANSLATED_LANGUAGES = Set.of("en", "de", "zh", "ko", "es");

    private final MessageSource messageSource;

    public ErrorMessageResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * ErrorCode から訳キーを導出する。{@code TEAM_001 → error.team.001}、
     * {@code AD_CAMPAIGN_NOT_FOUND → error.ad.campaign.not.FOUND}（末尾の区切り以降は大小を変えない。
     * 既存の訳キーがこの形で揃っているため規則を変えないこと）、区切りが無ければ {@code error.<小文字>}。
     */
    public static String messageKey(ErrorCode errorCode) {
        String code = errorCode.getCode();
        int lastUnderscore = code.lastIndexOf('_');
        if (lastUnderscore > 0) {
            String domain = code.substring(0, lastUnderscore).toLowerCase(Locale.ROOT).replace('_', '.');
            String number = code.substring(lastUnderscore + 1);
            return "error." + domain + "." + number;
        }
        return "error." + code.toLowerCase(Locale.ROOT);
    }

    /** 指定ロケールが訳 properties を引く対象か（ja・未対応言語・null は false）。 */
    public static boolean isTranslated(Locale locale) {
        return locale != null && TRANSLATED_LANGUAGES.contains(locale.getLanguage());
    }

    /** 現在のリクエストのロケール（{@link LocaleContextHolder}）で解決する。 */
    public String resolve(ErrorCode errorCode, Object... args) {
        return resolve(errorCode, LocaleContextHolder.getLocale(), args);
    }

    /** 指定ロケールで解決する。 */
    public String resolve(ErrorCode errorCode, Locale locale, Object... args) {
        return resolveKey(messageKey(errorCode), errorCode.getMessage(), locale, args);
    }

    /**
     * ErrorCode 以外の文言（例: マイルストーンロックの解除条件）を同じ規則で解決する。
     *
     * @param key         訳キー
     * @param jaMessage   ja の正本文言（引数があれば MessageFormat 形式）
     * @param locale      言語
     * @param args        差し込む値（無ければ空）
     */
    public String resolveKey(String key, String jaMessage, Locale locale, Object... args) {
        Object[] safeArgs = args == null ? new Object[0] : args;
        if (!isTranslated(locale)) {
            return formatJa(jaMessage, safeArgs);
        }
        try {
            return messageSource.getMessage(key, safeArgs.length == 0 ? null : safeArgs, locale);
        } catch (NoSuchMessageException e) {
            // 訳キー欠落は番人（ErrorMessageI18nGuardTest AC-6）が CI で止める。実行時は ja 正本に倒す。
            return formatJa(jaMessage, safeArgs);
        } catch (RuntimeException e) {
            // properties 破損・I/O 障害など想定外の失敗。エラー応答そのものを 500 にしないため ja 正本に倒すが、
            // 原因は握りつぶさず記録する。
            log.warn("エラーメッセージの解決に失敗したため ja 正本へフォールバック: key={}, locale={}", key, locale, e);
            return formatJa(jaMessage, safeArgs);
        }
    }

    private static String formatJa(String message, Object[] args) {
        return args.length == 0 ? message : MessageFormat.format(message, args);
    }
}
