package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.TerminalType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class DocumentOperationalTimelineServiceTest {

    private final CommercialDocumentRepository documents = mock(CommercialDocumentRepository.class);
    private final DocumentOperationalEventRepository events = mock(DocumentOperationalEventRepository.class);
    private final DocumentAttributionResolver attributions = mock(DocumentAttributionResolver.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final TerminalRepository terminals = mock(TerminalRepository.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final DocumentOperationalTimelineService service = new DocumentOperationalTimelineService(
            documents, events, attributions, users, terminals, organization);

    private Store store;
    private UserAccount user;
    private Terminal terminal;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES");
        store = new Store(new Company("B00000000", "Company", address),
                "Store", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        user = new UserAccount(store, "CAJERO", "hash", new Role(store, "VENTA"));
        terminal = new Terminal(store, "CAJA 02", TerminalType.TERMINAL_VENTA, "hash");
        when(organization.currentStore()).thenReturn(store);
    }

    @Test
    void returnsChronologicalEventsWithStoreScopedNames() {
        var document = document(CommercialDocumentType.TICKET);
        document.assignOriginTerminal(terminal.getId());
        var occurredAt = Instant.parse("2026-07-18T10:35:00Z");
        var event = new DocumentOperationalEvent(document, DocumentOperationalEventType.CREADO,
                user.getId(), terminal.getId(), occurredAt, Map.of());
        when(documents.findByIdAndTiendaId(document.getId(), store.getId()))
                .thenReturn(Optional.of(document));
        when(events.findAllByDocumentIdOrderByOccurredAtAscIdAsc(document.getId()))
                .thenReturn(List.of(event));
        when(users.findAllById(any())).thenReturn(List.of(user));
        when(terminals.findAllById(any())).thenReturn(List.of(terminal));
        when(attributions.resolve(List.of(document))).thenReturn(Map.of(
                document.getId(), new DocumentAttributionResolver.Attribution(
                        user.getId(), user.getUserName(), terminal.getId(), terminal.getNombre(), occurredAt)));

        var result = service.timeline(document.getId(), authentication("GESTION_VENTAS"));

        assertThat(result.originUserName()).isEqualTo("CAJERO");
        assertThat(result.originTerminalName()).isEqualTo("CAJA 02");
        assertThat(result.events()).singleElement().satisfies(value -> {
            assertThat(value.type()).isEqualTo(DocumentOperationalEventType.CREADO);
            assertThat(value.userName()).isEqualTo("CAJERO");
            assertThat(value.terminalName()).isEqualTo("CAJA 02");
            assertThat(value.occurredAt()).isEqualTo(occurredAt);
        });
    }

    @Test
    void productManagementCannotReadSalesTimeline() {
        var document = document(CommercialDocumentType.TICKET);
        when(documents.findByIdAndTiendaId(document.getId(), store.getId()))
                .thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.timeline(
                document.getId(), authentication("GESTION_PRODUCTO")))
                .isInstanceOf(AccessDeniedException.class);
    }

    private CommercialDocument document(CommercialDocumentType type) {
        return new CommercialDocument(store.getId(), UUID.randomUUID(), type,
                LocalDate.of(2026, 7, 18), user.getId(), BigDecimal.ZERO);
    }

    private UsernamePasswordAuthenticationToken authentication(String permission) {
        return new UsernamePasswordAuthenticationToken(
                "user", "token", List.of(new SimpleGrantedAuthority(permission)));
    }
}
