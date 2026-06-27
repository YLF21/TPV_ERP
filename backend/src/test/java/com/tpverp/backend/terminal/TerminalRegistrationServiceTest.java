package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.security.domain.UserAccount;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

class TerminalRegistrationServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listOnlyReturnsTerminalsFromAuthenticatedStore() {
        var currentStore = store("001");
        var foreignStore = store("002");
        var current = new Terminal(
                currentStore, "CURRENT", TerminalType.TERMINAL_VENTA, "hash");
        var foreign = new Terminal(
                foreignStore, "FOREIGN", TerminalType.TERMINAL_VENTA, "hash");
        var terminals = mock(TerminalRepository.class);
        when(terminals.findAllByTiendaIdOrderByNombre(currentStore.getId()))
                .thenReturn(List.of(current));
        authenticate(currentStore);

        var result = service(terminals, mock(StoreRepository.class)).list();

        assertThat(result).extracting(TerminalRegistrationService.TerminalItem::name)
                .containsExactly("CURRENT");
    }

    @Test
    void cannotDeactivateTerminalFromAnotherStore() {
        var currentStore = store("001");
        var foreignStore = store("002");
        var foreign = new Terminal(
                foreignStore, "FOREIGN", TerminalType.TERMINAL_VENTA, "hash");
        var terminals = mock(TerminalRepository.class);
        when(terminals.findByIdAndTiendaId(foreign.getId(), currentStore.getId()))
                .thenReturn(Optional.empty());
        authenticate(currentStore);

        assertThatThrownBy(() -> service(terminals, mock(StoreRepository.class))
                .deactivate(foreign.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Terminal no encontrada");
        assertThat(foreign.isActiva()).isTrue();
    }

    @Test
    void publicRequestIdentifiesTheStoreExplicitly() {
        assertThat(TerminalController.TerminalRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .contains("tiendaId");
    }

    private static TerminalRegistrationService service(
            TerminalRepository terminals, StoreRepository stores) {
        var organization = mock(CurrentOrganization.class);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var user = (UserAccount) authentication.getPrincipal();
        when(organization.currentStore()).thenReturn(user.getTienda());
        return new TerminalRegistrationService(
                terminals,
                stores,
                organization,
                mock(LicenseRepository.class),
                mock(InstallationStatusService.class),
                mock(PasswordEncoder.class),
                mock(UserSessionRepository.class),
                Clock.systemUTC(),
                mock(AuditService.class));
    }

    private static void authenticate(Store store) {
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "token"));
    }

    private static Store store(String code) {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Store(
                new Company("B00000000", "Company", address),
                code, "Store", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }
}
