package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentViewTest {

    @Test
    void exposesFinancialFieldsAndOverdueState() {
        var document = documentWithPayment(
                "100.00", "30.00", LocalDate.of(2026, 7, 1));

        var view = CustomerReceivableView.from(
                document, "CLIENTE DEMO", LocalDate.of(2026, 7, 16));

        assertThat(view.paidTotal()).isEqualByComparingTo("30.00");
        assertThat(view.pendingTotal()).isEqualByComparingTo("70.00");
        assertThat(view.overdue()).isTrue();
    }

    @Test
    void receivableProjectionRejectsNonReceivableDocumentTypes() {
        var ticket = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 16), UUID.randomUUID(), BigDecimal.ZERO);

        assertThatThrownBy(() -> CustomerReceivableView.from(
                ticket, "CLIENTE DEMO", LocalDate.of(2026, 7, 16)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assemblerResolvesCustomerInsideAuthenticatedCompany() {
        var companyId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var document = documentWithPayment(
                "100.00", "30.00", LocalDate.of(2026, 7, 1));
        document.setParties(customerId, null, null);
        var customers = mock(CustomerRepository.class);
        var organization = mock(CurrentOrganization.class);
        var company = mock(Company.class);
        var customer = mock(Customer.class);
        when(organization.currentCompany()).thenReturn(company);
        when(company.getId()).thenReturn(companyId);
        when(customers.findByIdAndCompanyId(customerId, companyId))
                .thenReturn(Optional.of(customer));
        when(customer.getFiscalName()).thenReturn("CLIENTE AUTORIZADO");

        var view = new DocumentViewAssembler(customers, organization)
                .documentView(document);

        assertThat(view.customerId()).isEqualTo(customerId);
        assertThat(view.customerName()).isEqualTo("CLIENTE AUTORIZADO");
        assertThat(view.dueDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(view.paidTotal()).isEqualByComparingTo("30.00");
        assertThat(view.pendingTotal()).isEqualByComparingTo("70.00");
        verify(customers).findByIdAndCompanyId(customerId, companyId);
    }

    @Test
    void includesPaymentDetailsWithVoucherCode() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 6, 17), UUID.randomUUID(), BigDecimal.ZERO);
        var method = new PaymentMethod(UUID.randomUUID(), "VALE", true);
        document.addPayment(new DocumentPayment(
                document, method, 1, new BigDecimal("10.00"), true,
                null, null, "vabc123", Instant.parse("2026-06-17T12:00:00Z")));

        var view = DocumentView.from(document);

        assertThat(view.payments()).hasSize(1);
        assertThat(view.payments().getFirst().methodName()).isEqualTo("VALE");
        assertThat(view.payments().getFirst().voucherCode()).isEqualTo("VABC123");
    }

    @Test
    void includesCardTerminalMetadataWithoutSensitiveCardData() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 4), UUID.randomUUID(), BigDecimal.ZERO);
        var method = new PaymentMethod(UUID.randomUUID(), "TARJETA", true);
        document.addPayment(new DocumentPayment(
                document, method, 1, new BigDecimal("25.00"), true,
                null, null, null, "AUTH-1", Instant.parse("2026-07-04T12:00:00Z"),
                com.tpverp.backend.terminal.PaymentCardMode.INTEGRATED,
                com.tpverp.backend.terminal.PaymentTerminalProvider.REDSYS_TPV_PC,
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.APPROVED,
                "A1B2C3", UUID.randomUUID()));

        var view = DocumentView.from(document);

        assertThat(view.payments().getFirst().cardMode()).isEqualTo("INTEGRATED");
        assertThat(view.payments().getFirst().paymentTerminalProvider()).isEqualTo("REDSYS_TPV_PC");
        assertThat(view.payments().getFirst().paymentTerminalStatus()).isEqualTo("APPROVED");
        assertThat(view.payments().getFirst().cardAuthorizationCode()).isEqualTo("A1B2C3");
    }

    private static CommercialDocument documentWithPayment(
            String total, String paid, LocalDate dueDate) {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 6, 17), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, 1, "P-1", "Producto", "VENTA",
                new BigDecimal(total), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        document.setDueDate(dueDate);
        var method = new PaymentMethod(UUID.randomUUID(), "EFECTIVO", true);
        document.addPayment(new DocumentPayment(
                document, method, 1, new BigDecimal(paid), true,
                null, null, null, Instant.parse("2026-06-17T12:00:00Z")));
        return document;
    }
}
