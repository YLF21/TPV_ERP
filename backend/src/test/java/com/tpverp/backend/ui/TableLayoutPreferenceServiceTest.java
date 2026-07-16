package com.tpverp.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;

class TableLayoutPreferenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");

    @Test
    void listsOnlyTheCurrentUsersPreferencesForTheRequestedApp() {
        var fixture = fixture();
        var preference = preference(fixture.user(), "venta", "stock.current");
        when(fixture.preferences().findAllByUserAndAppOrderByTableKeyAsc(
                fixture.user(), "venta"))
                .thenReturn(List.of(preference));

        var result = fixture.service().list("venta", authentication());

        assertThat(result.app()).isEqualTo("venta");
        assertThat(result.preferences()).containsExactly(
                TableLayoutPreferenceService.PreferenceView.from(preference));
        verify(fixture.preferences()).findAllByUserAndAppOrderByTableKeyAsc(
                fixture.user(), "venta");
    }

    @Test
    void returnsTheRequestedPreferenceOrEmptyColumns() {
        var fixture = fixture();
        var preference = preference(fixture.user(), "gestion", "products.list");
        when(fixture.preferences().findByUserAndAppAndTableKey(
                fixture.user(), "gestion", "products.list"))
                .thenReturn(Optional.of(preference));

        var existing = fixture.service().get("gestion", "products.list", authentication());
        var missing = fixture.service().get("gestion", "products.offers", authentication());

        assertThat(existing.columns()).isNotEmpty();
        assertThat(missing).isEqualTo(new TableLayoutPreferenceService.PreferenceView(
                "gestion", "products.offers", List.of()));
    }

    @Test
    void createsAStoreIndependentPreferenceForTheAuthenticatedUser() {
        var fixture = fixture();
        when(fixture.preferences().findByUserAndAppAndTableKey(
                fixture.user(), "venta", "stock.current"))
                .thenReturn(Optional.empty());
        when(fixture.preferences().save(any())).thenAnswer(call -> call.getArgument(0));

        var result = fixture.service().save(
                "venta",
                "stock.current",
                new TableLayoutPreferenceService.SavePreferenceRequest(
                        "venta",
                        "stock.current",
                        List.of(new TableLayoutColumn("name", 220, null))),
                authentication());

        var captor = ArgumentCaptor.forClass(TableLayoutPreference.class);
        verify(fixture.preferences()).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(fixture.user());
        assertThat(captor.getValue().getApp()).isEqualTo("venta");
        assertThat(captor.getValue().getTableKey()).isEqualTo("stock.current");
        assertThat(result.columns())
                .containsExactly(new TableLayoutColumn("name", 220, true));
    }

    @Test
    void updatesTheExistingPreferenceForTheSameUserAppAndTable() {
        var fixture = fixture();
        var existing = preference(fixture.user(), "venta", "stock.current");
        when(fixture.preferences().findByUserAndAppAndTableKey(
                fixture.user(), "venta", "stock.current"))
                .thenReturn(Optional.of(existing));
        when(fixture.preferences().save(any())).thenAnswer(call -> call.getArgument(0));

        var result = fixture.service().save(
                "venta",
                "stock.current",
                new TableLayoutPreferenceService.SavePreferenceRequest(
                        "venta",
                        "stock.current",
                        List.of(new TableLayoutColumn("code", 90, false))),
                authentication());

        verify(fixture.preferences()).save(existing);
        assertThat(result.columns())
                .containsExactly(new TableLayoutColumn("code", 90, false));
    }

    @Test
    void rejectsBodyValuesThatDoNotMatchThePath() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.service().save(
                "venta",
                "stock.current",
                new TableLayoutPreferenceService.SavePreferenceRequest(
                        "gestion", "stock.current", List.of()),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app del cuerpo");
        assertThatThrownBy(() -> fixture.service().save(
                "venta",
                "stock.current",
                new TableLayoutPreferenceService.SavePreferenceRequest(
                        "venta", "stock.topSales", List.of()),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tableKey del cuerpo");
        verify(fixture.organization(), never()).currentUser(any());
    }

    @Test
    void rejectsDuplicatedColumnKeys() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.service().save(
                "venta",
                "stock.current",
                new TableLayoutPreferenceService.SavePreferenceRequest(
                        "venta",
                        "stock.current",
                        List.of(
                                new TableLayoutColumn("name", 220, true),
                                new TableLayoutColumn("name", 180, true))),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicada");
        verify(fixture.organization(), never()).currentUser(any());
    }

    @Test
    void usesTheCurrentAuthenticatedUserWithoutAcceptingAClientUserId() {
        var fixture = fixture();
        var otherUser = org.mockito.Mockito.mock(UserAccount.class);
        when(fixture.organization().currentUser(any())).thenReturn(otherUser);
        when(fixture.preferences().findByUserAndAppAndTableKey(
                otherUser, "venta", "stock.current"))
                .thenReturn(Optional.empty());

        fixture.service().get("venta", "stock.current", authentication());

        verify(fixture.preferences()).findByUserAndAppAndTableKey(
                otherUser, "venta", "stock.current");
        verify(fixture.preferences(), never()).findByUserAndAppAndTableKey(
                fixture.user(), "venta", "stock.current");
    }

    private static TableLayoutPreference preference(
            UserAccount user, String app, String tableKey) {
        return new TableLayoutPreference(
                user,
                app,
                tableKey,
                List.of(new TableLayoutColumn("name", 220, true)),
                NOW);
    }

    private static Fixture fixture() {
        var preferences = org.mockito.Mockito.mock(TableLayoutPreferenceRepository.class);
        var organization = org.mockito.Mockito.mock(CurrentOrganization.class);
        var user = org.mockito.Mockito.mock(UserAccount.class);
        when(organization.currentUser(any())).thenReturn(user);
        var service = new TableLayoutPreferenceService(
                preferences,
                organization,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, preferences, organization, user);
    }

    private static TestingAuthenticationToken authentication() {
        return new TestingAuthenticationToken("cashier", "token");
    }

    private record Fixture(
            TableLayoutPreferenceService service,
            TableLayoutPreferenceRepository preferences,
            CurrentOrganization organization,
            UserAccount user) {
    }
}
