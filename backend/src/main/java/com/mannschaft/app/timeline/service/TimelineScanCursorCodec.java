package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * F06.4.1 タイムラインの走査上限で打ち切ったときの継続位置（暗号化した数値カーソル）の符号化（API 契約 §6）。
 *
 * <p>補充の走査を 1 回の要求あたり {@link TimelineEffortReadGuard#MAX_SCAN_ROWS} 行で打ち切ったとき、続きの位置は
 * 最後に読んだ行の投稿 ID になる。その行は閲覧者に見せない投稿のことがあるため、ID をそのまま {@code nextCursor} に
 * 出さず、鍵付きの擬似ランダム置換（52 bit の Feistel 網。ラウンド関数は HMAC-SHA256）で暗号化した <b>負の数</b> にする。
 * API の型（{@code nextCursor: number | null}・{@code cursor: Long}）は変えない。値は JavaScript の安全な整数
 * （絶対値 2^53 未満）に収まり、鍵なしに元の投稿 ID を逆算・推定できない。正のカーソルは従来どおり「見えた投稿の ID」。</p>
 *
 * <p>平文は「位置 40 bit ＋ 検査値 12 bit」。復号して検査値が合わない・範囲外の負のカーソルは改ざんとみなして
 * 400 {@code COMMON_001}（不正 cursor）にする。鍵は {@code mannschaft.jwt.secret} から HMAC-SHA256 で派生させた
 * 専用鍵（用途ラベル付き）で、他の用途の署名と共有しない。</p>
 */
@Component
public class TimelineScanCursorCodec {

    private static final int HALF_BITS = 26;
    private static final long HALF_MASK = (1L << HALF_BITS) - 1;
    private static final int TAG_BITS = 12;
    private static final long TAG_MASK = (1L << TAG_BITS) - 1;
    /** 位置に使える上限（2^40。投稿 ID の実用範囲を十分に覆う）。 */
    static final long MAX_POSITION = 1L << 40;
    /** 継続位置トークンの絶対値の上限（52 bit の置換の値域 + 1）。 */
    static final long MAX_TOKEN_MAGNITUDE = 1L << (2 * HALF_BITS);
    private static final int ROUNDS = 6;
    private static final String KEY_LABEL = "timeline-scan-cursor";

    private final byte[] key;

    public TimelineScanCursorCodec(@Value("${mannschaft.jwt.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("mannschaft.jwt.secret が未設定です（タイムラインの継続位置の鍵を作れない）");
        }
        this.key = hmac(secret.getBytes(StandardCharsets.UTF_8), KEY_LABEL.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 継続位置（最後に読んだ行の投稿 ID）を負の数のトークンにする。
     *
     * @param position 投稿 ID（0 以上 2^40 未満）
     * @return {@code -1 ... -2^52} の範囲のトークン
     */
    public long encode(long position) {
        if (position < 0 || position >= MAX_POSITION) {
            throw new IllegalStateException("継続位置が符号化できる範囲を超えています");
        }
        long plain = (position << TAG_BITS) | tag(position);
        return -(permute(plain) + 1);
    }

    /**
     * 要求の cursor を取得位置にする。null・0 以上はそのまま（見えた投稿の ID）、負なら継続位置トークンとして復号する。
     *
     * @throws BusinessException 復号できない（範囲外・検査値不一致）負の cursor は 400 COMMON_001
     */
    public Long toPosition(Long cursor) {
        if (cursor == null || cursor >= 0) {
            return cursor;
        }
        if (cursor < -MAX_TOKEN_MAGNITUDE) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        long plain = unpermute(-cursor - 1);
        long position = plain >>> TAG_BITS;
        if ((plain & TAG_MASK) != tag(position)) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        return position;
    }

    private long tag(long position) {
        byte[] mac = hmac(key, ByteBuffer.allocate(9).put((byte) 0xFF).putLong(position).array());
        return ((mac[0] & 0xFFL) << 8 | (mac[1] & 0xFFL)) & TAG_MASK;
    }

    private long permute(long value) {
        long left = (value >>> HALF_BITS) & HALF_MASK;
        long right = value & HALF_MASK;
        for (int round = 0; round < ROUNDS; round++) {
            long next = left ^ round(round, right);
            left = right;
            right = next;
        }
        return (left << HALF_BITS) | right;
    }

    private long unpermute(long value) {
        long left = (value >>> HALF_BITS) & HALF_MASK;
        long right = value & HALF_MASK;
        for (int round = ROUNDS - 1; round >= 0; round--) {
            long previous = right ^ round(round, left);
            right = left;
            left = previous;
        }
        return (left << HALF_BITS) | right;
    }

    private long round(int round, long half) {
        byte[] mac = hmac(key, ByteBuffer.allocate(9).put((byte) round).putLong(half).array());
        return ByteBuffer.wrap(mac).getLong() & HALF_MASK;
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 が使えません", e);
        }
    }
}
