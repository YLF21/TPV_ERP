package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.organization.StoreDocumentPrintConfigurationService;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PrintRenderingFailureTest {
    @Test void actualTicketAndReceiptDatabaseFailuresAreMarkedWhileValidationKeepsItsExistingType() throws Exception {
        var database = mock(DataSource.class);
        var failure = new SQLException("synthetic database unavailable");
        when(database.getConnection()).thenThrow(failure);
        var ticket = new TicketJasperRenderer(database, mock(DocumentTemplateResolver.class),
                mock(DocumentTemplateArtifactStorage.class), mock(StoreDocumentPrintConfigurationService.class),
                mock(BuiltInTicketJasperBundle.class));
        var document = mock(CommercialDocument.class);
        when(document.getTipo()).thenReturn(CommercialDocumentType.TICKET);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getTiendaId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> ticket.render(document, TicketJasperRenderer.Template.PRINCIPAL))
                .isInstanceOf(PrintRenderingException.class).isInstanceOf(IllegalStateException.class).hasCause(failure);
        var receipt = new OperationalReceiptJasperRenderer(database);
        assertThatThrownBy(() -> receipt.renderPendingCollection(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(PrintRenderingException.class).hasCause(failure);
        when(document.getTipo()).thenReturn(CommercialDocumentType.FACTURA_VENTA);
        assertThatThrownBy(() -> ticket.render(document, TicketJasperRenderer.Template.PRINCIPAL))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }
}
