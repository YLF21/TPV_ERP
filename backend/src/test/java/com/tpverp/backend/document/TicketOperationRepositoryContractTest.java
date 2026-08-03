package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;

class TicketOperationRepositoryContractTest {

    @Test
    void ticketDetailsAreLoadedInSeparateSingleBagQueries() throws Exception {
        var ticketLookup = CommercialDocumentRepository.class.getDeclaredMethod(
                "findByTiendaIdAndTipoAndNumeroIgnoreCase",
                UUID.class, CommercialDocumentType.class, String.class);
        var paymentLookup = CommercialDocumentRepository.class.getDeclaredMethod(
                "findByIdAndTiendaIdWithPayments", UUID.class, UUID.class);
        var serialLookup = CommercialDocumentRepository.class.getDeclaredMethod(
                "loadLineSerialNumbers", UUID.class);

        assertThat(ticketLookup.getAnnotation(EntityGraph.class).attributePaths())
                .containsExactly("lineas");
        assertThat(paymentLookup.getAnnotation(EntityGraph.class).attributePaths())
                .containsExactly("pagos", "pagos.metodoPago")
                .doesNotContain("lineas", "lineas.serialNumbers");
        assertThat(serialLookup.getAnnotation(Query.class).value())
                .contains("left join fetch line.serialNumbers")
                .doesNotContain("line.pagos");
    }

    @Test
    void latestTicketQueriesOnlySelectCandidateIds() throws Exception {
        var cancellable = CommercialDocumentRepository.class.getDeclaredMethod(
                "findLatestCancellableTicketIds",
                UUID.class, UUID.class, Pageable.class);
        var convertible = CommercialDocumentRepository.class.getDeclaredMethod(
                "findLatestConvertibleTicketIds",
                UUID.class, UUID.class, Pageable.class);

        assertThat(cancellable.getAnnotation(EntityGraph.class)).isNull();
        assertThat(convertible.getAnnotation(EntityGraph.class)).isNull();
        assertThat(cancellable.getAnnotation(Query.class).value())
                .contains("select document.id");
        assertThat(convertible.getAnnotation(Query.class).value())
                .contains("select document.id");
    }
}
