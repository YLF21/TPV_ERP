package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class SaleLineDeletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-28T12:00:00Z");

    @Mock private JdbcTemplate jdbc;
    @Mock private CurrentOrganization organization;
    @Mock private CurrentTerminal currentTerminal;
    @Mock private com.tpverp.backend.control.ControlAlertDetectionService controlAlerts;

    private SaleLineDeletionService service;
    private Store store;
    private UserAccount user;
    private UUID terminalId;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        store = new Store(
                new Company("B00000000", "Company", address),
                "Store", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        user = new UserAccount(store, "SELLER", "hash", new Role(store, "SELLER"));
        terminalId = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(organization.currentStore()).thenReturn(store);
        org.mockito.Mockito.lenient().when(organization.currentUser(any())).thenReturn(user);
        org.mockito.Mockito.lenient().when(currentTerminal.terminalId(any())).thenReturn(terminalId);
        org.mockito.Mockito.lenient()
                .when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        org.mockito.Mockito.lenient().when(jdbc.queryForObject(anyString(),
                org.mockito.ArgumentMatchers.eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        org.mockito.Mockito.lenient().when(jdbc.query(anyString(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<SaleLineDeletionView>>any(),
                any(Object[].class))).thenReturn(List.of());
        service = new SaleLineDeletionService(
                jdbc, organization, currentTerminal, controlAlerts,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordsDeletedLineWithUserTerminalAndTotal() {
        var productId = UUID.randomUUID();
        var saleOperationId = UUID.randomUUID();
        var deletionOperationId = UUID.randomUUID();

        var result = service.record(saleOperationId, deletionOperationId,
                List.of(new SaleLineDeletionCommand(
                        productId, "P-1", "Producto", 2, new BigDecimal("3.50"))),
                false, auth());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().type()).isEqualTo("LINEA");
        assertThat(result.getFirst().terminalId()).isEqualTo(terminalId);
        assertThat(result.getFirst().userId()).isEqualTo(user.getId());
        assertThat(result.getFirst().total()).isEqualByComparingTo("7.00");
        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
        verify(controlAlerts).detectConsecutiveLineDeletions(
                org.mockito.ArgumentMatchers.eq(saleOperationId),
                org.mockito.ArgumentMatchers.eq(1),
                any(), org.mockito.ArgumentMatchers.eq(terminalId), any());
    }

    @Test
    void fullTicketClearStoresListType() {
        var deletionOperationId = UUID.randomUUID();
        var result = service.record(UUID.randomUUID(), deletionOperationId,
                List.of(new SaleLineDeletionCommand(
                        UUID.randomUUID(), "P-1", "Producto", 1, new BigDecimal("10.00"))),
                true, auth());

        assertThat(result.getFirst().type()).isEqualTo("LISTA");
        verify(controlAlerts).detectSaleScreenCleared(
                org.mockito.ArgumentMatchers.eq(deletionOperationId),
                org.mockito.ArgumentMatchers.eq(result),
                org.mockito.ArgumentMatchers.eq(terminalId), any());
    }

    @Test
    void repeatedDeletionOperationIsReturnedWithoutCountingOrEmittingAgain() {
        var headerAttempts = new java.util.concurrent.atomic.AtomicInteger();
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("venta_operacion_eliminacion")
                        ? (headerAttempts.getAndIncrement() == 0 ? 1 : 0)
                        : 1);
        var saleOperationId = UUID.randomUUID();
        var deletionOperationId = UUID.randomUUID();
        var command = new SaleLineDeletionCommand(
                UUID.randomUUID(), "P-1", "Producto", 1, new BigDecimal("10.00"));

        service.record(saleOperationId, deletionOperationId,
                List.of(command), false, auth());
        var repeated = service.record(saleOperationId, deletionOperationId,
                List.of(command), false, auth());

        assertThat(repeated).isEmpty();
        verify(controlAlerts, times(1)).detectConsecutiveLineDeletions(
                org.mockito.ArgumentMatchers.eq(saleOperationId), any(Integer.class),
                any(), org.mockito.ArgumentMatchers.eq(terminalId), any());
    }

    @Test
    void purgesExpiredLinesBeforeTheirOperationHeaders() {
        service.purgeExpired();

        var ordered = inOrder(jdbc);
        ordered.verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("delete from venta_linea_eliminada"),
                org.mockito.ArgumentMatchers.eq(NOW.minus(365, ChronoUnit.DAYS)));
        ordered.verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("delete from venta_operacion_eliminacion"),
                org.mockito.ArgumentMatchers.eq(NOW.minus(365, ChronoUnit.DAYS)));
    }

    private static UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken("SELLER", "token");
    }
}
