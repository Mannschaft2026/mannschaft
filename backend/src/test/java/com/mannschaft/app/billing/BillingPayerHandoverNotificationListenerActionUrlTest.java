package com.mannschaft.app.billing;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CMP-260909-0304: 引継通知の遷移先 URL")
class BillingPayerHandoverNotificationListenerActionUrlTest {

    private final TeamService teamService = mock(TeamService.class);
    private final OrganizationService organizationService = mock(OrganizationService.class);
    private final BillingPayerHandoverNotificationListener listener =
            new BillingPayerHandoverNotificationListener(mock(NotificationDeliveryRunner.class),
                    mock(UserLocaleCache.class), mock(MessageSource.class), teamService, organizationService);

    private BillingPayerHandoverNotificationEvent event(EntitlementScopeKind kind, long scopeId, UUID id) {
        return new BillingPayerHandoverNotificationEvent(
                BillingPayerHandoverNotificationKind.HANDOVER_REQUESTED, id, kind, scopeId, List.of(1L), 2L);
    }

    @Test
    @DisplayName("TEAM は slug 付きの実在ルート /teams/{slug}/settings/billing を指す")
    void teamUsesSlugAndExistingRoute() {
        UUID id = UUID.randomUUID();
        when(teamService.getSlugById(10L)).thenReturn("my-team");
        assertThat(listener.buildActionUrl(event(EntitlementScopeKind.TEAM, 10L, id)))
                .isEqualTo("/teams/my-team/settings/billing?payerHandoverRequestId=" + id);
    }

    @Test
    @DisplayName("ORG は slug 付きの実在ルート /organizations/{slug}/settings/billing を指す")
    void orgUsesSlugAndExistingRoute() {
        UUID id = UUID.randomUUID();
        when(organizationService.getSlugById(20L)).thenReturn("my-org");
        assertThat(listener.buildActionUrl(event(EntitlementScopeKind.ORG, 20L, id)))
                .isEqualTo("/organizations/my-org/settings/billing?payerHandoverRequestId=" + id);
    }

    @Test
    @DisplayName("slug を解決できなければ存在しない URL を作らず null")
    void unresolvedSlugYieldsNull() {
        assertThat(listener.buildActionUrl(event(EntitlementScopeKind.TEAM, 99L, UUID.randomUUID()))).isNull();
    }
}
