package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerReceivablePaymentReservationCoordinatorTest {

    @Test
    void serializesOnDocumentAndSubtractsOtherActiveReservationsBeforePhysicalEffect() {
        var documents = mock(CommercialDocumentRepository.class);
        var reservations = mock(CustomerReceivablePaymentReservationRepository.class);
        var coordinator = new CustomerReceivablePaymentReservationCoordinator(
                documents, reservations,
                Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), ZoneOffset.UTC));
        var storeId = UUID.randomUUID();
        var document = receivable(storeId, "100.00");
        var active = CustomerReceivablePaymentReservation.reserve(
                UUID.randomUUID(), document.getId(), storeId, UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64), new BigDecimal("60.00"),
                CustomerReceivablePaymentReservation.Kind.INTEGRATED_CARD,
                UUID.randomUUID(), Instant.parse("2026-07-16T10:00:30Z"),
                Instant.parse("2026-07-16T09:59:59Z"));
        when(documents.findLockedReceivable(document.getId(), storeId))
                .thenReturn(Optional.of(document));
        when(reservations.findLockedById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        when(reservations.findAllLockedByDocumentId(document.getId()))
                .thenReturn(List.of(active));

        assertThatThrownBy(() -> coordinator.acquire(
                UUID.randomUUID(), document.getId(), storeId, UUID.randomUUID(), UUID.randomUUID(),
                "b".repeat(64), new BigDecimal("60.00"),
                CustomerReceivablePaymentReservation.Kind.STANDARD, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pending");

        verify(documents).findLockedReceivable(document.getId(), storeId);
        verify(reservations).findAllLockedByDocumentId(document.getId());
    }

    @Test
    void rejectsAnonymousDocumentBeforeCreatingPhysicalPaymentReservation() {
        var documents = mock(CommercialDocumentRepository.class);
        var reservations = mock(CustomerReceivablePaymentReservationRepository.class);
        var coordinator = new CustomerReceivablePaymentReservationCoordinator(
                documents, reservations,
                Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), ZoneOffset.UTC));
        var storeId = UUID.randomUUID();
        var document = receivable(storeId, "100.00");
        document.setParties(null, null, null);
        when(documents.findLockedReceivable(document.getId(), storeId))
                .thenReturn(Optional.of(document));

        assertThatThrownBy(() -> coordinator.acquire(
                UUID.randomUUID(), document.getId(), storeId, UUID.randomUUID(), UUID.randomUUID(),
                "b".repeat(64), new BigDecimal("20.00"),
                CustomerReceivablePaymentReservation.Kind.INTEGRATED_CARD, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("customer_required");

        verify(reservations, org.mockito.Mockito.never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    private static CommercialDocument receivable(UUID storeId, String total) {
        var document = new CommercialDocument(
                storeId, UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                java.time.LocalDate.of(2026, 7, 1), UUID.randomUUID(), BigDecimal.ZERO);
        document.setParties(UUID.randomUUID(), null, null);
        document.setDueDate(java.time.LocalDate.of(2026, 8, 1));
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, 1, "P-1", "Producto", "VENTA",
                new BigDecimal(total), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        document.confirm("FV-001-26-000001", UUID.randomUUID(), Instant.now(), false);
        return document;
    }
}
