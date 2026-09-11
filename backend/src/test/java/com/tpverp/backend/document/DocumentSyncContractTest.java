package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Both Maven modules consume this repository-level fixture to detect producer/receiver contract drift. */
class DocumentSyncContractTest {

    private static final Path FIXTURE = Path.of("..", "contracts", "sync", "commercial-document-v2.json");
    private static final UUID COMPANY = id(1);
    private static final UUID DOCUMENT = id(2);
    private static final UUID STORE = id(11);

    @Test
    void realPayloadFactoryMatchesTheSharedHttpContractIncludingThreeDecimalUnitPrice() throws Exception {
        var document = new CommercialDocument(STORE, id(3), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 9, 10), id(5), BigDecimal.ZERO);
        ReflectionTestUtils.setField(document, "id", DOCUMENT);
        ReflectionTestUtils.setField(document, "creadoEn", Instant.parse("2026-09-10T10:00:00Z"));
        document.setParties(id(4), null, null);
        document.assignOriginTerminal(id(7));
        document.setDueDate(LocalDate.of(2026, 10, 10));
        document.addLine(new DocumentLine(document, id(8), 1, 2, "CONTRACT-001", "Producto de contrato",
                "PVP", new BigDecimal("1.235"), BigDecimal.ZERO, false, "IGIC", new BigDecimal("7")));
        var cash = new PaymentMethod(COMPANY, "EFECTIVO", true);
        ReflectionTestUtils.setField(cash, "id", id(9));
        document.addPayment(new DocumentPayment(document, cash, 1, new BigDecimal("2.64"), true,
                new BigDecimal("5.00"), new BigDecimal("2.36"), Instant.parse("2026-09-10T10:01:00Z")));
        document.confirm("T-20260910-0001", id(6), Instant.parse("2026-09-10T10:01:00Z"), true);
        var relations = mock(DocumentRelationRepository.class);
        var relation = mock(DocumentRelationRepository.SyncRelation.class);
        when(relation.getType()).thenReturn(DocumentRelationType.COMPENSA);
        when(relation.getOriginId()).thenReturn(id(10));
        when(relation.getOriginStoreId()).thenReturn(STORE);
        when(relations.findOutgoingForSync(DOCUMENT, STORE)).thenReturn(List.of(relation));

        var payload = new DocumentSyncPayloadFactory(relations, org.mockito.Mockito.mock(DocumentAttributionResolver.class)).create(document, 21L);

        assertThat(FIXTURE).isRegularFile();
        var mapper = new ObjectMapper();
        var expected = mapper.readTree(Files.readString(FIXTURE));
        // Compare the actual JSON wire form: Java Long versus Integer is not a JSON contract difference.
        var actual = mapper.readTree(mapper.writeValueAsBytes(payload));
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.at("/lineas/0/precioUnitario").textValue()).isEqualTo("1.235");
        assertThat(actual.get("total").textValue()).isEqualTo("2.64");
        assertThat(actual.at("/pagos/0/importe").textValue()).isEqualTo("2.64");
        assertThat(actual.at("/relaciones/0/origenId").textValue()).isEqualTo(id(10).toString());
        verify(relations).findOutgoingForSync(DOCUMENT, STORE);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
