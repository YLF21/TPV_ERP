package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommercialDocumentWholesaleModeTest {

    @Test
    void defaultsHistoricalAndOrdinaryDocumentsToRetailMode() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.now(), UUID.randomUUID(), BigDecimal.ZERO);

        assertThat(document.isWholesaleMode()).isFalse();
    }

    @Test
    void carriesWholesaleModeThroughCardSnapshot() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.now(), UUID.randomUUID(), BigDecimal.ZERO, true);
        var productId = UUID.randomUUID();
        document.addLine(new DocumentLine(
                document, productId, 1, BigDecimal.ONE, "P1", "Producto", "MAYORISTA",
                BigDecimal.TEN, BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));

        var snapshot = ApprovedCardTicketSnapshot.from(document, UUID.randomUUID());

        assertThat(snapshot.wholesaleMode()).isTrue();
    }

    @Test
    void roundTripsWholesaleModeInSnapshotJson() {
        var snapshot = new ApprovedCardTicketSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), null, true,
                UUID.randomUUID(), BigDecimal.ZERO, new BigDecimal("10.00"),
                new BigDecimal("0.00"), new BigDecimal("10.00"),
                java.util.List.of(new DocumentLineCommand(
                        UUID.randomUUID(), BigDecimal.ONE, "P1", "Producto", "VENTA",
                        BigDecimal.TEN, BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO)
                        .withRequiresSerialNumber(false).withDiscountEligible(true)),
                null, null, java.util.List.of());
        var snapshots = new PosCardDocumentSnapshot(new ObjectMapper().findAndRegisterModules());

        assertThat(snapshots.deserialize(snapshots.serialize(snapshot)).wholesaleMode()).isTrue();
    }
}
