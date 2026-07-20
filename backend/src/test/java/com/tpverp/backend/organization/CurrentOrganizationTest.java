package com.tpverp.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.security.domain.OperationalSessionContext;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentOrganizationTest {

    private final StoreRepository stores = mock(StoreRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final CurrentOrganization organization = new CurrentOrganization(stores, users);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesGlobalAdminStoreFromAuthenticatedTerminalContext() {
        var operationalStore = store("Operational");
        var globalAdmin = new UserAccount(null, "ADMIN", "hash", new Role(null, "ADMIN"));
        var authentication = new UsernamePasswordAuthenticationToken(globalAdmin, "token");
        authentication.setDetails(new OperationalSessionContext(UUID.randomUUID(), operationalStore.getId()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(stores.findWithCompanyById(operationalStore.getId())).thenReturn(Optional.of(operationalStore));

        assertThat(organization.currentStore()).isSameAs(operationalStore);
        verify(stores).findWithCompanyById(operationalStore.getId());
    }

    @Test
    void terminalContextOverridesUsersHomeStoreForAuthorizedMultiStoreSession() {
        var homeStore = store("Home");
        var operationalStore = store("Operational");
        var user = new UserAccount(homeStore, "MANAGER", "hash", new Role(homeStore, "MANAGER"));
        var authentication = new UsernamePasswordAuthenticationToken(user, "token");
        authentication.setDetails(new OperationalSessionContext(UUID.randomUUID(), operationalStore.getId()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(stores.findWithCompanyById(operationalStore.getId())).thenReturn(Optional.of(operationalStore));

        assertThat(organization.currentStore()).isSameAs(operationalStore);
    }

    @Test
    void keepsAssignedStoreWhenAuthenticationHasNoTerminalContext() {
        var assignedStore = store("Assigned");
        var user = new UserAccount(assignedStore, "USER", "hash", new Role(assignedStore, "USER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "token"));

        assertThat(organization.currentStore()).isSameAs(assignedStore);
        verifyNoInteractions(stores);
    }

    @Test
    void installationAdminWithoutTerminalCannotAccessAnArbitraryStore() {
        var globalAdmin = new UserAccount(null, "ADMIN", "hash", new Role(null, "ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(globalAdmin, "token"));

        assertThatThrownBy(organization::currentStore)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.organization.store_not_initialized");
        verifyNoInteractions(stores);
    }

    private static Store store(String name) {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Store(
                new Company("B00000000", "Company", address),
                name, address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }
}
