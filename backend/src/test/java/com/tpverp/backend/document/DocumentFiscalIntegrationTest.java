package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.verifactu.FiscalDocumentType;
import com.tpverp.backend.verifactu.FiscalRecordCommand;
import com.tpverp.backend.verifactu.FiscalRecordOperation;
import com.tpverp.backend.verifactu.FiscalRecordService;
import com.tpverp.backend.verifactu.FiscalRecordRepository;
import com.tpverp.backend.verifactu.VerifactuInactiveException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class DocumentFiscalIntegrationTest {

    @Mock
    private FiscalRecordService fiscalRecords;
    @Mock
    private FiscalRecordRepository recordRepository;
    @Mock
    private CurrentOrganization organization;
    @Mock
    private InstallationRepository installations;
    @Mock
    private ApplicationEventPublisher events;

    private DocumentFiscalIntegration integration;
    private Store store;
    private Installation installation;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        store = new Store(
                new Company("B00000000", "Company", address),
                "001", "Store", address, "hash",
                "Atlantic/Canary", "EUR", "es-ES");
        installation = new Installation("INSTALL", "public", Instant.parse("2026-01-01T00:00:00Z"));
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(organization.currentCompany()).thenReturn(store.getEmpresa());
        lenient().when(installations.findAll()).thenReturn(List.of(installation));
        var fiscalRecord = org.mockito.Mockito.mock(
                com.tpverp.backend.verifactu.FiscalRecord.class);
        lenient().when(fiscalRecord.getId()).thenReturn(UUID.randomUUID());
        lenient().when(fiscalRecords.register(any())).thenReturn(fiscalRecord);
        lenient().when(fiscalRecords.registerSubstitution(any(), any()))
                .thenReturn(fiscalRecord);
        integration = new DocumentFiscalIntegration(
                fiscalRecords, recordRepository, organization, installations, events);
    }

    @Test
    void classifiesTicketInvoiceAndCreditNote() {
        integration.registerAlta(confirmed(CommercialDocumentType.TICKET, BigDecimal.TEN), false);
        integration.registerAlta(confirmed(CommercialDocumentType.TICKET, BigDecimal.ONE.negate()), false);
        integration.registerAlta(confirmed(CommercialDocumentType.FACTURA_VENTA, BigDecimal.TEN), false);
        integration.registerAlta(confirmed(CommercialDocumentType.FACTURA_VENTA, BigDecimal.TEN), true);
        integration.registerAlta(confirmed(CommercialDocumentType.RECTIFICATIVA_VENTA, BigDecimal.TEN), false);

        var commands = ArgumentCaptor.forClass(FiscalRecordCommand.class);
        verify(fiscalRecords, org.mockito.Mockito.times(5)).register(commands.capture());

        assertThat(commands.getAllValues())
                .extracting(FiscalRecordCommand::documentType)
                .containsExactly(
                        FiscalDocumentType.F2,
                        FiscalDocumentType.R5,
                        FiscalDocumentType.F1,
                        FiscalDocumentType.F3,
                        FiscalDocumentType.R1);
    }

    @Test
    void inactiveVerifactuDoesNotBlockDocumentFlow() {
        when(fiscalRecords.register(any())).thenThrow(new VerifactuInactiveException());

        integration.registerAlta(confirmed(CommercialDocumentType.TICKET, BigDecimal.TEN), false);

        verify(fiscalRecords).register(any());
    }

    @Test
    void publishesImmediateSubmissionEventForCreatedFiscalRecord() {
        var record = org.mockito.Mockito.mock(com.tpverp.backend.verifactu.FiscalRecord.class);
        var recordId = UUID.randomUUID();
        when(record.getId()).thenReturn(recordId);
        when(fiscalRecords.register(any())).thenReturn(record);

        integration.registerAlta(confirmed(CommercialDocumentType.TICKET, BigDecimal.TEN), false);

        verify(events).publishEvent(
                new com.tpverp.backend.verifactu.FiscalRecordQueuedEvent(recordId));
    }

    @Test
    void ignoresNonSalesFiscalDocuments() {
        integration.registerAlta(confirmed(CommercialDocumentType.FACTURA_COMPRA, BigDecimal.TEN), false);

        verify(fiscalRecords, never()).register(any());
    }

    @Test
    void registersTicketCancellationWithOriginalFiscalType() {
        integration.registerTicketCancellation(confirmed(CommercialDocumentType.TICKET, BigDecimal.ONE.negate()));

        var command = ArgumentCaptor.forClass(FiscalRecordCommand.class);
        verify(fiscalRecords).register(command.capture());
        assertThat(command.getValue().operation()).isEqualTo(FiscalRecordOperation.ANULACION);
        assertThat(command.getValue().documentType()).isEqualTo(FiscalDocumentType.R5);
    }

    @Test
    void registersInvoiceFromTicketAsF3Substitution() {
        var ticket = confirmed(CommercialDocumentType.TICKET, BigDecimal.TEN);
        var invoice = confirmed(CommercialDocumentType.FACTURA_VENTA, BigDecimal.TEN);

        integration.registerInvoiceFromTicket(invoice, ticket);

        var command = ArgumentCaptor.forClass(FiscalRecordCommand.class);
        verify(fiscalRecords).registerSubstitution(command.capture(), org.mockito.Mockito.eq(ticket.getId()));
        assertThat(command.getValue().documentType()).isEqualTo(FiscalDocumentType.F3);
    }

    private CommercialDocument confirmed(CommercialDocumentType type, BigDecimal amount) {
        var document = new CommercialDocument(
                store.getId(), UUID.randomUUID(), type, LocalDate.of(2026, 6, 8),
                UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLineCommand(
                UUID.randomUUID(), amount.signum() < 0 ? -1 : 1,
                "P-1", "Producto", "VENTA", amount.abs(), BigDecimal.ZERO,
                true, "IVA", BigDecimal.ZERO).toEntity(document));
        document.confirm("NUM", UUID.randomUUID(), Instant.parse("2026-06-08T12:00:00Z"), false);
        return document;
    }
}
