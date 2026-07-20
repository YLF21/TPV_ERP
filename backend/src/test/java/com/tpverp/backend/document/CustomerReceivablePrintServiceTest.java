package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRate;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.FiscalAddress;
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
        var service = new CustomerReceivablePrintService(documents, payments, organization, customers);
        var payment = new DocumentPayment(document,
                new PaymentMethod(UUID.randomUUID(), "TRANSFERENCIA", true), 1,
                new BigDecimal("20.00"), true, null, null, null, "TR-1",
                Instant.parse("2026-07-20T09:00:00Z"), null,
                null, null, null, null, UUID.randomUUID());
        document.addPayment(payment); document.updatePaymentStatus();
        when(documents.findCustomerDocumentForPrint(document.getId(), storeId)).thenReturn(Optional.of(document));
        when(payments.findByRequestId(payment.getRequestId())).thenReturn(Optional.of(payment));
        when(payments.findAllByDocumentoId(document.getId())).thenReturn(List.of(payment));

        var printable = service.document(document.getId());
        var receipt = service.paymentReceipt(document.getId(), payment.getRequestId());

        assertThat(printable.total()).isEqualByComparingTo("100.00");
        assertThat(printable.lines()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("Producto");
            assertThat(line.total()).isEqualByComparingTo("100.00");
        });
        assertThat(receipt.paymentId()).isEqualTo(payment.getRequestId());
        assertThat(receipt.amount()).isEqualByComparingTo("20.00");
        assertThat(receipt.remaining()).isEqualByComparingTo("80.00");
        assertThat(receipt.reference()).isEqualTo("TR-1");
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

    private static CommercialDocument document(UUID storeId) {
        return document(storeId, UUID.randomUUID());
    }

    private static CommercialDocument document(UUID storeId, UUID customerId) {
        var document = new CommercialDocument(storeId, UUID.randomUUID(),
                CommercialDocumentType.FACTURA_VENTA, LocalDate.of(2026, 7, 16),
                UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(document, UUID.randomUUID(), 1, 1,
                "P1", "Producto", "VENTA", new BigDecimal("100.00"),
                BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        document.setParties(customerId, null, null);
        document.confirm("FV-1", UUID.randomUUID(), Instant.parse("2026-07-16T10:00:00Z"), false);
        return document;
    }
}
