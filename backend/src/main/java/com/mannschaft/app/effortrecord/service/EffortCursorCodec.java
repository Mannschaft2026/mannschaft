package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.EncryptionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * F06.4.1 個人一覧の opaque cursor（API 契約 §1）。
 *
 * <p>{@code recordedOn, createdAt, id} の位置に、<b>認証主体 ID・filter hash・失効時刻</b>を束ねて
 * AES-256-GCM で暗号化・認証する（{@link EncryptionService}）。平文は復号した本人にしか見えず、
 * 改竄・別 filter・別ユーザー・24 時間超過・長すぎる文字列は {@code COMMON_001}（400）にする。
 * 文字列は URL セーフ Base64（パディングなし）で最大 {@value #MAX_LENGTH} 字。</p>
 */
@Component
public class EffortCursorCodec {

    /** cursor 文字列の最大長（API 契約 §1）。 */
    public static final int MAX_LENGTH = 2048;
    /** 有効期間（API 契約 §1: 24 時間）。 */
    public static final Duration TTL = Duration.ofHours(24);

    private static final String VERSION = "e1";
    private static final String SEPARATOR = "|";

    private final EncryptionService encryption;
    private final Clock clock;

    public EffortCursorCodec(EncryptionService encryption, @Qualifier("utcClock") Clock clock) {
        this.encryption = encryption;
        this.clock = clock;
    }

    /** 復号した位置。 */
    public record Position(LocalDate recordedOn, Instant createdAt, UUID id) {
    }

    /** 一覧の filter を識別する hash（cursor を別 filter に流用させない）。 */
    public String filterHash(LocalDate dateFrom, LocalDate dateTo, UUID definitionUuid) {
        String canonical = (dateFrom == null ? "" : dateFrom) + SEPARATOR
                + (dateTo == null ? "" : dateTo) + SEPARATOR
                + (definitionUuid == null ? "" : definitionUuid);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encode(Long ownerUserId, String filterHash, Position position) {
        Instant expiresAt = clock.instant().plus(TTL);
        String plain = String.join(SEPARATOR, VERSION, String.valueOf(ownerUserId), filterHash,
                String.valueOf(expiresAt.getEpochSecond()), position.recordedOn().toString(),
                String.valueOf(micros(position.createdAt())), position.id().toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(encryption.encrypt(plain).getBytes(StandardCharsets.US_ASCII));
    }

    public Position decode(Long ownerUserId, String filterHash, String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_LENGTH) {
            throw invalid();
        }
        try {
            String standard = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            String[] parts = encryption.decrypt(standard).split("\\|", -1);
            if (parts.length != 7 || !VERSION.equals(parts[0])
                    || !String.valueOf(ownerUserId).equals(parts[1]) || !filterHash.equals(parts[2])) {
                throw invalid();
            }
            if (Instant.ofEpochSecond(Long.parseLong(parts[3])).isBefore(clock.instant())) {
                throw invalid();
            }
            long micros = Long.parseLong(parts[5]);
            return new Position(LocalDate.parse(parts[4]),
                    Instant.EPOCH.plus(micros, ChronoUnit.MICROS), UUID.fromString(parts[6]));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // 復号失敗（改竄・鍵違い）・書式不正・数値/日付/UUID 解析失敗はすべて不正 cursor。入力値は記録しない。
            throw invalid();
        }
    }

    private static long micros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.COMMON_001);
    }
}
