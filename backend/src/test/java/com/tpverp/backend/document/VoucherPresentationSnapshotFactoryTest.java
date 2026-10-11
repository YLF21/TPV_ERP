package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.document.template.BuiltInDocumentJrxmlCatalog;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.DocumentTemplateOrigin;
import com.tpverp.backend.document.template.DocumentTemplatePresentationService;
import com.tpverp.backend.document.template.DocumentTemplateResolver;
import com.tpverp.backend.document.template.DocumentTemplateType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreDocumentPrintConfigurationService;
import com.tpverp.backend.terminal.TerminalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VoucherPresentationSnapshotFactoryTest {

    @Test
    void capturesSourceOperatorAndReadsLegacySnapshotsWithoutInventingOne() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var address = Map.of("linea1", "Calle 1", "codigoPostal", "35001",
                "ciudad", "Las Palmas", "provincia", "Las Palmas", "pais", "ES");
        var company = new Company("B12345678", "Empresa", address);
        var store = new Store(company, "001", "Tienda", address,
                "address-hash", "Atlantic/Canary", "EUR", "es");
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        var presentations = mock(StoreDocumentPrintConfigurationService.class);
        when(presentations.presentation(DocumentTemplateType.VALE)).thenReturn(
                new StoreDocumentPrintConfigurationService.Presentation(null, null));
        var presentationService = mock(DocumentTemplatePresentationService.class);
        when(presentationService.origin(DocumentTemplateType.VALE,
                DocumentTemplateFormat.TICKET_80)).thenReturn(DocumentTemplateOrigin.INTEGRATED);
        var builtIns = mock(BuiltInDocumentJrxmlCatalog.class);
        when(builtIns.reference(DocumentTemplateType.VALE,
                DocumentTemplateFormat.TICKET_80)).thenReturn(
                        new InvoicePresentationSnapshot.TemplateReference(
                                UUID.randomUUID(), "VALE_TICKET_80", 2, 1, "a".repeat(64), true));
        var source = mock(CommercialDocument.class);
        var sourceId = UUID.randomUUID();
        when(source.getId()).thenReturn(sourceId);
        when(source.getTiendaId()).thenReturn(store.getId());
        when(source.getNumero()).thenReturn("T-001");
        when(source.getTipo()).thenReturn(CommercialDocumentType.TICKET);
        when(source.getFecha()).thenReturn(LocalDate.of(2026, 10, 11));
        when(source.getTotal()).thenReturn(new BigDecimal("-10.00"));
        var attribution = mock(DocumentAttributionResolver.class);
        when(attribution.resolve(any())).thenReturn(Map.of(sourceId,
                new DocumentAttributionResolver.Attribution(
                        UUID.randomUUID(), "OPERADORA ORIGINAL", null, "", Instant.now())));
        var factory = new VoucherPresentationSnapshotFactory(
                organization, mock(DocumentTemplateResolver.class), presentations,
                mock(CommercialDocumentRepository.class), mock(TerminalRepository.class),
                mapper, presentationService, builtIns);
        factory.setAttributionResolver(attribution);
        var voucher = new Voucher(store.getId(), "V-001", new BigDecimal("10.00"),
                List.of("T-001"), Instant.parse("2026-10-11T10:00:00Z"));

        var saved = factory.read(factory.create(voucher, source, null));
        assertThat(saved.schemaVersion()).isEqualTo(3);
        assertThat(saved.userName()).isEqualTo("OPERADORA ORIGINAL");
        for (int legacyVersion : List.of(1, 2)) {
            ObjectNode legacy = (ObjectNode) mapper.valueToTree(saved);
            legacy.put("schemaVersion", legacyVersion);
            legacy.remove("userName");
            assertThat(factory.read(mapper.writeValueAsString(legacy)).userName()).isNull();
        }
    }
}
