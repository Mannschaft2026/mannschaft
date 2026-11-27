package com.mannschaft.app.supportaccess;

import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.advertising.dto.SpotlightContentResponse;
import com.mannschaft.app.advertising.repository.AdEntityRepository;
import com.mannschaft.app.advertising.service.AdClickService;
import com.mannschaft.app.advertising.service.AdImpressionService;
import com.mannschaft.app.advertising.service.SpotlightServingService;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.payment.service.TeamPlanService;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役Z-B 検分差し戻し対応A（重要4）: {@code GET /api/v1/spotlight/content} は代理中に本人の serve-token・serve-cap・
 * ラウンドロビンの位置（Valkey）を消費しない（洗い出し表の SKIP）。閲覧結果（広告候補）は通常どおり返す。
 */
@DisplayName("戦役Z-B Spotlight 配信の代理中 SKIP")
class SupportAccessSpotlightSkipTest {

    private static final Long USER_ID = 42L;

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SpotlightServingService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        FeatureFlagService featureFlagService = mock(FeatureFlagService.class);
        TeamPlanService teamPlanService = mock(TeamPlanService.class);
        MembershipService membershipService = mock(MembershipService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(featureFlagService.isEnabled("FEATURE_V9_ENABLED")).willReturn(true);
        given(membershipService.getActiveTeamIdsByUser(USER_ID)).willReturn(List.of());
        given(teamPlanService.hasAnyActivePaidPlan(anyList())).willReturn(false);
        given(valueOps.increment(anyString())).willReturn(1L);

        service = new SpotlightServingService(featureFlagService, teamPlanService, mock(RoleService.class),
                membershipService, mock(AdEntityRepository.class), mock(AdImpressionService.class),
                mock(AdClickService.class), redisTemplate);
        ReflectionTestUtils.setField(service, "em", entityManagerWithOneOperationalAd());
    }

    @AfterEach
    void tearDown() {
        SupportAccessContextHolder.clear();
    }

    /** 運用型（HOUSE）の広告 1 件だけを返す EntityManager（予約バナー・アフィリエイト・受信設定は空）。 */
    private static EntityManager entityManagerWithOneOperationalAd() {
        EntityManager em = mock(EntityManager.class);
        given(em.createNativeQuery(anyString())).willAnswer(inv -> {
            String sql = inv.getArgument(0);
            Query query = mock(Query.class, Answers.RETURNS_SELF);
            List<Object[]> rows = sql.contains("FROM ads a")
                    ? List.<Object[]>of(new Object[]{10L, 20L, 30L, "広告主", "タイトル", "https://img.example/a.png",
                            "https://dest.example/", 300, 250, "代替", BigDecimal.valueOf(100000), BigDecimal.ONE, "CPM"})
                    : List.of();
            given(query.getResultList()).willReturn((List) rows);
            return query;
        });
        return em;
    }

    @Test
    @DisplayName("代理でなければ serve-token の SET・serve-cap の SETNX・ラウンドロビンの INCR を行う（前提の確認）")
    void 通常は本人の露出機会を消費する() {
        SpotlightContentResponse response = service.serveContent(
                USER_ID, "SIDEBAR_RIGHT", 1, null, null, null, null, "ja");

        assertThat(response.items()).hasSize(1);
        verify(valueOps).set(startsWith("mannschaft:ad:serve-token:"), anyString(), any(Duration.class));
        verify(valueOps).setIfAbsent(startsWith("mannschaft:ad:serve-cap:"), anyString(), any(Duration.class));
        verify(valueOps).increment(startsWith("mannschaft:ad:rr:"));
    }

    @Test
    @DisplayName("代理中は候補を返すが、Valkey へは一切書かない（serve-token・serve-cap・ラウンドロビン）")
    void 代理中はValkeyへ書かない() {
        SupportAccessContextHolder.set(new SupportAccessContext(
                UUID.randomUUID(), 9001L, USER_ID, Instant.now().plusSeconds(3600)));

        SpotlightContentResponse response = service.serveContent(
                USER_ID, "SIDEBAR_RIGHT", 1, null, null, null, null, "ja");

        assertThat(response.items()).hasSize(1);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(valueOps, never()).increment(anyString());
        verify(valueOps, never()).increment(anyString(), anyLong());
    }
}
