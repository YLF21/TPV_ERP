package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberCardDeliveryWorkerTest {

    @Mock MemberCardDeliveryRepository deliveries;
    @Mock MemberCardSender sender;

    @Test
    void marksSentDeliveries() {
        var delivery = delivery();
        when(deliveries.findByStatusOrderByCreatedAtAsc(MemberCardDeliveryStatus.PENDIENTE))
                .thenReturn(List.of(delivery));

        var sent = worker().runOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(MemberCardDeliveryStatus.ENVIADO);
        assertThat(delivery.getSentAt()).isEqualTo(Instant.parse("2026-07-02T12:00:00Z"));
    }

    @Test
    void keepsFailuresVisibleForRetry() {
        var delivery = delivery();
        when(deliveries.findByStatusOrderByCreatedAtAsc(MemberCardDeliveryStatus.PENDIENTE))
                .thenReturn(List.of(delivery));
        doThrow(new IllegalStateException("smtp")).when(sender).send(delivery);

        var sent = worker().runOnce();

        assertThat(sent).isZero();
        assertThat(delivery.getStatus()).isEqualTo(MemberCardDeliveryStatus.ERROR);
        assertThat(delivery.getErrorMessage()).isEqualTo("smtp");
    }

    private MemberCardDeliveryWorker worker() {
        return new MemberCardDeliveryWorker(
                deliveries, sender, Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC));
    }

    private static MemberCardDelivery delivery() {
        var company = PartyTestData.company();
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, "cliente@example.com", null, CustomerRate.VENTA, java.math.BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        return new MemberCardDelivery(
                member, "cliente@example.com", "Tarjeta", "Codigo",
                MemberCardCodeFormat.QR, Instant.parse("2026-07-02T11:00:00Z"));
    }
}
