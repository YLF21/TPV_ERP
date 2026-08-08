package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreviousTicketImportIdempotencyTest {

    @Test
    void allPaymentHashesBindTheSourceFingerprintSerialsAndQuoteFingerprint() {
        var sourceTicketId = UUID.randomUUID();
        var sourceLineId = UUID.randomUUID();
        var base = replaySale(
                sourceTicketId, sourceLineId, "source-a", List.of("SN-2", "SN-1"),
                "quote-a");
        var normalizedEquivalent = replaySale(
                sourceTicketId, sourceLineId, "source-a", List.of("SN-1", "SN-2"),
                "quote-a");
        var differentTicket = replaySale(
                UUID.randomUUID(), sourceLineId, "source-a", List.of("SN-1", "SN-2"),
                "quote-a");
        var differentSourceFingerprint = replaySale(
                sourceTicketId, sourceLineId, "source-b", List.of("SN-1", "SN-2"),
                "quote-a");
        var differentSerial = replaySale(
                sourceTicketId, sourceLineId, "source-a", List.of("SN-3", "SN-2"),
                "quote-a");
        var differentQuoteFingerprint = replaySale(
                sourceTicketId, sourceLineId, "source-a", List.of("SN-1", "SN-2"),
                "quote-b");

        assertEquivalentAndDifferent(
                PosCashService.requestHash(cash(base)),
                PosCashService.requestHash(cash(normalizedEquivalent)),
                PosCashService.requestHash(cash(differentTicket)),
                PosCashService.requestHash(cash(differentSourceFingerprint)),
                PosCashService.requestHash(cash(differentSerial)),
                PosCashService.requestHash(cash(differentQuoteFingerprint)));
        assertEquivalentAndDifferent(
                PosCardService.hash(base, new BigDecimal("10.00")),
                PosCardService.hash(normalizedEquivalent, new BigDecimal("10.00")),
                PosCardService.hash(differentTicket, new BigDecimal("10.00")),
                PosCardService.hash(differentSourceFingerprint, new BigDecimal("10.00")),
                PosCardService.hash(differentSerial, new BigDecimal("10.00")),
                PosCardService.hash(differentQuoteFingerprint, new BigDecimal("10.00")));
        assertEquivalentAndDifferent(
                SalePaymentSessionService.hash(base, new BigDecimal("10.00")),
                SalePaymentSessionService.hash(
                        normalizedEquivalent, new BigDecimal("10.00")),
                SalePaymentSessionService.hash(differentTicket, new BigDecimal("10.00")),
                SalePaymentSessionService.hash(
                        differentSourceFingerprint, new BigDecimal("10.00")),
                SalePaymentSessionService.hash(differentSerial, new BigDecimal("10.00")),
                SalePaymentSessionService.hash(
                        differentQuoteFingerprint, new BigDecimal("10.00")));
    }

    @Test
    void paymentSessionHashPreservesTheSignedDocumentDirection() {
        var sale = replaySale(
                UUID.randomUUID(), UUID.randomUUID(), "source", List.of("SN-1"),
                "quote");

        assertThat(SalePaymentSessionService.hash(sale, new BigDecimal("10.00")))
                .isNotEqualTo(SalePaymentSessionService.hash(
                        sale, new BigDecimal("-10.00")));
    }

    private static void assertEquivalentAndDifferent(
            String base,
            String equivalent,
            String... different) {
        assertThat(equivalent).isEqualTo(base);
        assertThat(different).allSatisfy(value -> assertThat(value).isNotEqualTo(base));
    }

    private static PosCashController.CashRequest cash(
            PosCashController.SaleRequest sale) {
        return new PosCashController.CashRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                sale, new BigDecimal("10.00"), new BigDecimal("10.00"));
    }

    private static PosCashController.SaleRequest replaySale(
            UUID sourceTicketId,
            UUID sourceLineId,
            String sourceFingerprint,
            List<String> serials,
            String quoteFingerprint) {
        return new PosCashController.SaleRequest(
                null,
                List.of(),
                null,
                null,
                null,
                null,
                Map.of(),
                new PosCashController.PreviousTicketImportRequest(
                        sourceTicketId,
                        sourceFingerprint,
                        Map.of(sourceLineId, serials)),
                quoteFingerprint);
    }
}
