package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class ParkedSaleServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-17T10:00:00Z");

    @Mock
    private ParkedSaleRepository repository;
    @Mock
    private CurrentOrganization organization;

    private ParkedSaleService service;
    private Store store;
    private UserAccount user;

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
        user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(any())).thenReturn(user);
        service = new ParkedSaleService(
                repository, organization, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void parksSaleWithoutTicketNumberAndRemovesItWhenOpened() {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        var parked = service.park(command(), "Cliente vuelve en 5 min", auth());
        when(repository.findByIdAndTiendaId(parked.getId(), store.getId()))
                .thenReturn(Optional.of(parked));

        var opened = service.openAndRemove(parked.getId());

        assertThat(parked.getTicketNumber()).isNull();
        assertThat(parked.getComment()).isEqualTo("Cliente vuelve en 5 min");
        assertThat(parked.getTotal()).isEqualByComparingTo("10.00");
        assertThat(opened.document().tipo()).isEqualTo(CommercialDocumentType.TICKET);
        verify(repository).delete(parked);
    }

    private static DocumentCommand command() {
        return new DocumentCommand(
                UUID.randomUUID(),
                CommercialDocumentType.TICKET,
                LocalDate.of(2026, 6, 17),
                UUID.randomUUID(),
                null,
                null,
                BigDecimal.ZERO,
                false,
                List.of(new DocumentLineCommand(
                        UUID.randomUUID(), 1, "P-1", "Producto", "VENTA",
                        new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                        new BigDecimal("21"))));
    }

    private static UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken("ADMIN", "n/a");
    }
}
