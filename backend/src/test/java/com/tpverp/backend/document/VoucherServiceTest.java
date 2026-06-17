package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.Usuario;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoucherServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-17T10:00:00Z");

    @Mock
    private VoucherRepository vouchers;
    @Mock
    private CurrentOrganization organization;

    private VoucherService service;
    private Tienda store;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        store = new Tienda(
                new Empresa("B00000000", "Empresa", address),
                "Tienda", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        var user = new Usuario(store, "ADMIN", "hash", new Rol(store, "ADMIN"));
        when(organization.currentStore()).thenReturn(store);
        service = new VoucherService(
                vouchers, organization, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void negativeTicketIssuesVoucherAndPartialUseCreatesReplacementWithLineage() {
        when(vouchers.save(any())).thenAnswer(call -> call.getArgument(0));
        var sourceTicket = ticket("001-260617-00001", "-100.00");

        var issued = service.issueFromNegativeTicket(sourceTicket);
        when(vouchers.findByTiendaIdAndCode(store.getId(), issued.code()))
                .thenReturn(Optional.of(issued));

        var result = service.consume(
                issued.code(), new BigDecimal("20.00"), ticket("001-260617-00002", "20.00"));

        assertThat(issued.balance()).isZero();
        assertThat(result.consumedAmount()).isEqualByComparingTo("20.00");
        assertThat(result.replacement()).isPresent();
        assertThat(result.replacement().orElseThrow().balance())
                .isEqualByComparingTo("80.00");
        assertThat(result.replacement().orElseThrow().originTickets())
                .containsExactly("001-260617-00001", "001-260617-00002");
    }

    private Documento ticket(String number, String total) {
        var document = new Documento(
                store.getId(), UUID.randomUUID(), TipoDocumento.TICKET,
                LocalDate.of(2026, 6, 17), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentoLinea(
                document, UUID.randomUUID(), 1, new BigDecimal(total).signum(),
                "P-1", "Producto", "VENTA", new BigDecimal(total).abs(),
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21")));
        document.confirm(number, UUID.randomUUID(), NOW, false);
        return document;
    }
}
