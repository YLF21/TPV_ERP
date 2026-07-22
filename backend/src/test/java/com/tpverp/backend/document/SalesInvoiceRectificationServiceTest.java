package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
class SalesInvoiceRectificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T10:00:00Z");

    @Mock private CommercialDocumentRepository documents;
    @Mock private SalesInvoiceRectificationRepository rectifications;
    @Mock private DocumentRelationRepository relations;
    @Mock private CurrentOrganization organization;
    @Mock private CurrentTerminal currentTerminal;
    @Mock private DocumentOperationalEventRecorder operationalEvents;

    private SalesInvoiceRectificationService service;
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
        when(currentTerminal.terminalId(any())).thenReturn(UUID.randomUUID());
        service = new SalesInvoiceRectificationService(
                documents, rectifications, relations, organization, currentTerminal,
                operationalEvents, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsLinkedR1DifferenceDraftWithoutConfirmingIt() {
        var original = confirmedInvoice();
        var sourceLine = original.getLineas().getFirst();
        when(documents.findLockedDocument(original.getId(), store.getId()))
                .thenReturn(Optional.of(original));
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.createDraft(
                original.getId(),
                request(SalesInvoiceRectificationReason.GOODS_RETURN,
                        sourceLine.getId(), "-1.000", "60.50"),
                authentication());

        assertThat(result.document().getTipo()).isEqualTo(CommercialDocumentType.RECTIFICATIVA_VENTA);
        assertThat(result.document().getEstado()).isEqualTo(DocumentStatus.BORRADOR);
        assertThat(result.document().getNumero()).isNull();
        assertThat(result.document().getTotal()).isNegative();
        assertThat(result.document().isOrigenStock()).isTrue();
        assertThat(result.metadata().getFiscalType()).isEqualTo(SalesInvoiceRectificationFiscalType.R1);
        assertThat(result.metadata().getMethod()).isEqualTo(SalesInvoiceRectificationMethod.I);
        assertThat(result.metadata().isAffectsStock()).isTrue();
        assertThat(result.metadata().getOriginalDocumentId()).isEqualTo(original.getId());
        assertThat(result.document().getLineas().getFirst().getOriginalDocumentLineId())
                .isEqualTo(sourceLine.getId());
        verify(rectifications).save(result.metadata());
        verify(relations).save(any(DocumentRelation.class));
        verify(operationalEvents).record(
                result.document(), DocumentOperationalEventType.CREADO,
                user.getId(), result.document().getTerminalOrigenId(), result.document().getCreadoEn());
    }

    @Test
    void previewsR4EconomicDifferenceWithoutStockOrPersistence() {
        var original = confirmedInvoice();
        var sourceLine = original.getLineas().getFirst();
        when(documents.findByIdAndTiendaId(original.getId(), store.getId()))
                .thenReturn(Optional.of(original));

        var result = service.preview(
                original.getId(),
                request(SalesInvoiceRectificationReason.OTHER,
                        sourceLine.getId(), "-1.000", "10.00"),
                authentication());

        assertThat(result.metadata().getFiscalType()).isEqualTo(SalesInvoiceRectificationFiscalType.R4);
        assertThat(result.metadata().isAffectsStock()).isFalse();
        assertThat(result.document().isOrigenStock()).isFalse();
        assertThat(result.document().getTotal()).isNegative();
        verify(documents, never()).saveAndFlush(any());
        verify(rectifications, never()).save(any());
        verify(relations, never()).save(any());
    }

    @Test
    void rejectsReturnThatExceedsOriginalAvailableQuantity() {
        var original = confirmedInvoice();
        var sourceLine = original.getLineas().getFirst();
        when(documents.findByIdAndTiendaId(original.getId(), store.getId()))
                .thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.preview(
                original.getId(),
                request(SalesInvoiceRectificationReason.GOODS_RETURN,
                        sourceLine.getId(), "-3.000", "60.50"),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("saldo pendiente");
    }

    private CommercialDocument confirmedInvoice() {
        var document = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 7, 21), user.getId(), BigDecimal.ZERO);
        document.setParties(UUID.randomUUID(), null, null);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, new BigDecimal("2.000"),
                "P-1", "Producto fiscal", "VENTA", new BigDecimal("60.50"),
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21.00")));
        document.confirm("FV-001-26-000001", user.getId(), NOW, false);
        return document;
    }

    private static SalesInvoiceRectificationRequest request(
            SalesInvoiceRectificationReason reason,
            UUID originalLineId,
            String quantity,
            String unitPrice) {
        return new SalesInvoiceRectificationRequest(
                reason,
                "Motivo fiscal suficientemente detallado",
                List.of(new SalesInvoiceRectificationRequest.LineRequest(
                        originalLineId, new BigDecimal(quantity), new BigDecimal(unitPrice))));
    }

    private static UsernamePasswordAuthenticationToken authentication() {
        return UsernamePasswordAuthenticationToken.authenticated("ADMIN", "", List.of());
    }
}
