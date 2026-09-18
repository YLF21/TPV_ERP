package com.tpverp.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.TestingAuthenticationToken;

class DashboardPreferenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Test
    void returnsOnlyDefaultsAllowedByTheUsersDataPermissions() {
        var fixture = fixture();
        when(fixture.preferences().findByUser(fixture.user())).thenReturn(Optional.empty());

        var result = fixture.service().get(authentication("GESTION_VENTAS"));

        assertThat(result.availableWidgets()).containsExactly(
                "sales.today", "sales.operations", "sales.average", "sales.trend", "sales.top-products");
        assertThat(result.widgets()).containsExactly(
                new DashboardWidgetLayout("sales.today", 4, 1),
                new DashboardWidgetLayout("sales.operations", 4, 1),
                new DashboardWidgetLayout("sales.average", 4, 1),
                new DashboardWidgetLayout("sales.trend", 12, 2),
                new DashboardWidgetLayout("sales.top-products", 8, 2));
        assertThat(result.options()).isEqualTo(DashboardOptions.defaults());
        assertThat(result.businessDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 18));
        assertThat(result.storeTimezone()).isEqualTo("Atlantic/Canary");
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
        when(fixture.preferences().saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        var result = fixture.service().save(
                new DashboardPreferenceService.SavePreferenceRequest(List.of(
                        new DashboardWidgetLayout("sales.today", 8, 2))),
                authentication("GESTION_VENTAS"));

        verify(fixture.preferences()).saveAndFlush(existing);
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
                "sales.today", "sales.operations", "sales.average", "sales.trend", "sales.top-products",
                "promotions.active", "control.alerts");
    }

    @Test
    void keepsSavedLayoutsAndOptionsWhenAnOlderClientOnlySendsWidgets() {
        var fixture = fixture();
        var layout = List.of(new DashboardWidgetLayout("sales.top-products", 12, 3));
        var options = new DashboardOptions("LAST_7_DAYS", "TABLE", "TABLE", "COMPACT", false);
        var existing = new DashboardPreference(fixture.user(), layout, NOW);
        existing.update(layout, options, NOW);
        when(fixture.preferences().findByUser(fixture.user())).thenReturn(Optional.of(existing));

        var loaded = fixture.service().get(authentication("GESTION_VENTAS"));
        assertThat(loaded.widgets()).containsExactlyElementsOf(layout);
        assertThat(loaded.options()).isEqualTo(options);

        var saved = fixture.service().save(new DashboardPreferenceService.SavePreferenceRequest(layout),
                authentication("GESTION_VENTAS"));
        assertThat(saved.options()).isEqualTo(options);
    }

    @Test
    void savesExplicitOptionsAndAnIntentionallyEmptyDashboard() {
        var fixture = fixture();
        when(fixture.preferences().findByUser(fixture.user())).thenReturn(Optional.empty());
        var options = new DashboardOptions("TODAY", "BAR", "TABLE", "COMPACT", false);

        var result = fixture.service().save(new DashboardPreferenceService.SavePreferenceRequest(List.of(), options),
                authentication("GESTION_VENTAS"));

        assertThat(result.widgets()).isEmpty();
        assertThat(result.options()).isEqualTo(options);
    }

    @Test
    void returnsBusinessDateInTheStoreTimezone() {
        var fixture = fixture();
        var store = org.mockito.Mockito.mock(Store.class);
        when(store.getTimezone()).thenReturn("Pacific/Honolulu");
        when(fixture.organization().currentStore()).thenReturn(store);
        var service = new DashboardPreferenceService(fixture.preferences(), fixture.organization(),
                Clock.fixed(Instant.parse("2026-07-18T01:00:00Z"), ZoneOffset.UTC));

        assertThat(service.get(authentication("GESTION_VENTAS")).businessDate())
                .isEqualTo(java.time.LocalDate.of(2026, 7, 17));
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

    @Test
    void translatesOnlyConcurrentWriteFailuresAndPreservesOtherIntegrityErrors() {
        var fixture = fixture();
        var request = new DashboardPreferenceService.SavePreferenceRequest(
                List.of(new DashboardWidgetLayout("sales.today", 4, 1)));
        var auth = authentication("GESTION_VENTAS");
        var concurrentInsert = new DataIntegrityViolationException("insert failed",
                new ConstraintViolationException("unique constraint", new SQLException("conflict", "23505"),
                        "preferencia_dashboard_usuario_uq"));
        var otherIntegrity = new DataIntegrityViolationException("invalid timestamp",
                new ConstraintViolationException("check constraint", new SQLException("invalid", "23514"),
                        "preferencia_dashboard_fechas_ck"));
        when(fixture.preferences().saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(DashboardPreference.class, "preference"))
                .thenThrow(concurrentInsert)
                .thenThrow(otherIntegrity);

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> fixture.service().save(request, auth))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("message.dashboard.preference_conflict");
        }
        assertThatThrownBy(() -> fixture.service().save(request, auth)).isSameAs(otherIntegrity);
    }

    private static Fixture fixture() {
        var preferences = org.mockito.Mockito.mock(DashboardPreferenceRepository.class);
        var organization = org.mockito.Mockito.mock(CurrentOrganization.class);
        var user = org.mockito.Mockito.mock(UserAccount.class);
        var store = org.mockito.Mockito.mock(Store.class);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(organization.currentStore()).thenReturn(store);
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
