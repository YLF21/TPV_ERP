package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;

class PreviousTicketImportContractTest {

    @Test
    void exposesTerminalScopedImportPreviewToSaleOperators() throws Exception {
        var method = TicketController.class.getDeclaredMethod(
                "previousCurrentTerminalImportPreview", Authentication.class);

        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/previous-current-terminal/import-preview");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("ADMIN", "VENTA", "TICKETS_CREATE")
                .doesNotContain("GESTION_VENTAS");
    }

    @Test
    void previousTicketQueryFindsTheLatestStructurallyImportableTerminalTicket()
            throws Exception {
        var method = CommercialDocumentRepository.class.getDeclaredMethod(
                "findLatestPositiveConfirmedTicketIds",
                UUID.class, UUID.class, Pageable.class);
        var query = method.getAnnotation(Query.class).value();

        assertThat(method.getAnnotation(EntityGraph.class)).isNull();
        assertThat(query)
                .contains("select document.id")
                .contains("document.tiendaId = :storeId")
                .contains("document.terminalOrigenId = :terminalId")
                .contains("CommercialDocumentType.TICKET")
                .contains("DocumentStatus.CONFIRMADO")
                .contains("DocumentStatus.ANULADO")
                .contains("document.total > 0")
                .contains("from DocumentLine productLine")
                .contains("DocumentLineType.PRODUCT")
                .contains("DocumentLineType.RETURN_ADJUSTMENT")
                .contains("invalidLine.originalDocumentLineId is not null")
                .contains("invalidLine.cantidad <= 0")
                .contains("from DocumentRelation relation")
                .contains("DocumentRelationType.COMPENSA")
                .contains("coalesce(document.confirmadoEn, document.creadoEn) desc")
                .contains("document.id desc")
                .doesNotContain("FACTURA_DE", "RECTIFICA");
    }
}
