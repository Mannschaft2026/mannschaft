package com.mannschaft.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link ScopeSlugIdConverter} のクエリ {@code organizationId} 越境解決の受け入れテスト
 * （試練 / red 先行。CMP-261125-2124・CMP-261022-0234 ε1 G3/G4）。
 *
 * <h3>欠陥</h3>
 * <p>本コンバータは URI の直前セグメントが {@code organizations} の場合だけ組織として解決し、
 * それ以外は TEAM として解決する。{@code /api/v1/advertiser/account?organizationId=<slug>} のように
 * URI に {@code /organizations/} を含まないクエリパラメータへ slug を渡すと、同名チームの ID が
 * {@code organizationId} に入り、別組織を誤照会しうる。</p>
 *
 * <h3>受け入れ条件</h3>
 * <ul>
 *   <li>G4: クエリ {@code organizationId} の非数値は組織として解決するか 400。TEAM として解決しない。</li>
 *   <li>G3: TEAM 経路（パス変数 {@code /teams/{slug}}）と数値 ID の高速パスは回帰しない。</li>
 * </ul>
 *
 * <p>修正方針（組織解決 / 400）は実装役の選択に委ねるため、G4 は「200 なら組織 ID、そうでなければ 400」
 * の両方を許容し、TEAM 解決（チーム ID の混入・TeamService の呼び出し）だけを禁止する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScopeSlugIdConverter クエリ organizationId の越境解決（CMP-261125-2124 / ε1 G3・G4）")
class ScopeSlugIdConverterQueryOrganizationIdTest {

    private static final long ORG_ID = 4242L;
    private static final long SAME_NAME_TEAM_ID = 77L;

    @Mock
    private TeamService teamService;

    @Mock
    private OrganizationService organizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ScopeSlugIdConverter converter = new ScopeSlugIdConverter(teamService, organizationService);
        FormattingConversionService conversionService = new DefaultFormattingConversionService();
        conversionService.addConverter(converter);
        mockMvc = MockMvcBuilders.standaloneSetup(new QueryProbeController())
                .setConversionService(conversionService)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    @Test
    @DisplayName("G4: /organizations/ を含まない URI のクエリ organizationId に同名 slug → TEAM として解決せず、組織 ID（200）か 400")
    void G4_同名チームがあってもクエリのorganizationIdはチームIDにならない() throws Exception {
        lenient().when(organizationService.resolveOrgId("dup-slug")).thenReturn(ORG_ID);
        lenient().when(teamService.resolveTeamId("dup-slug")).thenReturn(SAME_NAME_TEAM_ID);

        MvcResult result = mockMvc.perform(get("/api/v1/advertiser/account").param("organizationId", "dup-slug"))
                .andReturn();

        assertOrganizationResolvedOrBadRequest(result, String.valueOf(ORG_ID));
        verify(teamService, never()).resolveTeamId(anyString());
    }

    @Test
    @DisplayName("G4: 同名チームだけが存在し組織が無い slug → チーム ID で 200 にならない（400 か 404）")
    void G4_組織不在で同名チームだけある場合もチームIDを返さない() throws Exception {
        lenient().when(organizationService.resolveOrgId("team-only-slug"))
                .thenThrow(new BusinessException(OrgErrorCode.ORG_001));
        lenient().when(teamService.resolveTeamId("team-only-slug")).thenReturn(SAME_NAME_TEAM_ID);

        MvcResult result = mockMvc.perform(get("/api/v1/advertiser/account").param("organizationId", "team-only-slug"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("組織として解決できない slug は 400 か 404（チーム ID での 200 は越境）")
                .isIn(400, 404);
        assertThat(result.getResponse().getContentAsString()).isNotEqualTo(String.valueOf(SAME_NAME_TEAM_ID));
        verify(teamService, never()).resolveTeamId(anyString());
    }

    @Test
    @DisplayName("G4: クエリ organizationId の数値はそのまま Long（Service 呼び出しなし）")
    void G4_クエリorganizationIdの数値は高速パスのまま() throws Exception {
        mockMvc.perform(get("/api/v1/advertiser/account").param("organizationId", "1234"))
                .andExpect(status().isOk())
                .andExpect(content().string("1234"));

        verify(teamService, never()).resolveTeamId(anyString());
        verify(organizationService, never()).resolveOrgId(anyString());
    }

    @Test
    @DisplayName("G3: TEAM 経路（パス変数 /teams/{slug}）は従来どおり TEAM として解決される")
    void G3_チームのパス変数slugはチームとして解決される() throws Exception {
        lenient().when(teamService.resolveTeamId("my-team")).thenReturn(SAME_NAME_TEAM_ID);

        mockMvc.perform(get("/api/v1/teams/my-team/advertiser/account"))
                .andExpect(status().isOk())
                .andExpect(content().string(String.valueOf(SAME_NAME_TEAM_ID)));

        verify(organizationService, never()).resolveOrgId(anyString());
    }

    @Test
    @DisplayName("G3: 組織のパス変数 /organizations/{slug} は従来どおり組織として解決される")
    void G3_組織のパス変数slugは組織として解決される() throws Exception {
        lenient().when(organizationService.resolveOrgId("my-org")).thenReturn(ORG_ID);

        mockMvc.perform(get("/api/v1/organizations/my-org/advertiser/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string(String.valueOf(ORG_ID)));

        verify(teamService, never()).resolveTeamId(anyString());
    }

    private static void assertOrganizationResolvedOrBadRequest(MvcResult result, String expectedOrgId)
            throws Exception {
        int httpStatus = result.getResponse().getStatus();
        assertThat(httpStatus)
                .as("組織として解決（200）か 400 のどちらか")
                .isIn(200, 400);
        if (httpStatus == 200) {
            assertThat(result.getResponse().getContentAsString())
                    .as("200 の場合は組織 ID が入っていること（同名チームの ID は越境）")
                    .isEqualTo(expectedOrgId);
        }
    }

    /** 実コントローラ（AdvertiserDashboardController 等）と同形で Long を受けるテスト専用コントローラ。 */
    @RestController
    static class QueryProbeController {

        @GetMapping("/api/v1/advertiser/account")
        String account(@RequestParam Long organizationId) {
            return String.valueOf(organizationId);
        }

        @GetMapping("/api/v1/teams/{teamId}/advertiser/account")
        String teamAccount(@PathVariable Long teamId) {
            return String.valueOf(teamId);
        }

        @GetMapping("/api/v1/organizations/{organizationId}/advertiser/probe")
        String orgProbe(@PathVariable Long organizationId) {
            return String.valueOf(organizationId);
        }
    }
}
