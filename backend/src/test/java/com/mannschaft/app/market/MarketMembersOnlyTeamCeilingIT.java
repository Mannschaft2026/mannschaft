package com.mannschaft.app.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentCategoryEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingRegionEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRegionRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CMP-261124-0159 PR-P: 公開マーケットの親チーム上限（F00 §20.5.3 マーケット行・AC-0159-27）。
 *
 * <p>公開募集を持つチーム（地域 A・B に属する募集 1 件、地域 A のみの募集 1 件）を非公開にすると、匿名・認証済みの
 * 一覧と total からその 2 件が消え（total は 2 減る）、詳細は不在の募集と同一応答、{@code /summary} の地域別件数は
 * 地域 A が 2・地域 B が 1 減る。{@code /regions} は変わらない。公開に戻すと再び出る。他チームの募集は不変。
 * 件数は共有 DB の他データの影響を受けないよう、操作の前後の差分で検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 公開マーケットの親チーム上限（一覧・total・詳細・summary）")
class MarketMembersOnlyTeamCeilingIT extends AbstractMySqlIntegrationTest {

    private static final String PREF_A = "13";
    private static final String CITY_A = "13101";
    private static final String PREF_B = "14";
    private static final String CITY_B = "14100";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RecruitmentCategoryRepository categoryRepository;
    @Autowired private RecruitmentListingRepository listingRepository;
    @Autowired private RecruitmentListingRegionRepository regionRepository;
    @PersistenceContext private EntityManager em;

    private ScopeContentFixture fixture;
    private String marker;
    private Long listingAB;
    private Long listingA;
    private Long otherTeamListing;

    @BeforeEach
    void setUp() {
        fixture = ScopeContentFixture.create(em);
        ScopeContentFixture.setTeamVisibility(em, fixture.teamId, "PUBLIC");
        ScopeContentFixture.setTeamVisibility(em, fixture.otherTeamId, "PUBLIC");
        marker = "C59MK" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Long categoryId = categoryRepository.save(RecruitmentCategoryEntity.builder()
                .code("C59_" + marker).nameI18nKey("recruitment.category.c59")
                .defaultParticipationType(RecruitmentParticipationType.INDIVIDUAL)
                .displayOrder(0).isActive(true).build()).getId();
        listingAB = insertListing(fixture.teamId, categoryId, marker + " 地域AB");
        regionRepository.save(RecruitmentListingRegionEntity.of(listingAB, PREF_A, CITY_A));
        regionRepository.save(RecruitmentListingRegionEntity.of(listingAB, PREF_B, CITY_B));
        listingA = insertListing(fixture.teamId, categoryId, marker + " 地域A");
        regionRepository.save(RecruitmentListingRegionEntity.of(listingA, PREF_A, CITY_A));
        otherTeamListing = insertListing(fixture.otherTeamId, categoryId, marker + " 他チーム");
        regionRepository.save(RecruitmentListingRegionEntity.of(otherTeamListing, PREF_A, CITY_A));
        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("非公開化で、匿名・部外者・外されたサポーターの一覧と total から 2 件が消え、他チームの募集は残り、公開に戻すと再び出る")
    void 一覧とtotal() throws Exception {
        for (Long viewer : viewers()) {
            ScopeContentFixture.setTeamVisibility(em, fixture.teamId, "PUBLIC");
            em.flush();
            long totalBefore = total(viewer, null);
            assertThat(idsWithMarker(viewer)).as("viewer=%s 公開中", viewer)
                    .contains(listingAB, listingA, otherTeamListing);

            ScopeContentFixture.setTeamVisibility(em, fixture.teamId, "MEMBERS_AND_ABOVE");
            em.flush();
            assertThat(total(viewer, null)).as("viewer=%s total は 2 減る", viewer).isEqualTo(totalBefore - 2);
            assertThat(idsWithMarker(viewer)).as("viewer=%s 非公開中", viewer)
                    .containsExactly(otherTeamListing);

            ScopeContentFixture.setTeamVisibility(em, fixture.teamId, "PUBLIC");
            em.flush();
            assertThat(total(viewer, null)).as("viewer=%s 公開に戻すと total は元どおり", viewer).isEqualTo(totalBefore);
        }
    }

    @Test
    @DisplayName("非公開チームの募集の詳細は、不在の募集と同一応答（status・code・message）で本文を返さない")
    void 詳細は不在と同一応答() throws Exception {
        ScopeContentFixture.setTeamVisibility(em, fixture.teamId, "MEMBERS_AND_ABOVE");
        em.flush();
        for (Long viewer : viewers()) {
            JsonNode absent = call("/api/v1/public/market/listings/" + Long.MAX_VALUE, viewer);
            MvcResult raw = raw("/api/v1/public/market/listings/" + listingAB, viewer);
            JsonNode actual = objectMapper.readTree(raw.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(raw.getResponse().getStatus()).as("viewer=%s", viewer).isEqualTo(404);
            assertThat(actual.path("error")).as("viewer=%s", viewer).isEqualTo(absent.path("error"));
            assertThat(raw.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(marker);
            assertThat(raw("/api/v1/public/market/listings/" + otherTeamListing, viewer).getResponse().getStatus())
                    .as("viewer=%s 他チームの募集は不変", viewer).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("/summary の地域別件数は地域 A が 2・地域 B が 1 減り（byPrefecture・byCity を個別に検証）、/regions は変わらない")
    void summaryとregions() throws Exception {
        Map<String, Long> prefBefore = counts("byPrefecture");
        Map<String, Long> cityBefore = counts("byCity");
        String regionsBefore = raw("/api/v1/public/market/regions", null).getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        ScopeContentFixture.setTeamVisibility(em, fixture.teamId, "MEMBERS_AND_ABOVE");
        em.flush();

        Map<String, Long> prefAfter = counts("byPrefecture");
        Map<String, Long> cityAfter = counts("byCity");
        assertThat(prefAfter.getOrDefault(PREF_A, 0L)).isEqualTo(prefBefore.get(PREF_A) - 2);
        assertThat(prefAfter.getOrDefault(PREF_B, 0L)).isEqualTo(prefBefore.get(PREF_B) - 1);
        assertThat(cityAfter.getOrDefault(CITY_A, 0L)).isEqualTo(cityBefore.get(CITY_A) - 2);
        assertThat(cityAfter.getOrDefault(CITY_B, 0L)).isEqualTo(cityBefore.get(CITY_B) - 1);
        assertThat(raw("/api/v1/public/market/regions", null).getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(regionsBefore);

        ScopeContentFixture.setTeamVisibility(em, fixture.teamId, "PUBLIC");
        em.flush();
        assertThat(counts("byPrefecture").get(PREF_A)).isEqualTo(prefBefore.get(PREF_A));
        assertThat(counts("byCity").get(CITY_B)).isEqualTo(cityBefore.get(CITY_B));
    }

    // ─── ヘルパー ─────────────────────────────────────────

    /** 未ログイン（null）・ログイン済み部外者・外されたサポーター。 */
    private List<Long> viewers() {
        return Arrays.asList(null, fixture.user(Actor.OUTSIDER), fixture.user(Actor.SUPPORTER));
    }

    private MvcResult raw(String path, Long viewer) throws Exception {
        if (viewer == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(viewer.toString(), null, List.of()));
        }
        return mockMvc.perform(get(path)).andReturn();
    }

    private JsonNode call(String path, Long viewer) throws Exception {
        return objectMapper.readTree(raw(path, viewer).getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private long total(Long viewer, String keyword) throws Exception {
        String path = "/api/v1/public/market/listings?size=1" + (keyword == null ? "" : "&keyword=" + keyword);
        return call(path, viewer).path("meta").path("total").asLong();
    }

    private List<Long> idsWithMarker(Long viewer) throws Exception {
        JsonNode data = call("/api/v1/public/market/listings?size=50&keyword=" + marker, viewer).path("data");
        List<Long> ids = new java.util.ArrayList<>();
        data.forEach(n -> ids.add(n.path("id").asLong()));
        ids.sort(null);
        return ids;
    }

    private Map<String, Long> counts(String field) throws Exception {
        Map<String, Long> out = new HashMap<>();
        call("/api/v1/public/market/summary", null).path("data").path(field)
                .forEach(n -> out.put(n.path("code").asText(), n.path("count").asLong()));
        return out;
    }

    private Long insertListing(Long teamId, Long categoryId, String title) {
        LocalDateTime start = LocalDateTime.now().plusDays(30).withNano(0);
        return listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(teamId)
                .categoryId(categoryId)
                .title(title)
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .visibility(RecruitmentVisibility.PUBLIC)
                .location("上限試練 会場")
                .status(RecruitmentListingStatus.OPEN)
                .createdBy(fixture.user(Actor.ADMIN))
                .build()).getId();
    }
}
