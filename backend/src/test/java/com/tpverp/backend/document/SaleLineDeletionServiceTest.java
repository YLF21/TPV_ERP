package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(any())).thenReturn(user);
        when(currentTerminal.terminalId(any())).thenReturn(terminalId);
        service = new SaleLineDeletionService(
                jdbc, organization, currentTerminal, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordsDeletedLineWithUserTerminalAndTotal() {
        var productId = UUID.randomUUID();

        var result = service.record(List.of(new SaleLineDeletionCommand(
                productId, "P-1", "Producto", 2, new BigDecimal("3.50"))), false, auth());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().type()).isEqualTo("LINEA");
        assertThat(result.getFirst().terminalId()).isEqualTo(terminalId);
        assertThat(result.getFirst().userId()).isEqualTo(user.getId());
        assertThat(result.getFirst().total()).isEqualByComparingTo("7.00");
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void fullTicketClearStoresListType() {
        var result = service.record(List.of(new SaleLineDeletionCommand(
                UUID.randomUUID(), "P-1", "Producto", 1, new BigDecimal("10.00"))), true, auth());

        assertThat(result.getFirst().type()).isEqualTo("LISTA");
    }

    private static UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken("SELLER", "token");
    }
}
