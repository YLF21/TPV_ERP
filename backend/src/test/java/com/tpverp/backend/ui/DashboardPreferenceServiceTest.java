package com.tpverp.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class DashboardPreferenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Test
    void returnsOnlyDefaultsAllowedByTheUsersDataPermissions() {
        var fixture = fixture();
        when(fixture.preferences().findByUser(fixture.user())).thenReturn(Optional.empty());

        var result = fixture.service().get(authentication("GESTION_VENTAS"));

        assertThat(result.availableWidgets()).containsExactly(
                "sales.today", "sales.top-products");
        assertThat(result.widgets()).containsExactly(
                new DashboardWidgetLayout("sales.today", 4, 1),
                new DashboardWidgetLayout("sales.top-products", 8, 2));
    }

    @Test
    void rejectsUnknownOrUnauthorizedWidgetsBeforeWriting() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.service().save(
                new DashboardPreferenceService.SavePreferenceRequest(List.of(
                        new DashboardWidgetLayout("promotions.active", 4, 2))),
                authentication("GESTION_VENTAS")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("promotions.active");
        assertThatThrownBy(() -> fixture.service().save(
                new DashboardPreferenceService.SavePreferenceRequest(List.of(
                        new DashboardWidgetLayout("unknown.widget", 4, 1))),
                authentication("GESTION_VENTAS")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown.widget");
    }

    @Test
    void preservesStoredWidgetsHiddenByARevokedPermission() {
        var fixture = fixture();
        var existing = new DashboardPreference(
                fixture.user(),
                List.of(
                        new DashboardWidgetLayout("sales.today", 4, 1),
                        new DashboardWidgetLayout("promotions.active", 4, 2),
                        new DashboardWidgetLayout("control.alerts", 4, 2)),
                NOW);
        when(fixture.preferences().findByUser(fixture.user())).thenReturn(Optional.of(existing));
        when(fixture.preferences().save(any())).thenAnswer(call -> call.getArgument(0));

        var result = fixture.service().save(
                new DashboardPreferenceService.SavePreferenceRequest(List.of(
                        new DashboardWidgetLayout("sales.today", 8, 2))),
                authentication("GESTION_VENTAS"));

        verify(fixture.preferences()).save(existing);
        assertThat(existing.getWidgets()).containsExactly(
                new DashboardWidgetLayout("sales.today", 8, 2),
                new DashboardWidgetLayout("promotions.active", 4, 2),
                new DashboardWidgetLayout("control.alerts", 4, 2));
        assertThat(result.widgets()).containsExactly(
                new DashboardWidgetLayout("sales.today", 8, 2));
    }

    @Test
    void adminReceivesTheCompleteCatalog() {
        var fixture = fixture();
        when(fixture.preferences().findByUser(fixture.user())).thenReturn(Optional.empty());

        var result = fixture.service().get(authentication("ROLE_ADMIN"));

        assertThat(result.availableWidgets()).containsExactly(
                "sales.today", "sales.top-products", "promotions.active", "control.alerts");
    }

    @Test
    void controlAlertsWidgetAcceptsEitherReadOrManagePermission() {
        var readFixture = fixture();
        when(readFixture.preferences().findByUser(readFixture.user())).thenReturn(Optional.empty());

        var readResult = readFixture.service().get(authentication("CONTROL_ALERTS_READ"));

        assertThat(readResult.availableWidgets()).containsExactly("control.alerts");
        assertThat(readResult.widgets()).containsExactly(
                new DashboardWidgetLayout("control.alerts", 4, 2));

        var manageFixture = fixture();
        when(manageFixture.preferences().findByUser(manageFixture.user())).thenReturn(Optional.empty());

        var manageResult = manageFixture.service().get(authentication("CONTROL_ALERTS_MANAGE"));

        assertThat(manageResult.availableWidgets()).containsExactly("control.alerts");
        assertThat(manageResult.widgets()).containsExactly(
                new DashboardWidgetLayout("control.alerts", 4, 2));
    }

    private static Fixture fixture() {
        var preferences = org.mockito.Mockito.mock(DashboardPreferenceRepository.class);
        var organization = org.mockito.Mockito.mock(CurrentOrganization.class);
        var user = org.mockito.Mockito.mock(UserAccount.class);
        when(organization.currentUser(any())).thenReturn(user);
        var service = new DashboardPreferenceService(
                preferences,
                organization,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, preferences, organization, user);
    }

    private static TestingAuthenticationToken authentication(String... authorities) {
        return new TestingAuthenticationToken("manager", "token", authorities);
    }

    private record Fixture(
            DashboardPreferenceService service,
            DashboardPreferenceRepository preferences,
            CurrentOrganization organization,
            UserAccount user) {
    }
}
