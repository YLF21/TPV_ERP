package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class CashReportServiceTest {

    private static final Instant FROM = Instant.parse("2026-06-25T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-26T00:00:00Z");

    @Test
    void reportAggregatesMovementTotalsRetainedFundsAndDiscrepancies() {
        var fixture = fixture();
        var session = closedSession(fixture.store().getId(), fixture.terminalId(), fixture.userId());
        when(fixture.movements().findAllByTiendaIdAndTerminalIdAndCreadoEnBetweenOrderByCreadoEnAsc(
                fixture.store().getId(), fixture.terminalId(), FROM, TO))
                .thenReturn(List.of(
                        movement(fixture, CashMovementType.COBRO_EFECTIVO, "100.00"),
                        movement(fixture, CashMovementType.RETIRADA, "15.00"),
                        movement(fixture, CashMovementType.COBRO_EFECTIVO, "20.00")));
        when(fixture.sessions().findAllByTiendaIdAndTerminalIdAndClosedAtBetweenOrderByClosedAtDesc(
                fixture.store().getId(), fixture.terminalId(), FROM, TO))
                .thenReturn(List.of(session));

        var report = fixture.service().report(
                fixture.terminalId(), fixture.store().getId(), FROM, TO,
                new TestingAuthenticationToken("accounting", "token"));

        assertThat(report.totalsByType())
                .containsEntry(CashMovementType.COBRO_EFECTIVO, new BigDecimal("120.00"))
                .containsEntry(CashMovementType.RETIRADA, new BigDecimal("15.00"));
        assertThat(report.retainedFunds()).isEqualByComparingTo("80.00");
        assertThat(report.discrepancies()).isEqualByComparingTo("-2.50");
        verify(fixture.permissions()).requireReportPermission(any());
    }

    @Test
    void updateConfigRejectsNegativeTolerance() {
        var fixture = fixture();
        when(fixture.configs().findById(fixture.store().getId()))
                .thenReturn(Optional.of(new CashStoreConfig(fixture.store().getId())));

        assertThatThrownBy(() -> fixture.service().updateConfig(
                new CashStoreConfigRequest(new BigDecimal("-0.01"), true, false, true),
                new TestingAuthenticationToken("admin", "token")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tolerancia_descuadre");
    }

    @Test
    void updateConfigRejectsMissingWithdrawalBreakdownFlag() {
        var fixture = fixture();
        when(fixture.configs().findById(fixture.store().getId()))
                .thenReturn(Optional.of(new CashStoreConfig(fixture.store().getId())));

        assertThatThrownBy(() -> fixture.service().updateConfig(
                new CashStoreConfigRequest(new BigDecimal("1.00"), true, null, true),
                new TestingAuthenticationToken("admin", "token")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requireWithdrawalBreakdown");
    }

    @Test
    void updateConfigRejectsMissingDiscrepancyTolerance() {
        var fixture = fixture();
        when(fixture.configs().findById(fixture.store().getId()))
                .thenReturn(Optional.of(new CashStoreConfig(fixture.store().getId())));

        assertThatThrownBy(() -> fixture.service().updateConfig(
                new CashStoreConfigRequest(null, true, false, true),
                new TestingAuthenticationToken("admin", "token")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("discrepancyTolerance");
    }

    private static CashMovement movement(
            Fixture fixture,
            CashMovementType type,
            String amount) {
        return CashMovement.sessionMovement(
                fixture.store().getId(),
                fixture.terminalId(),
                UUID.randomUUID(),
                type,
                new BigDecimal(amount),
                FROM.plusSeconds(60),
                fixture.userId(),
                null,
                type.name(),
                null,
                null);
    }

    private static CashSession closedSession(UUID storeId, UUID terminalId, UUID userId) {
        var session = CashSession.open(
                storeId, terminalId, userId, FROM.plusSeconds(3600), new BigDecimal("50.00"));
        session.close(
                userId, FROM.plusSeconds(7200), new BigDecimal("82.50"),
                new BigDecimal("80.00"), new BigDecimal("-2.50"));
        return session;
    }

    private static Fixture fixture() {
        var store = store();
        var movements = mock(CashMovementRepository.class);
        var sessions = mock(CashSessionRepository.class);
        var configs = mock(CashStoreConfigRepository.class);
        var organization = mock(CurrentOrganization.class);
        var permissions = mock(CashPermissionService.class);
        when(organization.currentStore()).thenReturn(store);
        var service = new CashReportService(movements, sessions, configs, organization, permissions);
        return new Fixture(
                service, movements, sessions, configs, permissions,
                store, UUID.randomUUID(), UUID.randomUUID());
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
            CashReportService service,
            CashMovementRepository movements,
            CashSessionRepository sessions,
            CashStoreConfigRepository configs,
            CashPermissionService permissions,
            Tienda store,
            UUID terminalId,
            UUID userId) {
    }
}
