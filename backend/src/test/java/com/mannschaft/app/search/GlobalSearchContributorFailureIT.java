package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-C10: ある種別の取得が例外またはタイムアウト（3秒）しても全体は 200 で、その種別が
 * {@code timedOutTypes} に入り、他種別の結果と件数は欠けない（試練・red 先行）。
 *
 * <p><b>故障の差し込み方</b>: 各ドメインが登録する実物の {@link SearchContributor} Bean を
 * {@link FaultInjection} の {@link BeanPostProcessor} で包み、{@code TEAM} は常に例外、
 * {@code USER} は {@value #STALL_MILLIS} ms 停止させる。{@code ORGANIZATION} は素通しで、
 * 他種別が返ることの対照に使う。実装クラス名に依存しないため、後続 PR で種別が増えても壊れない。</p>
 *
 * <p>Bean 構成を変えるため本クラスだけ ApplicationContext が分かれる（他の検索 IT は共通 context）。
 * DB・認可・Security フィルタは実物のまま。</p>
 */
@Import(GlobalSearchContributorFailureIT.FaultInjection.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("グローバル検索 種別単位の故障隔離（AC-C10 試練）")
class GlobalSearchContributorFailureIT extends AbstractGlobalSearchIT {

    /** USER 種別の停止時間。タイムアウト 3 秒を十分に超える。 */
    static final long STALL_MILLIS = 8_000L;

    @TestConfiguration
    static class FaultInjection {

        @Bean
        static BeanPostProcessor searchContributorFaultInjector() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof SearchContributor contributor) {
                        if (contributor.type() == SearchType.TEAM) {
                            return new Throwing(contributor.type());
                        }
                        if (contributor.type() == SearchType.USER) {
                            return new Stalling(contributor);
                        }
                    }
                    return bean;
                }
            };
        }
    }

    /** 件数・取得とも例外を投げる Contributor。 */
    record Throwing(SearchType type) implements SearchContributor {
        @Override
        public long count(SearchCriteria criteria, SearchViewer viewer) {
            throw new IllegalStateException("AC-C10 試練: 差し込んだ故障（count）");
        }

        @Override
        public List<SearchHit> find(SearchCriteria criteria, SearchViewer viewer, int offset, int limit) {
            throw new IllegalStateException("AC-C10 試練: 差し込んだ故障（find）");
        }
    }

    /** 実物に委譲する前に長時間停止する Contributor。 */
    record Stalling(SearchContributor delegate) implements SearchContributor {
        @Override
        public SearchType type() {
            return delegate.type();
        }

        @Override
        public long count(SearchCriteria criteria, SearchViewer viewer) {
            stall();
            return delegate.count(criteria, viewer);
        }

        @Override
        public List<SearchHit> find(SearchCriteria criteria, SearchViewer viewer, int offset, int limit) {
            stall();
            return delegate.find(criteria, viewer, offset, limit);
        }

        private static void stall() {
            try {
                Thread.sleep(STALL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    @DisplayName("AC-C10: TEAM の例外・USER のタイムアウトでも 200 で timedOutTypes に入り、ORGANIZATION は結果も件数も返る")
    void AC_C10_種別単位でスキップし他種別は返る() throws Exception {
        Long viewer = viewer("fault");
        List<Long> orgIds = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            orgIds.add(publicOrg(kw + " 組織" + i));
        }
        publicTeam(kw + " チーム");
        Long mate = user(kw + " 仲間");
        Long shared = privateTeam("共有-" + kw.substring(2));
        teamMember(viewer, shared);
        teamMember(mate, shared);

        long started = System.nanoTime();
        JsonNode body = search(viewer, kw);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        List<String> timedOut = new ArrayList<>();
        body.path("data").path("timedOutTypes").forEach(n -> timedOut.add(n.asText()));
        assertThat(timedOut).as("故障した種別だけが timedOutTypes に入る（応答: %s）", body)
                .containsExactlyInAnyOrder("TEAM", "USER");
        assertThat(typeCount(body, "ORGANIZATION")).as("例外で他種別の件数が欠けない").isEqualTo(2);
        assertThat(resultIds(body, "ORGANIZATION")).containsExactlyInAnyOrderElementsOf(orgIds);
        assertThat(resultIds(body, "TEAM")).isEmpty();
        assertThat(resultIds(body, "USER")).isEmpty();
        assertThat(elapsedMillis).as("停止した種別を 3 秒で打ち切り、%d ms 待たない", STALL_MILLIS)
                .isLessThan(STALL_MILLIS - 2_000);
    }

    @Test
    @DisplayName("AC-C10: 詳細ビューで故障種別を指定しても 200（結果0・timedOutTypes にその種別）")
    void AC_C10_詳細ビューの故障() throws Exception {
        Long viewer = viewer("fault-detail");
        publicTeam(kw + " チーム");

        JsonNode body = search(viewer, kw, "TEAM");

        List<String> timedOut = new ArrayList<>();
        body.path("data").path("timedOutTypes").forEach(n -> timedOut.add(n.asText()));
        assertThat(timedOut).containsExactly("TEAM");
        assertThat(body.path("data").path("results")).isEmpty();
        assertThat(body.path("meta").path("viewMode").asText()).isEqualTo("DETAIL");
    }
}
