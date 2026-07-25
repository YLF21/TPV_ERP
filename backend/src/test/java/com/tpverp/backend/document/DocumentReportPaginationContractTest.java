package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

class DocumentReportPaginationContractTest {

    @Test
    void ordersAndFiltersTheUuidTieBreakerUsingTheSameStringRepresentation() throws Exception {
        var firstPageMethod = CommercialDocumentRepository.class.getDeclaredMethod(
                "findReportDocuments",
                UUID.class,
                Collection.class,
                Pageable.class);
        var nextPageMethod = CommercialDocumentRepository.class.getDeclaredMethod(
                "findReportDocumentsAfter",
                UUID.class,
                Collection.class,
                LocalDate.class,
                Instant.class,
                String.class,
                Pageable.class);
        var completeListMethod = CommercialDocumentRepository.class.getDeclaredMethod(
                "findAllByStoreAndTypesOrderByRecency",
                UUID.class,
                Collection.class);

        var firstPageQuery = firstPageMethod.getAnnotation(Query.class).value();
        var nextPageQuery = nextPageMethod.getAnnotation(Query.class).value();
        var completeListQuery = completeListMethod.getAnnotation(Query.class).value();

        assertThat(firstPageQuery)
                .doesNotContain("cursorDate")
                .contains("order by document.fecha desc,")
                .contains("coalesce(document.confirmadoEn, document.creadoEn) desc,")
                .contains("cast(document.id as string) desc");
        assertThat(nextPageQuery)
                .doesNotContain(":cursorDate is null")
                .contains("coalesce(document.confirmadoEn, document.creadoEn) < :cursorOccurredAt")
                .contains("cast(document.id as string) < :cursorId")
                .contains("order by document.fecha desc,")
                .contains("coalesce(document.confirmadoEn, document.creadoEn) desc,")
                .contains("cast(document.id as string) desc");
        assertThat(completeListQuery)
                .contains("order by document.fecha desc,")
                .contains("coalesce(document.confirmadoEn, document.creadoEn) desc,")
                .contains("cast(document.id as string) desc");
    }
}
