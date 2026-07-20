package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

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
                .hasMessageContaining("message.terminal.not_found");
        assertThat(foreign.isActiva()).isTrue();
    }

    @Test
    void publicRequestIdentifiesTheStoreExplicitly() {
        assertThat(TerminalController.TerminalRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .contains("tiendaId");
    }

    @Test
    void installationAdminCanProvisionAndRotateTheUniqueServerCredential() {
        var store = store("001");
        var server = new Terminal(store, "SERVIDOR", TerminalType.SERVIDOR, "old-hash");
        var terminals = mock(TerminalRepository.class);
        var stores = mock(StoreRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var audit = mock(AuditService.class);
        when(stores.findAll()).thenReturn(List.of(store));
        when(terminals.findByTiendaIdAndTipo(store.getId(), TerminalType.SERVIDOR))
                .thenReturn(Optional.of(server));
        when(encoder.encode(org.mockito.ArgumentMatchers.anyString())).thenReturn("new-hash");
        var admin = new UserAccount(null, "ADMIN", "hash", new Role(null, "ADMIN"));

        var result = service(terminals, stores, encoder, audit).provisionServer(admin);

        assertThat(result.terminalId()).isEqualTo(server.getId());
        assertThat(result.terminalCredential()).isNotBlank();
        assertThat(server.getCredentialHash()).isEqualTo("new-hash");
        verify(audit).recordForStore(
                org.mockito.ArgumentMatchers.eq(store),
                org.mockito.ArgumentMatchers.eq("SERVER_TERMINAL_PROVISIONED"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyMap());
    }

    private static TerminalRegistrationService service(
            TerminalRepository terminals, StoreRepository stores) {
        return service(terminals, stores, mock(PasswordEncoder.class), mock(AuditService.class));
    }

    private static TerminalRegistrationService service(
            TerminalRepository terminals,
            StoreRepository stores,
            PasswordEncoder encoder,
            AuditService audit) {
        var organization = mock(CurrentOrganization.class);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserAccount user) {
            when(organization.currentStore()).thenReturn(user.getTienda());
        }
        return new TerminalRegistrationService(
                terminals,
                stores,
                organization,
                mock(LicenseRepository.class),
                mock(InstallationStatusService.class),
                encoder,
                mock(UserSessionRepository.class),
                Clock.systemUTC(),
                audit);
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
