package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CMP-261009-0010 試練: 広告配信 worker の候補取得が<b>実 Spring トランザクション内</b>で動くことの受け入れテスト。
 *
 * <h2>何を守るか</h2>
 * <p>{@link AdCampaignDeliveryWorker#runDelivery()} は同一 bean の {@code @Transactional} メソッド
 * {@code loadActiveCampaigns()} を {@code this} 経由で呼んでいた。Spring の {@code @Transactional} は
 * プロキシ経由でしか効かないため、候補取得の {@code SELECT ... FOR UPDATE}
 * （{@link AdMessagingCampaignRepository#findActiveDeliveringForUpdate}）がトランザクション無しで走り、
 * {@code TransactionRequiredException} で毎分の配信が丸ごと止まる。既存の単体テスト
 * {@code AdCampaignDeliveryWorkerTest} は repository をモックしているため原理的に検出できない。</p>
 *
 * <h2>試験の組み立て（共有コンテキストを増やさないため）</h2>
 * <p>{@code @MockitoBean} を足すと構成キーが増え、番人 {@code ContextOverrideCensusGuardTest} の
 * distinct key ラチェットに触れる。そこで基底 {@link AbstractMySqlIntegrationTest} の共有コンテキストをそのまま使い、
 * worker の<b>実体（プロキシの中身）</b>が持つ {@code audienceResolver} / {@code dispatcher} の 2 フィールドだけを
 * テスト中にモックへ差し替え、{@link #restoreCollaborators()} で必ず元に戻す。
 * repository・claim サービス・トランザクション・Aspect（{@code BackgroundFeaturePolicy} / {@code BatchEndpoint}）は
 * すべて本物で、{@code runDelivery()} は<b>プロキシ経由</b>で呼ぶ（本番の毎分起動と同じ入口）。
 * ShedLock は {@code test} プロファイルで無効（{@code ShedLockConfig} の {@code @Profile}）のため、
 * {@code @SchedulerLock} は宣言の不変を検査する（D4）。</p>
 *
 * <p><b>機能フラグ</b>: {@code runDelivery} は {@code @BackgroundFeaturePolicy(SKIP_WHEN_DISABLED,
 * gateKeys="FEATURE_PROMOTION_ENABLED")} でゲートされる。テストは Flyway 無効・{@code ddl-auto: create} で
 * {@code feature_flags} が空のためフェイルクローズで本体がスキップされる。したがって {@link #openFeatureGate()} で
 * 毎回フラグを開け、ゲートが閉じたときに本体がスキップされることを対照ケース
 * {@code D1_ゲートが閉じると本体はスキップされる} で固定する（検出力の前提。{@link FeatureFlagTestSupport} 参照）。</p>
 *
 * <p>共有 DB には他試験の DELIVERING キャンペーンが残り得るため、検証は本試験が作ったキャンペーン ID に絞る。
 * 本試験が作ったキャンペーンは {@link #restoreCollaborators()} で論理削除し、以後の配信候補に残さない。</p>
 *
 * <p><b>実装役への注意</b>: 根治で worker の {@code audienceResolver} / {@code dispatcher} フィールド名を変える場合は
 * 本試験の {@link #FIELD_AUDIENCE_RESOLVER} / {@link #FIELD_DISPATCHER} も合わせること。</p>
 */
@DisplayName("CMP-261009-0010 広告配信worker 候補取得の実TX 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AdCampaignDeliveryWorkerIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final String FIELD_AUDIENCE_RESOLVER = "audienceResolver";
    private static final String FIELD_DISPATCHER = "dispatcher";
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Autowired
    private AdCampaignDeliveryWorker worker;

    @Autowired
    private AdMessagingCampaignRepository campaignRepository;

    @Autowired
    private AdCampaignDeliveryClaimService claimService;

    @Autowired
    private FeatureFlagRepository featureFlagRepository;

    @Autowired
    private CacheManager cacheManager;

    private AdCampaignDeliveryWorker target;
    private Object originalAudienceResolver;
    private Object originalDispatcher;
    private AdAudienceResolver audienceResolver;
    private AdCampaignDeliveryDispatcher dispatcher;
    private final List<UUID> createdCampaignIds = new ArrayList<>();

    @BeforeEach
    void openFeatureGate() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_PROMOTION_ENABLED");
    }

    @BeforeEach
    void swapCollaborators() {
        target = AopTestUtils.getUltimateTargetObject(worker);
        originalAudienceResolver = ReflectionTestUtils.getField(target, FIELD_AUDIENCE_RESOLVER);
        originalDispatcher = ReflectionTestUtils.getField(target, FIELD_DISPATCHER);
        assertThat(originalAudienceResolver).as("worker に %s フィールドがある", FIELD_AUDIENCE_RESOLVER).isNotNull();
        assertThat(originalDispatcher).as("worker に %s フィールドがある", FIELD_DISPATCHER).isNotNull();

        audienceResolver = mock(AdAudienceResolver.class);
        dispatcher = mock(AdCampaignDeliveryDispatcher.class);
        // 本試験が作っていないキャンペーン（他試験の残存行）には候補ユーザー無しを返す（Mockito 既定は空 Stream）。
        given(dispatcher.deliverForUser(argThat(c -> c != null), anyLong())).willReturn(AdDeliveryOutcome.DELIVERED);
        ReflectionTestUtils.setField(target, FIELD_AUDIENCE_RESOLVER, audienceResolver);
        ReflectionTestUtils.setField(target, FIELD_DISPATCHER, dispatcher);
    }

    @AfterEach
    void restoreCollaborators() {
        ReflectionTestUtils.setField(target, FIELD_AUDIENCE_RESOLVER, originalAudienceResolver);
        ReflectionTestUtils.setField(target, FIELD_DISPATCHER, originalDispatcher);
        for (UUID id : createdCampaignIds) {
            campaignRepository.findById(id).ifPresent(c -> {
                c.softDelete();
                campaignRepository.saveAndFlush(c);
            });
        }
        createdCampaignIds.clear();
    }

    private AdMessagingCampaign persistCampaign(String name, AdCampaignStatus status,
                                                LocalDateTime startsAt, LocalDateTime endsAt) {
        AdMessagingCampaign campaign = AdMessagingCampaign.builder()
                .advertiserAccountId(7101L)
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(9201L)
                .name(name)
                .status(status)
                .moderationStatus(AdModerationStatus.APPROVED)
                .totalBudgetYen(50_000L)
                .consumedBudgetYen(0L)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .scheduledTimezone("Asia/Tokyo")
                .createdByUserId(6101L)
                .build();
        AdMessagingCampaign saved = campaignRepository.saveAndFlush(campaign);
        createdCampaignIds.add(saved.getId());
        return saved;
    }

    private AdMessagingCampaign persistActiveCampaign(String name) {
        LocalDateTime now = LocalDateTime.now();
        return persistCampaign(name, AdCampaignStatus.DELIVERING, now.minusDays(1), now.plusDays(7));
    }

    private void givenCandidates(UUID campaignId, Long... userIds) {
        // streamCandidateUserIds は try-with-resources で閉じられるため、呼び出しごとに新しい Stream を返す。
        given(audienceResolver.streamCandidateUserIds(campaignId)).willAnswer(inv -> Stream.of(userIds));
    }

    private static Method runDeliveryMethod() throws NoSuchMethodException {
        return AdCampaignDeliveryWorker.class.getDeclaredMethod("runDelivery");
    }

    // ================================================================== D1

    @Test
    @DisplayName("D1: 実Springコンテキストで runDelivery が TransactionRequiredException を出さず配信まで到達する")
    void D1_実TXで候補取得し配信まで到達する() {
        AdMessagingCampaign campaign = persistActiveCampaign("D1 実TX 候補取得");
        givenCandidates(campaign.getId(), 81001L);

        assertThatCode(() -> worker.runDelivery())
                .as("候補取得の SELECT ... FOR UPDATE が実トランザクション内で走ること"
                        + "（自己呼出しで @Transactional が効かないと TransactionRequiredException になる）")
                .doesNotThrowAnyException();

        verify(dispatcher, times(1)).deliverForUser(
                argThat(c -> c != null && campaign.getId().equals(c.getId())), eq(81001L));
    }

    @Test
    @DisplayName("D1: 機能フラグ FEATURE_PROMOTION_ENABLED が閉じていると本体はスキップされる（ゲートの開閉を本当に通っている対照）")
    void D1_ゲートが閉じると本体はスキップされる() {
        AdMessagingCampaign campaign = persistActiveCampaign("D1 ゲート閉鎖");
        givenCandidates(campaign.getId(), 81002L);
        FeatureFlagEntity flag = featureFlagRepository.findByFlagKey("FEATURE_PROMOTION_ENABLED").orElseThrow();
        flag.updateFlag(false, null);
        featureFlagRepository.save(flag);
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);

        assertThatCode(() -> worker.runDelivery()).doesNotThrowAnyException();

        verify(audienceResolver, never()).streamCandidateUserIds(campaign.getId());
        verify(dispatcher, never()).deliverForUser(
                argThat(c -> c != null && campaign.getId().equals(c.getId())), anyLong());
    }

    @Test
    @DisplayName("D1: runDelivery 自体には @Transactional を付けない（配信は user 単位 REQUIRES_NEW に委ねる方針の固定）")
    void D1_runDelivery自体は非トランザクション() throws Exception {
        assertThat(AnnotatedElementUtils.hasAnnotation(runDeliveryMethod(), Transactional.class))
                .as("runDelivery に @Transactional が付いていないこと（付けると FOR UPDATE のロックが配信ループ全体に及ぶ）")
                .isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(AdCampaignDeliveryWorker.class, Transactional.class))
                .as("AdCampaignDeliveryWorker にクラスレベル @Transactional が付いていないこと")
                .isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(runDeliveryMethod(), jakarta.transaction.Transactional.class))
                .as("runDelivery に jakarta の @Transactional も付いていないこと")
                .isFalse();
    }

    // ================================================================== D2

    @Test
    @DisplayName("D2: DELIVERING 以外・期間外・論理削除済みのキャンペーンは候補に取らない（回帰）")
    void D2_対象外キャンペーンは取らない() {
        LocalDateTime now = LocalDateTime.now();
        AdMessagingCampaign active = persistActiveCampaign("D2 配信中・期間内");
        AdMessagingCampaign paused = persistCampaign("D2 一時停止", AdCampaignStatus.PAUSED,
                now.minusDays(1), now.plusDays(7));
        AdMessagingCampaign scheduled = persistCampaign("D2 予約済み", AdCampaignStatus.SCHEDULED,
                now.minusDays(1), now.plusDays(7));
        AdMessagingCampaign expired = persistCampaign("D2 期間終了", AdCampaignStatus.DELIVERING,
                now.minusDays(10), now.minusDays(1));
        AdMessagingCampaign future = persistCampaign("D2 期間前", AdCampaignStatus.DELIVERING,
                now.plusDays(1), now.plusDays(10));
        AdMessagingCampaign deleted = persistActiveCampaign("D2 論理削除済み");
        deleted.softDelete();
        campaignRepository.saveAndFlush(deleted);

        worker.runDelivery();

        verify(audienceResolver, times(1)).streamCandidateUserIds(active.getId());
        for (AdMessagingCampaign excluded : List.of(paused, scheduled, expired, future, deleted)) {
            verify(audienceResolver, never()).streamCandidateUserIds(excluded.getId());
        }
    }

    // ================================================================== D3

    @Test
    @DisplayName("D3: 個別キャンペーンの失敗で全体が止まらず、他キャンペーンの配信は続く（回帰）")
    void D3_個別キャンペーン失敗で全体停止しない() {
        AdMessagingCampaign broken = persistActiveCampaign("D3 失敗するキャンペーン");
        AdMessagingCampaign healthy = persistActiveCampaign("D3 正常なキャンペーン");
        given(audienceResolver.streamCandidateUserIds(broken.getId()))
                .willThrow(new IllegalStateException("D3 意図的な候補解決失敗"));
        givenCandidates(healthy.getId(), 83001L);

        assertThatCode(() -> worker.runDelivery()).doesNotThrowAnyException();

        verify(audienceResolver, times(1)).streamCandidateUserIds(broken.getId());
        verify(dispatcher, times(1)).deliverForUser(
                argThat(c -> c != null && healthy.getId().equals(c.getId())), eq(83001L));
    }

    // ================================================================== D4

    @Test
    @DisplayName("D4: 今週 claim 済みのユーザーには配信せず、未 claim のユーザーにだけ配信する（Claim による二重配信防止の回帰）")
    void D4_claim済みユーザーには再配信しない() {
        AdMessagingCampaign campaign = persistActiveCampaign("D4 claim 済み除外");
        Long claimedUser = 84001L;
        Long unclaimedUser = 84002L;
        LocalDate weekStart = LocalDate.now(TOKYO).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(claimService.tryClaim(campaign.getId(), claimedUser, weekStart)).isTrue();
        givenCandidates(campaign.getId(), claimedUser, unclaimedUser);

        worker.runDelivery();

        verify(dispatcher, never()).deliverForUser(
                argThat(c -> c != null && campaign.getId().equals(c.getId())), eq(claimedUser));
        verify(dispatcher, times(1)).deliverForUser(
                argThat(c -> c != null && campaign.getId().equals(c.getId())), eq(unclaimedUser));
    }

    @Test
    @DisplayName("D4: @SchedulerLock の宣言（name=adCampaignDelivery / lockAtMostFor=5m / lockAtLeastFor=30s）が不変（多重起動防止の回帰）")
    void D4_SchedulerLock宣言が不変() throws Exception {
        SchedulerLock lock = runDeliveryMethod().getAnnotation(SchedulerLock.class);
        assertThat(lock).as("runDelivery に @SchedulerLock がある").isNotNull();
        assertThat(lock.name()).isEqualTo("adCampaignDelivery");
        assertThat(lock.lockAtMostFor()).isEqualTo("5m");
        assertThat(lock.lockAtLeastFor()).isEqualTo("30s");
    }
}
