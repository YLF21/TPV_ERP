package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.DocumentTemplateType;
import com.tpverp.backend.document.template.OperationalDocumentJasperRenderer;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreDocumentPrintConfigurationService;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.TerminalType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GiftReceiptPrintAttributionTest {

    @Test
    void reprintUsesPersistedIssuerAndTerminalOfGiftReceipt() {
        var address = Map.of("linea1", "Calle 1", "codigoPostal", "35001",
                "ciudad", "Las Palmas", "provincia", "Las Palmas", "pais", "ES");
        var company = new Company("B12345678", "Empresa", address);
        var store = new Store(company, "001", "Tienda", address,
                "address-hash", "Atlantic/Canary", "EUR", "es");
        var issuer = new UserAccount(store, "ISSUER", "hash", new Role(store, "SELLER"));
        issuer.cambiarUserName("EMPLEADA ORIGINAL");
        var terminal = new Terminal(store, "TPV ORIGINAL", TerminalType.TERMINAL_VENTA, "hash");
        var receipt = new GiftReceipt(store.getId(), UUID.randomUUID(), UUID.randomUUID(),
                "RG-001-261011-00001", issuer.getId(), terminal.getId(),
                Instant.parse("2026-10-11T10:00:00Z"));
        var receipts = mock(GiftReceiptRepository.class);
        when(receipts.findByStoreIdAndCodigoIgnoreCase(store.getId(), receipt.getCode()))
                .thenReturn(Optional.of(receipt));
        var documents = mock(DocumentService.class);
        when(documents.cardRefundLineOptions(receipt.getSourceDocumentId())).thenReturn(List.of());
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        var identities = mock(GiftReceiptPrintIdentityRepository.class);
        when(identities.forSourceTicket(receipt.getSourceDocumentId(), store.getId()))
                .thenReturn(new GiftReceiptPrintIdentityRepository.Identity(
                        "Tienda", "Empresa", null, "B12345678", null, null,
                        null, null, null, null, null));
        var configuration = mock(StoreDocumentPrintConfigurationService.class);
        when(configuration.presentation(DocumentTemplateType.TICKET)).thenReturn(
                new StoreDocumentPrintConfigurationService.Presentation(null, null));
        var users = mock(UserAccountRepository.class);
        when(users.findById(issuer.getId())).thenReturn(Optional.of(issuer));
        var terminals = mock(TerminalRepository.class);
        when(terminals.findByIdAndTiendaId(terminal.getId(), store.getId()))
                .thenReturn(Optional.of(terminal));
        var renderer = mock(OperationalDocumentJasperRenderer.class);
        when(renderer.mapper()).thenReturn(new ObjectMapper());
        var service = new GiftReceiptService(receipts, mock(GiftReceiptLineRepository.class),
                mock(GiftReceiptSequenceRepository.class), documents, organization,
                mock(CurrentTerminal.class), mock(AuditService.class), Clock.systemUTC(),
                identities, configuration);
        service.setPrinting(renderer);
        service.setPrintAttribution(users, terminals);

        service.printDocument(receipt.getCode());

        var payload = ArgumentCaptor.forClass(ObjectNode.class);
        verify(renderer).render(org.mockito.ArgumentMatchers.eq(DocumentTemplateType.TICKET_REGALO),
                org.mockito.ArgumentMatchers.eq(DocumentTemplateFormat.TICKET_80),
                payload.capture(), any());
        assertThat(payload.getValue().path("userName").asText()).isEqualTo("EMPLEADA ORIGINAL");
        assertThat(payload.getValue().path("terminalName").asText()).isEqualTo("TPV ORIGINAL");
        verify(organization, never()).currentUser(any());
    }
}
