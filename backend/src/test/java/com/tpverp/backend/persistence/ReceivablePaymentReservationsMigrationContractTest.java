package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReceivablePaymentReservationsMigrationContractTest {

    @Test
    void migrationPersistsAuthoritativeReceivablePaymentReservationsAndPendingSaleLease() throws Exception {
        var sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V91__receivable_payment_reservations.sql"))
                .toLowerCase();

        assertThat(sql).contains(
                "customer_receivable_payment_reservation", "document_id", "amount",
                "request_hash", "status", "lease_owner", "lease_until", "version");
        assertThat(sql).contains(
                "alter table customer_pending_sale_checkout", "processing_owner",
                "processing_lease_until");
        assertThat(sql).contains("check (amount > 0)");
    }
}
