package com.tpverp.saas.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.sync.SyncEventRequest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reads the exact fixture asserted by the local producer, through normal JSON request deserialization. */
class CommercialDocumentSnapshotContractTest {

    private static final Path FIXTURE = Path.of("..", "contracts", "sync", "commercial-document-v2.json");

    @Test
    void receivesTheSharedProducerPayloadWithExactMoneyLocalIdentitiesAndAdditionalMetadata() throws Exception {
        assertThat(FIXTURE).isRegularFile();
        String httpBody = """
                {
                  "eventId": "00000000-0000-0000-0000-000000000012",
                  "companyId": "00000000-0000-0000-0000-000000000001",
                  "storeId": "00000000-0000-0000-0000-000000000011",
                  "terminalId": "00000000-0000-0000-0000-000000000007",
                  "entityType": "DOCUMENTO",
                  "entityId": "00000000-0000-0000-0000-000000000002",
                  "operation": "CONFIRMAR",
                  "payload": %s
                }
                """.formatted(Files.readString(FIXTURE));
        var mapper = new ObjectMapper();
        var request = mapper.readValue(httpBody, SyncEventRequest.class);

        var snapshot = CommercialDocumentSnapshot.parse(request);
        var metadata = CommercialDocumentQueryMetadata.parse(request);

        assertThat(snapshot.sourceRevision()).isEqualTo(21L);
        assertThat(snapshot.type()).isEqualTo("TICKET");
        assertThat(snapshot.status()).isEqualTo("CONFIRMADO");
        assertThat(snapshot.number()).isEqualTo("T-20260910-0001");
        assertThat(snapshot.businessDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(snapshot.subtotal()).isEqualTo(new BigDecimal("2.47"));
        assertThat(snapshot.taxTotal()).isEqualTo(new BigDecimal("0.17"));
        assertThat(snapshot.total()).isEqualTo(new BigDecimal("2.64"));
        assertThat(snapshot.total().scale()).isEqualTo(2);
        assertThat(snapshot.currency()).isEqualTo("EUR");
        assertThat(snapshot.customerLocalId()).isEqualTo(id(4));
        assertThat(snapshot.createdByLocalId()).isEqualTo(id(5));
        assertThat(snapshot.confirmedByLocalId()).isEqualTo(id(6));
        assertThat(snapshot.originTerminalLocalId()).isEqualTo(id(7));
        assertThat(snapshot.sourceCreatedAt()).isEqualTo(Instant.parse("2026-09-10T10:00:00Z"));
        assertThat(snapshot.sourceConfirmedAt()).isEqualTo(Instant.parse("2026-09-10T10:01:00Z"));
        assertThat(request.payload()).containsKeys("lineas", "pagos", "relaciones", "fechaVencimiento",
                "settledByOrigin", "anuladoPor", "anuladoEn");
        var receivedPayload = mapper.valueToTree(request.payload());
        assertThat(receivedPayload.at("/lineas/0/precioUnitario").textValue()).isEqualTo("1.235");
        assertThat(receivedPayload.at("/pagos/0/importe").textValue()).isEqualTo("2.64");
        assertThat(receivedPayload.at("/relaciones/0/tipo").textValue()).isEqualTo("COMPENSA");
        assertThat(receivedPayload.at("/relaciones/0/origenId").textValue()).isEqualTo(id(10).toString());
        assertThat(receivedPayload.get("fechaVencimiento").textValue()).isEqualTo("2026-10-10");
        assertThat(receivedPayload.get("settledByOrigin").booleanValue()).isFalse();
        assertThat(metadata.cancelledByLocalId()).isNull();
        assertThat(metadata.sourceCancelledAt()).isNull();
        assertThat(metadata.dueDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(metadata.settledByOrigin()).isFalse();
        assertThat(metadata.relationshipsComplete()).isTrue();
        assertThat(metadata.relationships())
                .containsExactly(new CommercialDocumentQueryMetadata.Relation("COMPENSA", id(10)));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
