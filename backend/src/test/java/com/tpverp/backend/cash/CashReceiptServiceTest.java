package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.security.application.CorePermissionBootstrap;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.security.domain.UsuarioRepository;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.TipoTerminal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class CashReceiptServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-25T09:30:00Z");

    @Test
    void withdrawalReceiptIncludesPrintableDataAndSignatureLabels() {
        var fixture = fixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("100.00"));
        var movement = CashMovement.sessionMovement(
                fixture.store.getId(), fixture.terminal.getId(), session, CashMovementType.RETIRADA_CIERRE,
                new BigDecimal("20.00"), NOW.plusSeconds(60), fixture.user.getId(), null, "retirada cierre",
                null, null);
        movement.addDenomination(new BigDecimal("20.00"), 1);
        when(fixture.movements.findById(movement.getId())).thenReturn(Optional.of(movement));
        when(fixture.sessions.findById(session.getId())).thenReturn(Optional.of(session));

        var receipt = fixture.service.withdrawalReceipt(movement.getId());

        assertThat(receipt.amount()).isEqualByComparingTo("20.00");
        assertThat(receipt.denominations()).containsExactly(
                new CashDenominationCommand(new BigDecimal("20.00"), 1));
        assertThat(receipt.createdAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(receipt.userName()).isEqualTo("SELLER");
        assertThat(receipt.terminalName()).isEqualTo("TPV 1");
        assertThat(receipt.sessionId()).isEqualTo(session.getId());
        assertThat(receipt.giverSignatureLabel()).isEmpty();
        assertThat(receipt.receiverSignatureLabel()).isEmpty();
    }

    @Test
    void withdrawalReceiptRejectsNonWithdrawalMovement() {
        var fixture = fixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("100.00"));
        var movement = CashMovement.sessionMovement(
                fixture.store.getId(), fixture.terminal.getId(), session, CashMovementType.ENTRADA,
                new BigDecimal("20.00"), NOW.plusSeconds(60), fixture.user.getId(), null, "entrada",
                null, null);
        when(fixture.movements.findById(movement.getId())).thenReturn(Optional.of(movement));

        assertThatThrownBy(() -> fixture.service.withdrawalReceipt(movement.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retirada");
    }

    @Test
    void sellerCloseReceiptDoesNotExposeExpectedCash() {
        var fixture = fixture();
        var session = closedSession(fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId());
        when(fixture.sessions.findById(session.getId())).thenReturn(Optional.of(session));

        var receipt = fixture.service.closeReceipt(session.getId(), salesAuthentication(fixture.user));

        assertThat(receipt.retainedFund()).isEqualByComparingTo("95.00");
        assertThat(receipt.discrepancy()).isEqualByComparingTo("-5.00");
        assertThat(receipt.expectedCash()).isNull();
        assertThat(receipt.giverSignatureLabel()).isEmpty();
        assertThat(receipt.receiverSignatureLabel()).isEmpty();
    }

    @Test
    void accountingCloseReceiptIncludesExpectedCash() {
        var fixture = fixture();
        var session = closedSession(fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId());
        when(fixture.sessions.findById(session.getId())).thenReturn(Optional.of(session));

        var receipt = fixture.service.closeReceipt(session.getId(), accountingAuthentication(fixture.user));

        assertThat(receipt.retainedFund()).isEqualByComparingTo("95.00");
        assertThat(receipt.discrepancy()).isEqualByComparingTo("-5.00");
        assertThat(receipt.expectedCash()).isEqualByComparingTo("100.00");
    }

    @Test
    void closeReceiptRejectsOpenSession() {
        var fixture = fixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("100.00"));
        when(fixture.sessions.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> fixture.service.closeReceipt(session.getId(), salesAuthentication(fixture.user)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("abierta");
    }

    private static Fixture fixture() {
        var store = store();
        var user = new Usuario(store, "SELLER", "hash", new Rol(store, "SELLER"));
        var terminal = new Terminal(store, "TPV 1", TipoTerminal.TERMINAL_VENTA, "hash");
        var sessions = mock(CashSessionRepository.class);
        var movements = mock(CashMovementRepository.class);
        var terminals = mock(TerminalRepository.class);
        var users = mock(UsuarioRepository.class);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(org.mockito.ArgumentMatchers.any())).thenReturn(user);
        when(terminals.findByIdAndTiendaId(terminal.getId(), store.getId())).thenReturn(Optional.of(terminal));
        when(users.findByIdAndTiendaId(user.getId(), store.getId())).thenReturn(Optional.of(user));
        var service = new CashReceiptService(
                sessions, movements, terminals, users, organization,
                new CashPermissionService(null, null, organization));
        return new Fixture(service, sessions, movements, store, user, terminal);
    }

    private static CashSession closedSession(UUID storeId, UUID terminalId, UUID userId) {
        var session = CashSession.open(storeId, terminalId, userId, NOW, new BigDecimal("100.00"));
        session.close(userId, NOW.plusSeconds(120), new BigDecimal("100.00"),
                new BigDecimal("95.00"), new BigDecimal("-5.00"));
        return session;
    }

    private static UsernamePasswordAuthenticationToken salesAuthentication(Usuario user) {
        return new UsernamePasswordAuthenticationToken(
                user, "token", List.of(new SimpleGrantedAuthority(CorePermissionBootstrap.GESTION_VENTAS)));
    }

    private static UsernamePasswordAuthenticationToken accountingAuthentication(Usuario user) {
        return new UsernamePasswordAuthenticationToken(
                user, "token", List.of(new SimpleGrantedAuthority(CorePermissionBootstrap.GESTION_CUENTAS)));
    }

    private static Tienda store() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Tienda(
                new Empresa("B00000000", "Empresa", address),
                "001", "Tienda", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }

    private record Fixture(
            CashReceiptService service,
            CashSessionRepository sessions,
            CashMovementRepository movements,
            Tienda store,
            Usuario user,
            Terminal terminal) {
    }
}
