package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;

class StockColumnPreferenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-12T10:00:00Z");

    @Test
    void getsOnlyTheCurrentUsersPreferenceInTheCurrentStoreAndApp() {
        var fixture = fixture();
        var preference = new StockColumnPreference(
                fixture.company().getId(),
                fixture.store().getId(),
                fixture.user().getId(),
                "venta",
                columns(),
                NOW);
        when(fixture.preferences().findByCompanyIdAndStoreIdAndUserIdAndApp(
                fixture.company().getId(),
                fixture.store().getId(),
                fixture.user().getId(),
                "venta"))
                .thenReturn(Optional.of(preference));

        var result = fixture.service().get("venta", authentication(fixture.user()));

        assertThat(result.app()).isEqualTo("venta");
        assertThat(result.settings()).containsExactlyEntriesOf(columns());
        verify(fixture.preferences()).findByCompanyIdAndStoreIdAndUserIdAndApp(
                fixture.company().getId(),
                fixture.store().getId(),
                fixture.user().getId(),
                "venta");
    }

    @Test
    void returnsAnEmptyConfigurationWhenTheCurrentUserHasNotSavedOne() {
        var fixture = fixture();
        when(fixture.preferences().findByCompanyIdAndStoreIdAndUserIdAndApp(
                fixture.company().getId(),
                fixture.store().getId(),
                fixture.user().getId(),
                "gestion"))
                .thenReturn(Optional.empty());

        var result = fixture.service().get("GESTION", authentication(fixture.user()));

        assertThat(result.app()).isEqualTo("gestion");
        assertThat(result.settings()).isEmpty();
    }

    @Test
    void resolvesTheCompanyThroughCurrentOrganizationWithoutDereferencingTheDetachedStore() {
        var preferences = org.mockito.Mockito.mock(StockColumnPreferenceRepository.class);
        var organization = org.mockito.Mockito.mock(CurrentOrganization.class);
        var users = org.mockito.Mockito.mock(UserAccountRepository.class);
        var user = org.mockito.Mockito.mock(UserAccount.class);
        var store = org.mockito.Mockito.mock(Store.class);
        var company = org.mockito.Mockito.mock(Company.class);
        var userId = java.util.UUID.randomUUID();
        var storeId = java.util.UUID.randomUUID();
        var companyId = java.util.UUID.randomUUID();
        when(user.getId()).thenReturn(userId);
        when(store.getId()).thenReturn(storeId);
        when(store.getEmpresa()).thenThrow(new AssertionError("No debe inicializar el proxy de tienda"));
        when(company.getId()).thenReturn(companyId);
        when(organization.currentUser(any())).thenReturn(user);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(users.hasStoreAccess(userId, storeId)).thenReturn(true);
        when(preferences.findByCompanyIdAndStoreIdAndUserIdAndApp(
                companyId, storeId, userId, "venta"))
                .thenReturn(Optional.empty());
        var service = new StockColumnPreferenceService(
                preferences,
                organization,
                users,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.get("venta", authentication(user));

        assertThat(result.settings()).isEmpty();
        verify(organization).currentCompany();
        verify(store, never()).getEmpresa();
    }

    @Test
    void protectedAdminUsesTheCurrentTerminalStoreWithoutAnExplicitStoreAssignment() {
        var preferences = org.mockito.Mockito.mock(StockColumnPreferenceRepository.class);
        var organization = org.mockito.Mockito.mock(CurrentOrganization.class);
        var users = org.mockito.Mockito.mock(UserAccountRepository.class);
        var user = org.mockito.Mockito.mock(UserAccount.class);
        var store = org.mockito.Mockito.mock(Store.class);
        var company = org.mockito.Mockito.mock(Company.class);
        var userId = java.util.UUID.randomUUID();
        var storeId = java.util.UUID.randomUUID();
        var companyId = java.util.UUID.randomUUID();
        when(user.getId()).thenReturn(userId);
        when(user.isProtegido()).thenReturn(true);
        when(store.getId()).thenReturn(storeId);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentUser(any())).thenReturn(user);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(preferences.findByCompanyIdAndStoreIdAndUserIdAndApp(
                companyId, storeId, userId, "venta"))
                .thenReturn(Optional.empty());
        var service = new StockColumnPreferenceService(
                preferences,
                organization,
                users,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.get("venta", authentication(user));

        assertThat(result.settings()).isEmpty();
        verify(users, never()).hasStoreAccess(userId, storeId);
    }

    @Test
    void createsOnePreferenceForTheAuthenticatedUserAndApp() {
        var fixture = fixture();
        when(fixture.preferences().findByCompanyIdAndStoreIdAndUserIdAndApp(
                fixture.company().getId(),
                fixture.store().getId(),
                fixture.user().getId(),
                "venta"))
                .thenReturn(Optional.empty());
        when(fixture.preferences().save(any())).thenAnswer(call -> call.getArgument(0));

        var result = fixture.service().save(
                "venta",
                new StockColumnPreferenceService.SavePreferenceRequest("venta", columns()),
                authentication(fixture.user()));

        var captor = ArgumentCaptor.forClass(StockColumnPreference.class);
        verify(fixture.preferences()).save(captor.capture());
        assertThat(captor.getValue().getCompanyId()).isEqualTo(fixture.company().getId());
        assertThat(captor.getValue().getStoreId()).isEqualTo(fixture.store().getId());
        assertThat(captor.getValue().getUserId()).isEqualTo(fixture.user().getId());
        assertThat(captor.getValue().getApp()).isEqualTo("venta");
        assertThat(result.settings()).containsExactlyEntriesOf(columns());
    }

    @Test
    void updatesTheExistingRowInsteadOfCreatingAnotherOne() {
        var fixture = fixture();
        var existing = new StockColumnPreference(
                fixture.company().getId(),
                fixture.store().getId(),
                fixture.user().getId(),
                "venta",
                columns(),
                NOW.minusSeconds(60));
        when(fixture.preferences().findByCompanyIdAndStoreIdAndUserIdAndApp(
                fixture.company().getId(),
                fixture.store().getId(),
                fixture.user().getId(),
                "venta"))
                .thenReturn(Optional.of(existing));
        when(fixture.preferences().save(any())).thenAnswer(call -> call.getArgument(0));
        var changed = Map.of(
                "stock.current",
                List.of(new StockColumnSetting("name", 300), new StockColumnSetting("code", 90)));

        var result = fixture.service().save(
                "venta",
                new StockColumnPreferenceService.SavePreferenceRequest("venta", changed),
                authentication(fixture.user()));

        verify(fixture.preferences()).save(existing);
        assertThat(result.settings()).containsExactlyEntriesOf(changed);
    }

    @Test
    void neverReadsAnotherUsersRowFromAClientSuppliedIdentity() {
        var fixture = fixture();
        var other = new UserAccount(
                fixture.store(), "OTHER", "hash", new Role(fixture.store(), "OTHER"));
        when(fixture.organization().currentUser(any())).thenReturn(other);
        when(fixture.users().hasStoreAccess(other.getId(), fixture.store().getId())).thenReturn(true);
        when(fixture.preferences().findByCompanyIdAndStoreIdAndUserIdAndApp(
                fixture.company().getId(), fixture.store().getId(), other.getId(), "venta"))
                .thenReturn(Optional.empty());

        fixture.service().get("venta", authentication(other));

        verify(fixture.preferences()).findByCompanyIdAndStoreIdAndUserIdAndApp(
                fixture.company().getId(), fixture.store().getId(), other.getId(), "venta");
        verify(fixture.preferences(), never()).findByCompanyIdAndStoreIdAndUserIdAndApp(
                fixture.company().getId(),
                fixture.store().getId(),
                fixture.user().getId(),
                "venta");
    }

    @Test
    void deniesUsersWithoutAccessToTheCurrentStore() {
        var fixture = fixture();
        when(fixture.users().hasStoreAccess(fixture.user().getId(), fixture.store().getId()))
                .thenReturn(false);

        assertThatThrownBy(() -> fixture.service().get("venta", authentication(fixture.user())))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("tienda actual");
        verifyNoInteractions(fixture.preferences());
    }

    @Test
    void rejectsUnknownAppsAndMalformedColumnConfigurations() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.service().save(
                "venta",
                new StockColumnPreferenceService.SavePreferenceRequest("terminal", columns()),
                authentication(fixture.user())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app");
        assertThatThrownBy(() -> fixture.service().save(
                "venta",
                new StockColumnPreferenceService.SavePreferenceRequest("venta", Map.of()),
                authentication(fixture.user())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("settings");
        assertThatThrownBy(() -> fixture.service().save(
                "venta",
                new StockColumnPreferenceService.SavePreferenceRequest(
                        "venta",
                        Map.of("stock.unknown", List.of(new StockColumnSetting("name", 220)))),
                authentication(fixture.user())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vista de stock");
        assertThatThrownBy(() -> fixture.service().save(
                "venta",
                new StockColumnPreferenceService.SavePreferenceRequest(
                        "venta",
                        Map.of("stock.current", List.of(
                                new StockColumnSetting("name", 220),
                                new StockColumnSetting("name", 240)))),
                authentication(fixture.user())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicada");
        assertThatThrownBy(() -> fixture.service().save(
                "venta",
                new StockColumnPreferenceService.SavePreferenceRequest(
                        "venta",
                        Map.of("stock.current", List.of(new StockColumnSetting("name", 421)))),
                authentication(fixture.user())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("72 y 420");
        verifyNoInteractions(fixture.preferences());
    }

    @Test
    void rejectsWhenThePathAndBodyAppsDoNotMatch() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.service().save(
                "venta",
                new StockColumnPreferenceService.SavePreferenceRequest("gestion", columns()),
                authentication(fixture.user())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no coincide");
        verifyNoInteractions(fixture.preferences());
    }

    private static Fixture fixture() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        var company = new Company("B00000000", "Company", address);
        var store = new Store(
                company,
                "Store",
                address,
                "stock-column-preference-hash",
                "Atlantic/Canary",
                "EUR",
                "es-ES");
        var user = new UserAccount(store, "CASHIER", "hash", new Role(store, "CASHIER"));
        var preferences = org.mockito.Mockito.mock(StockColumnPreferenceRepository.class);
        var organization = org.mockito.Mockito.mock(CurrentOrganization.class);
        var users = org.mockito.Mockito.mock(UserAccountRepository.class);
        when(organization.currentUser(any())).thenReturn(user);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(users.hasStoreAccess(user.getId(), store.getId())).thenReturn(true);
        var service = new StockColumnPreferenceService(
                preferences,
                organization,
                users,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, preferences, organization, users, company, store, user);
    }

    private static LinkedHashMap<String, List<StockColumnSetting>> columns() {
        var columns = new LinkedHashMap<String, List<StockColumnSetting>>();
        columns.put("stock.current", List.of(
                new StockColumnSetting("name", 220),
                new StockColumnSetting("code", 110)));
        columns.put("stock.topSales", List.of(
                new StockColumnSetting("ranking", 72),
                new StockColumnSetting("amount", 110)));
        return columns;
    }

    private static TestingAuthenticationToken authentication(UserAccount user) {
        return new TestingAuthenticationToken(user, "token");
    }

    private record Fixture(
            StockColumnPreferenceService service,
            StockColumnPreferenceRepository preferences,
            CurrentOrganization organization,
            UserAccountRepository users,
            Company company,
            Store store,
            UserAccount user) {
    }
}
