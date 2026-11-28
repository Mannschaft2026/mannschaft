package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F06.4.1 タイムラインの継続位置トークン（暗号化した負の数のカーソル）の単体試験（CMP-261115-2145）。 */
@DisplayName("TimelineScanCursorCodec")
class TimelineScanCursorCodecTest {

    private final TimelineScanCursorCodec codec = new TimelineScanCursorCodec("unit-test-secret-key-for-timeline-cursor-0123456789");

    @Test
    @DisplayName("符号化した位置は負の数で JS の安全な整数に収まり、復号すると元の位置に戻る")
    void 往復() {
        for (long position : new long[] {0L, 1L, 2L, 1234L, 1235L, 987_654_321L, TimelineScanCursorCodec.MAX_POSITION - 1}) {
            long token = codec.encode(position);
            assertThat(token).isNegative();
            assertThat(Math.abs(token)).isLessThanOrEqualTo(TimelineScanCursorCodec.MAX_TOKEN_MAGNITUDE)
                    .isLessThan(1L << 53);
            assertThat(codec.toPosition(token)).isEqualTo(position);
        }
    }

    @Test
    @DisplayName("隣り合う位置のトークンは近い値にならない（値から位置を推定できない）")
    void 推定できない() {
        long a = codec.encode(1_000_000L);
        long b = codec.encode(1_000_001L);
        assertThat(Math.abs(a - b)).isGreaterThan(1_000_000L);
        assertThat(Math.abs(a)).isNotEqualTo(1_000_000L).isNotEqualTo(1_000_001L);
    }

    @Test
    @DisplayName("鍵が違えば同じ位置でも別のトークンになり、他の鍵のトークンは検査値で拒否される")
    void 鍵依存() {
        TimelineScanCursorCodec other = new TimelineScanCursorCodec("another-secret-key-for-timeline-cursor-0123456789");
        int rejected = 0;
        for (long position = 100; position < 200; position++) {
            long token = codec.encode(position);
            assertThat(other.encode(position)).isNotEqualTo(token);
            try {
                assertThat(other.toPosition(token)).isNotEqualTo(position);
            } catch (BusinessException e) {
                rejected++;
            }
        }
        assertThat(rejected).as("12 bit の検査値でほぼすべて拒否").isGreaterThan(90);
    }

    @Test
    @DisplayName("null と 0 以上はそのまま。範囲外の負の数は 400 COMMON_001")
    void 正のカーソルと範囲外() {
        assertThat(codec.toPosition(null)).isNull();
        assertThat(codec.toPosition(0L)).isZero();
        assertThat(codec.toPosition(42L)).isEqualTo(42L);
        assertThatThrownBy(() -> codec.toPosition(-(1L << 53))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.toPosition(Long.MIN_VALUE)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.encode(-1L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> codec.encode(TimelineScanCursorCodec.MAX_POSITION)).isInstanceOf(IllegalStateException.class);
    }
}
