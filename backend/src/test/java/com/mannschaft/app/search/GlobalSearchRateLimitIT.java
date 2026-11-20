package com.mannschaft.app.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * AC-C13: 検索 30回/分・サジェスト 60回/分のユーザー単位レート制限（試練・red 先行）。
 *
 * <p>外部 Valkey は基底で Mock 化されているため、{@code ValkeyRateLimiter} が使う
 * {@code StringRedisTemplate#execute(script, keys, args)}（INCR + EXPIRE の Lua）を
 * キーごとのメモリ上カウンタで代替する。レート制限フィルタ・JWT・Security フィルタは実物を通す。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("グローバル検索 レート制限（AC-C13 試練）")
class GlobalSearchRateLimitIT extends AbstractGlobalSearchIT {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void emulateValkeyCounter() {
        counters.clear();
        doAnswer(invocation -> {
            List<String> keys = invocation.getArgument(1);
            return counters.computeIfAbsent(keys.get(0), k -> new AtomicLong()).incrementAndGet();
        }).when(redisTemplate).execute(
                ArgumentMatchers.<RedisScript<Long>>any(), ArgumentMatchers.<List<String>>any(),
                ArgumentMatchers.<Object>any());
    }

    private MockHttpServletResponse call(String path, Long actor) throws Exception {
        return http(get(path).param("q", "ab"), actor).andReturn().getResponse();
    }

    @Test
    @DisplayName("AC-C13: 同一ユーザーの 31 回目の GET /search は 429（Retry-After 付き）。別ユーザーは影響を受けない")
    void AC_C13_検索は31回目で429() throws Exception {
        Long heavy = viewer("heavy");
        Long other = viewer("other");

        for (int i = 1; i <= 30; i++) {
            assertThat(call(SEARCH, heavy).getStatus()).as("%d 回目は通る", i).isEqualTo(200);
        }
        MockHttpServletResponse limited = call(SEARCH, heavy);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).as("Retry-After を付ける").isNotBlank();

        assertThat(call(SEARCH, other).getStatus()).as("別ユーザーは影響を受けない").isEqualTo(200);
    }

    @Test
    @DisplayName("AC-C13: サジェストは 61 回目で 429（Retry-After 付き）。別ユーザーは影響を受けない")
    void AC_C13_サジェストは61回目で429() throws Exception {
        Long heavy = viewer("heavy-suggest");
        Long other = viewer("other-suggest");

        for (int i = 1; i <= 60; i++) {
            assertThat(call(SEARCH + "/suggestions", heavy).getStatus()).as("%d 回目は通る", i).isEqualTo(200);
        }
        MockHttpServletResponse limited = call(SEARCH + "/suggestions", heavy);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotBlank();

        assertThat(call(SEARCH + "/suggestions", other).getStatus()).isEqualTo(200);
    }
}
