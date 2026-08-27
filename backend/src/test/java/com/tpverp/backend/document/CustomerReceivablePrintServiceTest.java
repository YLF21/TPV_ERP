package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.document.template.InvoiceJasperRenderer;
import com.tpverp.backend.document.template.OperationalReceiptJasperRenderer;
import com.tpverp.backend.document.template.DocumentTemplateType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRate;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.FiscalAddress;
import com.tpverp.backend.verifactu.FiscalEndpointEnvironment;
import com.tpverp.backend.verifactu.FiscalMode;
import com.tpverp.backend.verifactu.FiscalPrintSnapshotFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerReceivablePrintServiceTest {

    @Test
    void commercialDocumentSnapshotContainsAuthoritativeIssuerAndCustomerFiscalIdentity()
            throws Exception {
        var address = java.util.Map.of(
                "linea1", "Calle Emisor 1", "codigoPostal", "28001",
                "ciudad", "Madrid", "provincia", "Madrid", "pais", "ES");
        var company = new Company("B12345678", "TPV ERP SL", address);
        var store = new Store(company, "001", "Tienda Centro", address,
                UUID.randomUUID().toString(), "Europe/Madrid", "EUR", "es-ES");
        var customer = new Customer(company, "Cliente Fiscal SL", DocumentType.CIF,
                "B87654321", new FiscalAddress("Avenida Sur 2", "41001", "Sevilla",
                "Sevilla", "ES"), null, null, null, CustomerRate.VENTA,
                BigDecimal.ZERO);
        customer.assignClientCode(store.getId(), "C-001");
        var document = document(store.getId(), customer.getId());
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var customers = mock(CustomerRepository.class);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(documents.findCustomerDocumentForPrint(document.getId(), store.getId()))
                .thenReturn(Optional.of(document));
        when(customers.findByIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(customer));

        var constructor = java.util.Arrays.stream(CustomerReceivablePrintService.class.getConstructors())
                .filter(value -> java.util.Arrays.asList(value.getParameterTypes())
                        .contains(CustomerRepository.class))
                .findFirst();
        assertThat(constructor).as("printing service must resolve the authoritative customer")
                .isPresent();
        var service = (CustomerReceivablePrintService) constructor.orElseThrow()
                .newInstance(documents, payments, organization, customers);

        var printable = service.document(document.getId());
        var issuer = printable.getClass().getMethod("issuer").invoke(printable);
        var printedCustomer = printable.getClass().getMethod("customer").invoke(printable);
        assertThat(issuer.getClass().getMethod("name").invoke(issuer)).isEqualTo("TPV ERP SL");
        assertThat(issuer.getClass().getMethod("taxId").invoke(issuer)).isEqualTo("B12345678");
        assertThat(issuer.getClass().getMethod("address").invoke(issuer).toString())
                .contains("Calle Emisor 1", "28001", "Madrid", "ES");
        assertThat(printedCustomer.getClass().getMethod("name").invoke(printedCustomer))
                .isEqualTo("Cliente Fiscal SL");
        assertThat(printedCustomer.getClass().getMethod("taxId").invoke(printedCustomer))
                .isEqualTo("B87654321");
        assertThat(printedCustomer.getClass().getMethod("address").invoke(printedCustomer).toString())
                .contains("Avenida Sur 2", "41001", "Sevilla", "ES");
    }

    @Test
    void buildsAuthoritativeCommercialDocumentAndSinglePaymentReceiptWithinCurrentStore() {
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var customers = mock(CustomerRepository.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId); when(organization.currentStore()).thenReturn(store);
        var document = document(storeId);
        var company = new Company("B12345678", "TPV ERP SL", java.util.Map.of(
                "linea1", "Calle 1", "codigoPostal", "28001", "ciudad", "Madrid",
                "provincia", "Madrid", "pais", "ES"));
        var customer = mock(Customer.class);
        when(customer.getFiscalName()).thenReturn("Cliente");
        when(customer.getDocumentNumber()).thenReturn("B87654321");
        when(organization.currentCompany()).thenReturn(company);
        when(customers.findByIdAndCompanyId(document.getClienteId(), company.getId()))
                .thenReturn(Optional.of(customer));
        var receiptRenderer = mock(OperationalReceiptJasperRenderer.class);
        var payment = new DocumentPayment(document,
                new PaymentMethod(UUID.randomUUID(), "TRANSFERENCIA", true), 1,
                new BigDecimal("20.00"), true, null, null, null, "TR-1",
                Instant.parse("2026-07-20T09:00:00Z"), null,
                null, null, null, null, UUID.randomUUID(), null,
                LocalDate.of(2026, 7, 19));
        document.addPayment(payment); document.updatePaymentStatus();
        when(documents.findCustomerDocumentForPrint(document.getId(), storeId)).thenReturn(Optional.of(document));
        when(payments.findByRequestId(payment.getRequestId())).thenReturn(Optional.of(payment));
        when(payments.findAllByDocumentoId(document.getId())).thenReturn(List.of(payment));
        when(receiptRenderer.renderPendingCollection(
                document.getId(), payment.getRequestId())).thenReturn(
                new OperationalReceiptJasperRenderer.RenderedReceipt(
                        "%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        new byte[] {4, 5, 6}));
        var service = new CustomerReceivablePrintService(
                documents, payments, organization, customers, null, null, null,
                null, receiptRenderer);

        var printable = service.document(document.getId());
        var receipt = service.paymentReceipt(document.getId(), payment.getRequestId());

        assertThat(printable.total()).isEqualByComparingTo("100.00");
        assertThat(printable.lines()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("Producto");
            assertThat(line.barcode()).isEqualTo("8430000000010");
            assertThat(line.total()).isEqualByComparingTo("100.00");
        });
        assertThat(receipt.paymentId()).isEqualTo(payment.getRequestId());
        assertThat(receipt.amount()).isEqualByComparingTo("20.00");
        assertThat(receipt.remaining()).isEqualByComparingTo("80.00");
        assertThat(receipt.reference()).isEqualTo("TR-1");
        assertThat(receipt.transferDate()).isEqualTo(LocalDate.of(2026, 7, 19));
        assertThat(java.util.Base64.getDecoder().decode(receipt.renderedPdf().base64()))
                .startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F');
        assertThat(java.util.Base64.getDecoder().decode(
                receipt.ticketRenderedImage().base64())).containsExactly(4, 5, 6);
    }

    @Test
    void attachesJasperPdfWhenTheFrozenTemplateRendersIt() {
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var customers = mock(CustomerRepository.class);
        var presentations = mock(InvoicePresentationSnapshotFactory.class);
        var fiscalQr = mock(DocumentFiscalQrService.class);
        var qrImages = mock(com.tpverp.backend.verifactu.FiscalQrImageService.class);
        var renderer = mock(InvoiceJasperRenderer.class);
        var address = java.util.Map.of(
                "linea1", "Calle 1", "codigoPostal", "35001",
                "ciudad", "Las Palmas", "provincia", "Las Palmas", "pais", "ES");
        var company = new Company("B12345678", "TPV ERP SL", address);
        var store = new Store(company, "001", "Tienda", address,
                UUID.randomUUID().toString(), "Atlantic/Canary", "EUR", "es-ES");
        var customer = new Customer(company, "Cliente", DocumentType.CIF,
                "B87654321", new FiscalAddress(
                        "Calle 2", "35002", "Las Palmas", "Las Palmas", "ES"),
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        customer.assignClientCode(store.getId(), "C-1");
        var document = document(store.getId(), customer.getId());
        var presentation = new InvoicePresentationSnapshot(
                2, InvoiceFiscalProfile.IVA, null, List.of());
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(documents.findCustomerDocumentForPrint(document.getId(), store.getId()))
                .thenReturn(Optional.of(document));
        when(customers.findByIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(customer));
        when(presentations.create(DocumentTemplateType.FACTURA_VENTA))
                .thenReturn("snapshot");
        when(presentations.read("snapshot")).thenReturn(presentation);
        var qrUrl = "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR?nif=B12345674";
        var fiscalData = new DocumentFiscalQrService.FiscalQrPrintData(
                qrUrl,
                "A".repeat(64),
                FiscalPrintSnapshotFactory.FORMAT_VERSION,
                "TPV-ERP-2026.08.25",
                FiscalMode.VERIFACTU,
                FiscalEndpointEnvironment.TEST,
                "Prefijo congelado:",
                "Leyenda congelada",
                "Aviso congelado",
                "Obligado congelado SL",
                "B12345674",
                java.util.Map.of(
                        "linea1", "Calle congelada 7",
                        "codigoPostal", "35007",
                        "ciudad", "Telde",
                        "provincia", "Las Palmas",
                        "pais", "ES"));
        when(fiscalQr.resolveForPrint(document.getId())).thenReturn(Optional.of(fiscalData));
        when(qrImages.png(qrUrl, 240)).thenReturn(
                new com.tpverp.backend.verifactu.FiscalQrImage(
                        pngSignature(), "image/png"));
        when(renderer.renderWithFiscalSnapshot(
                document, store, company, customer, presentation, fiscalData.toView(), null,
                com.tpverp.backend.document.template.DocumentTemplateFormat.A4))
                .thenReturn(Optional.of("%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        var service = new CustomerReceivablePrintService(
                documents, payments, organization, customers, presentations,
                fiscalQr, qrImages, renderer);

        var printable = service.document(document.getId());

        assertThat(printable.renderedPdf().contentType()).isEqualTo("application/pdf");
        assertThat(printable.qrUrl()).isEqualTo(qrUrl);
        assertThat(printable.qrImage()).startsWith("data:image/png;base64,");
        assertThat(printable.fiscal()).isEqualTo(fiscalData.toView());
        assertThat(printable.fiscal().prefix()).isEqualTo("Prefijo congelado:");
        assertThat(printable.fiscal().testNotice()).isEqualTo("Aviso congelado");
        assertThat(printable.issuer().name()).isEqualTo("Obligado congelado SL");
        assertThat(printable.issuer().taxId()).isEqualTo("B12345674");
        assertThat(printable.issuer().address().line1()).isEqualTo("Calle congelada 7");
        assertThat(java.util.Base64.getDecoder().decode(printable.renderedPdf().base64()))
                .startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F');
    }

    @Test
    void rectificationCannotRenderA4OrRasterWithoutItsFrozenQrSnapshot() {
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var customers = mock(CustomerRepository.class);
        var presentations = mock(InvoicePresentationSnapshotFactory.class);
        var fiscalQr = mock(DocumentFiscalQrService.class);
        var qrImages = mock(com.tpverp.backend.verifactu.FiscalQrImageService.class);
        var renderer = mock(InvoiceJasperRenderer.class);
        var address = java.util.Map.of(
                "linea1", "Calle 1", "codigoPostal", "35001",
                "ciudad", "Las Palmas", "provincia", "Las Palmas", "pais", "ES");
        var company = new Company("B12345678", "TPV ERP SL", address);
        var store = new Store(company, "001", "Tienda", address,
                UUID.randomUUID().toString(), "Atlantic/Canary", "EUR", "es-ES");
        var customer = new Customer(company, "Cliente", DocumentType.CIF,
                "B87654321", new FiscalAddress(
                        "Calle 2", "35002", "Las Palmas", "Las Palmas", "ES"),
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        customer.assignClientCode(store.getId(), "C-1");
        var document = rectification(store.getId(), customer.getId());
        var presentation = new InvoicePresentationSnapshot(
                2, InvoiceFiscalProfile.IGIC, null, List.of());
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(documents.findCustomerDocumentForPrint(document.getId(), store.getId()))
                .thenReturn(Optional.of(document));
        when(customers.findByIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(customer));
        when(presentations.create(DocumentTemplateType.RECTIFICATIVA_VENTA))
                .thenReturn("rectification-snapshot");
        when(presentations.read("rectification-snapshot")).thenReturn(presentation);
        when(fiscalQr.resolveForPrint(document.getId())).thenThrow(
                new FiscalQrUnavailableException(document.getId(),
                        FiscalQrUnavailableException.Reason.FROZEN_SNAPSHOT_MISSING));
        var service = new CustomerReceivablePrintService(
                documents, payments, organization, customers, presentations,
                fiscalQr, qrImages, renderer);

        assertThatThrownBy(() -> service.document(document.getId()))
                .isInstanceOf(FiscalQrUnavailableException.class)
                .extracting(error -> ((FiscalQrUnavailableException) error).reason())
                .isEqualTo(FiscalQrUnavailableException.Reason.FROZEN_SNAPSHOT_MISSING);
        verifyNoInteractions(qrImages, renderer);
    }

    @Test
    void fiscalA4CannotRenderWhenGeneratedQrIsNotPng() {
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var customers = mock(CustomerRepository.class);
        var fiscalQr = mock(DocumentFiscalQrService.class);
        var qrImages = mock(com.tpverp.backend.verifactu.FiscalQrImageService.class);
        var renderer = mock(InvoiceJasperRenderer.class);
        var store = mock(Store.class);
        var company = new Company("B12345678", "TPV ERP SL", java.util.Map.of(
                "linea1", "Calle 1", "codigoPostal", "35001", "ciudad", "Las Palmas",
                "provincia", "Las Palmas", "pais", "ES"));
        var customer = mock(Customer.class);
        var document = document(UUID.randomUUID());
        when(store.getId()).thenReturn(document.getTiendaId());
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(documents.findCustomerDocumentForPrint(document.getId(), document.getTiendaId()))
                .thenReturn(Optional.of(document));
        when(customers.findByIdAndCompanyId(document.getClienteId(), company.getId()))
                .thenReturn(Optional.of(customer));
        var qrUrl = "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR?nif=B12345674";
        when(fiscalQr.resolveForPrint(document.getId())).thenReturn(Optional.of(
                new DocumentFiscalQrService.FiscalQrPrintData(qrUrl, "A".repeat(64))));
        when(qrImages.png(qrUrl, 240)).thenReturn(
                new com.tpverp.backend.verifactu.FiscalQrImage(
                        "not-png".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "image/png"));
        var service = new CustomerReceivablePrintService(
                documents, payments, organization, customers, null,
                fiscalQr, qrImages, renderer);

        assertThatThrownBy(() -> service.document(document.getId()))
                .isInstanceOf(FiscalQrUnavailableException.class)
                .extracting(error -> ((FiscalQrUnavailableException) error).reason())
                .isEqualTo(FiscalQrUnavailableException.Reason.IMAGE_GENERATION_FAILED);
        verifyNoInteractions(renderer);
    }

    @Test
    void deliveryNoteWithoutCustomerRendersWithoutFiscalQrOrPaymentData() {
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var customers = mock(CustomerRepository.class);
        var presentations = mock(InvoicePresentationSnapshotFactory.class);
        var fiscalQr = mock(DocumentFiscalQrService.class);
        var qrImages = mock(com.tpverp.backend.verifactu.FiscalQrImageService.class);
        var renderer = mock(InvoiceJasperRenderer.class);
        var address = java.util.Map.of(
                "linea1", "Calle 1", "codigoPostal", "35001",
                "ciudad", "Las Palmas", "provincia", "Las Palmas", "pais", "ES");
        var company = new Company("B12345678", "TPV ERP SL", address);
        var store = new Store(company, "001", "Tienda", address,
                UUID.randomUUID().toString(), "Atlantic/Canary", "EUR", "es-ES");
        var document = deliveryNote(store.getId());
        var presentation = new InvoicePresentationSnapshot(
                2, InvoiceFiscalProfile.IGIC, "Entregar por la manana", List.of());
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(documents.findCustomerDocumentForPrint(document.getId(), store.getId()))
                .thenReturn(Optional.of(document));
        when(presentations.create(DocumentTemplateType.ALBARAN_VENTA))
                .thenReturn("delivery-note-snapshot");
        when(presentations.read("delivery-note-snapshot")).thenReturn(presentation);
        when(renderer.render(document, store, company, null, presentation, null))
                .thenReturn(Optional.of(
                        "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        var service = new CustomerReceivablePrintService(
                documents, payments, organization, customers, presentations,
                fiscalQr, qrImages, renderer);

        var printable = service.document(document.getId());

        assertThat(printable.documentType()).isEqualTo(CommercialDocumentType.ALBARAN_VENTA);
        assertThat(printable.customerId()).isNull();
        assertThat(printable.customer()).isNull();
        assertThat(printable.qrUrl()).isNull();
        assertThat(printable.qrImage()).isNull();
        assertThat(printable.renderedPdf()).isNotNull();
        verify(fiscalQr, never()).resolveForPrint(any());
        verify(customers, never()).findByIdAndCompanyId(any(), any());
    }

    @Test
    void receiptKeepsHistoricalRemainingAfterEachPayment() {
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var customers = mock(CustomerRepository.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId); when(organization.currentStore()).thenReturn(store);
        var service = new CustomerReceivablePrintService(documents, payments, organization, customers);
        var document = document(storeId);
        var method = new PaymentMethod(UUID.randomUUID(), "EFECTIVO", true);
        var first = new DocumentPayment(document, method, 1, new BigDecimal("20.00"), true,
                null, null, null, null, Instant.parse("2026-07-20T09:00:00Z"), null,
                null, null, null, null, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        var second = new DocumentPayment(document, method, 2, new BigDecimal("30.00"), false,
                null, null, null, null, Instant.parse("2026-07-20T09:00:00Z"), null,
                null, null, null, null, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        document.addPayment(first); document.addPayment(second); document.updatePaymentStatus();
        when(documents.findCustomerDocumentForPrint(document.getId(), storeId)).thenReturn(Optional.of(document));
        when(payments.findByRequestId(first.getRequestId())).thenReturn(Optional.of(first));
        when(payments.findByRequestId(second.getRequestId())).thenReturn(Optional.of(second));
        when(payments.findAllByDocumentoId(document.getId())).thenReturn(List.of(second, first));

        assertThat(service.paymentReceipt(document.getId(), first.getRequestId()).remaining())
                .isEqualByComparingTo("80.00");
        assertThat(service.paymentReceipt(document.getId(), second.getRequestId()).remaining())
                .isEqualByComparingTo("50.00");
    }

    @Test
    void rejectsPaymentReceiptFromAnotherDocumentOrStore() {
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var customers = mock(CustomerRepository.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId); when(organization.currentStore()).thenReturn(store);
        var service = new CustomerReceivablePrintService(documents, payments, organization, customers);
        var requested = document(storeId); var foreign = document(UUID.randomUUID());
        var requestId = UUID.randomUUID();
        var payment = new DocumentPayment(foreign,
                new PaymentMethod(UUID.randomUUID(), "EFECTIVO", true), 1,
                BigDecimal.TEN, true, null, null, null, null, Instant.now(), null,
                null, null, null, null, requestId);
        when(documents.findCustomerDocumentForPrint(requested.getId(), storeId)).thenReturn(Optional.of(requested));
        when(payments.findByRequestId(requestId)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.paymentReceipt(requested.getId(), requestId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reprintsPersistedPaymentByScopedIdWithoutWritingOrRegisteringAnotherPayment() {
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var customers = mock(CustomerRepository.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        var service = new CustomerReceivablePrintService(
                documents, payments, organization, customers);
        var document = document(storeId);
        var payment = new DocumentPayment(document,
                new PaymentMethod(UUID.randomUUID(), "TRANSFERENCIA", true), 1,
                new BigDecimal("25.00"), true, null, null, null, "TR-HIST-1",
                Instant.parse("2026-07-20T09:00:00Z"), null, null, null,
                null, null, null);
        document.addPayment(payment);
        document.updatePaymentStatus();
        when(documents.findCustomerDocumentForPrint(document.getId(), storeId))
                .thenReturn(Optional.of(document));
        when(payments.findCustomerReceivablePayment(
                document.getId(), payment.getId(), storeId)).thenReturn(Optional.of(payment));
        when(payments.findAllByDocumentoId(document.getId())).thenReturn(List.of(payment));

        var first = service.paymentReceiptByPaymentId(document.getId(), payment.getId());
        var replay = service.paymentReceiptByPaymentId(document.getId(), payment.getId());

        assertThat(replay).isEqualTo(first);
        assertThat(replay.paymentId()).isEqualTo(payment.getId());
        assertThat(replay.amount()).isEqualByComparingTo("25.00");
        assertThat(replay.remaining()).isEqualByComparingTo("75.00");
        verify(payments, org.mockito.Mockito.times(2)).findCustomerReceivablePayment(
                document.getId(), payment.getId(), storeId);
        verify(payments, never()).save(any());
        assertThat(document.getPagos()).hasSize(1);
    }

    private static CommercialDocument document(UUID storeId) {
        return document(storeId, UUID.randomUUID());
    }

    private static CommercialDocument document(UUID storeId, UUID customerId) {
        var document = new CommercialDocument(storeId, UUID.randomUUID(),
                CommercialDocumentType.FACTURA_VENTA, LocalDate.of(2026, 7, 16),
                UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(document, UUID.randomUUID(), 1,
                BigDecimal.ONE, "P1", "8430000000010", "Producto", "VENTA",
                new BigDecimal("100.00"),
                BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        document.setParties(customerId, null, null);
        document.confirm("FV-1", UUID.randomUUID(), Instant.parse("2026-07-16T10:00:00Z"), false);
        return document;
    }

    private static CommercialDocument deliveryNote(UUID storeId) {
        var document = new CommercialDocument(storeId, UUID.randomUUID(),
                CommercialDocumentType.ALBARAN_VENTA, LocalDate.of(2026, 8, 10),
                UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(document, UUID.randomUUID(), 1,
                BigDecimal.ONE, "P1", "8430000000010", "Producto", "VENTA",
                new BigDecimal("100.00"), BigDecimal.ZERO, true,
                "IGIC", new BigDecimal("7.00")));
        document.confirm("AV-1", UUID.randomUUID(),
                Instant.parse("2026-08-10T10:00:00Z"), false);
        return document;
    }

    private static CommercialDocument rectification(UUID storeId, UUID customerId) {
        var document = new CommercialDocument(storeId, UUID.randomUUID(),
                CommercialDocumentType.RECTIFICATIVA_VENTA, LocalDate.of(2026, 8, 10),
                UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(document, UUID.randomUUID(), 1,
                BigDecimal.ONE.negate(), "P1", "8430000000010", "Producto", "VENTA",
                new BigDecimal("100.00"), BigDecimal.ZERO, true,
                "IGIC", new BigDecimal("7.00")));
        document.setParties(customerId, null, null);
        document.confirm("RV-1", UUID.randomUUID(),
                Instant.parse("2026-08-10T10:00:00Z"), false);
        return document;
    }

    private static byte[] pngSignature() {
        return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A};
    }
}
