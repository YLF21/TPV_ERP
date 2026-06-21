package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.LicenciaRepository;
import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.SesionRepository;
import com.tpverp.backend.security.domain.Usuario;
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
                currentStore, "CURRENT", TipoTerminal.TERMINAL_VENTA, "hash");
        var foreign = new Terminal(
                foreignStore, "FOREIGN", TipoTerminal.TERMINAL_VENTA, "hash");
        var terminals = mock(TerminalRepository.class);
        when(terminals.findAllByTiendaIdOrderByNombre(currentStore.getId()))
                .thenReturn(List.of(current));
        authenticate(currentStore);

        var result = service(terminals, mock(TiendaRepository.class)).list();

        assertThat(result).extracting(TerminalRegistrationService.TerminalItem::name)
                .containsExactly("CURRENT");
    }

    @Test
    void cannotDeactivateTerminalFromAnotherStore() {
        var currentStore = store("001");
        var foreignStore = store("002");
        var foreign = new Terminal(
                foreignStore, "FOREIGN", TipoTerminal.TERMINAL_VENTA, "hash");
        var terminals = mock(TerminalRepository.class);
        when(terminals.findByIdAndTiendaId(foreign.getId(), currentStore.getId()))
                .thenReturn(Optional.empty());
        authenticate(currentStore);

        assertThatThrownBy(() -> service(terminals, mock(TiendaRepository.class))
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
            TerminalRepository terminals, TiendaRepository stores) {
        var organization = mock(CurrentOrganization.class);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var user = (Usuario) authentication.getPrincipal();
        when(organization.currentStore()).thenReturn(user.getTienda());
        return new TerminalRegistrationService(
                terminals,
                stores,
                organization,
                mock(LicenciaRepository.class),
                mock(InstallationStatusService.class),
                mock(PasswordEncoder.class),
                mock(SesionRepository.class),
                Clock.systemUTC(),
                mock(AuditService.class));
    }

    private static void authenticate(Tienda store) {
        var user = new Usuario(store, "ADMIN", "hash", new Rol(store, "ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "token"));
    }

    private static Tienda store(String code) {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Tienda(
                new Empresa("B00000000", "Empresa", address),
                code, "Tienda", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }
}
