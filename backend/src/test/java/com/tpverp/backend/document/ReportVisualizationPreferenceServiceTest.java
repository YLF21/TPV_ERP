package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class ReportVisualizationPreferenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-06T12:00:00Z");

    @Test
    void listsOnlyCurrentUserPreferencesForRequestedApp() {
        var fixture = fixture();
        var preference = new ReportVisualizationPreference(
                fixture.user(), "venta", "salesReport.tickets", List.of("date", "ticket"));
        when(fixture.organization().currentUser(any())).thenReturn(fixture.user());
        when(fixture.repository().findByUserAndApp(fixture.user(), "venta"))
                .thenReturn(List.of(preference));

        var response = fixture.service().list("venta", new TestingAuthenticationToken("admin", "token"));

        assertThat(response.preferences())
                .containsExactly(new ReportVisualizationPreferenceService.PreferenceView(
                        "salesReport.tickets", List.of("date", "ticket")));
        verify(fixture.repository()).findByUserAndApp(fixture.user(), "venta");
    }

    @Test
    void savesNewPreferenceForCurrentUser() {
        var fixture = fixture();
        when(fixture.organization().currentUser(any())).thenReturn(fixture.user());
        when(fixture.repository().findByUserAndAppAndReportKey(
                fixture.user(), "venta", "salesReport.tickets"))
                .thenReturn(Optional.empty());
        when(fixture.repository().save(any())).thenAnswer(call -> call.getArgument(0));

        var result = fixture.service().save(
                "salesReport.tickets",
                new ReportVisualizationPreferenceService.SavePreferenceRequest(
                        "venta", List.of("date", "ticket", "total")),
                new TestingAuthenticationToken("admin", "token"));

        assertThat(result.reportKey()).isEqualTo("salesReport.tickets");
        assertThat(result.visibleAttributes()).containsExactly("date", "ticket", "total");
        verify(fixture.repository()).save(any(ReportVisualizationPreference.class));
    }

    @Test
    void updatesExistingPreferenceForSameUserAppAndReport() {
        var fixture = fixture();
        var existing = new ReportVisualizationPreference(
                fixture.user(), "venta", "salesReport.tickets", List.of("date", "ticket"));
        when(fixture.organization().currentUser(any())).thenReturn(fixture.user());
        when(fixture.repository().findByUserAndAppAndReportKey(
                fixture.user(), "venta", "salesReport.tickets"))
                .thenReturn(Optional.of(existing));
        when(fixture.repository().save(any())).thenAnswer(call -> call.getArgument(0));

        var result = fixture.service().save(
                "salesReport.tickets",
                new ReportVisualizationPreferenceService.SavePreferenceRequest(
                        "venta", List.of("ticket", "date", "total")),
                new TestingAuthenticationToken("admin", "token"));

        assertThat(result.visibleAttributes()).containsExactly("ticket", "date", "total");
        verify(fixture.repository()).save(existing);
    }

    @Test
    void rejectsBlankReportKeyAndEmptyAttributes() {
        var fixture = fixture();
        when(fixture.organization().currentUser(any())).thenReturn(fixture.user());

        assertThatThrownBy(() -> fixture.service().save(
                " ",
                new ReportVisualizationPreferenceService.SavePreferenceRequest("venta", List.of("date")),
                new TestingAuthenticationToken("admin", "token")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reportKey");

        assertThatThrownBy(() -> fixture.service().save(
                "salesReport.tickets",
                new ReportVisualizationPreferenceService.SavePreferenceRequest("venta", List.of()),
                new TestingAuthenticationToken("admin", "token")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visibleAttributes");
    }

    @Test
    void rejectsUnknownApp() {
        var fixture = fixture();
        when(fixture.organization().currentUser(any())).thenReturn(fixture.user());

        assertThatThrownBy(() -> fixture.service().save(
                "salesReport.tickets",
                new ReportVisualizationPreferenceService.SavePreferenceRequest("terminal-01", List.of("date")),
                new TestingAuthenticationToken("admin", "token")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app");
    }

    private static Fixture fixture() {
        var repository = org.mockito.Mockito.mock(ReportVisualizationPreferenceRepository.class);
        var organization = org.mockito.Mockito.mock(CurrentOrganization.class);
        var service = new ReportVisualizationPreferenceService(
                repository, organization, Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, repository, organization, user());
    }

    private static UserAccount user() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        var store = new Store(
                new Company("B00000000", "Company", address),
                "Store", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        return new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
    }

    private record Fixture(
            ReportVisualizationPreferenceService service,
            ReportVisualizationPreferenceRepository repository,
            CurrentOrganization organization,
            UserAccount user) {
    }
}
