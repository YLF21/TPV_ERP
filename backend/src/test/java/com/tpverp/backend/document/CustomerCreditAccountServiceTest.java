package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class CustomerCreditAccountServiceTest {

    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CommercialDocumentRepository documents = mock(CommercialDocumentRepository.class);
    private final Authentication authentication = mock(Authentication.class);
    private final Company company = mock(Company.class);
    private final Store store = mock(Store.class);
    private final UUID companyId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private CustomerCreditAccountService service;

    @BeforeEach
    void setUp() {
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Europe/Madrid");
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        service = new CustomerCreditAccountService(
                organization, customers, documents,
                Clock.fixed(Instant.parse("2026-07-21T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void exposesCompanyCreditAndCurrentStoreStatementWithoutLeakingOtherStores() {
        var customerId = UUID.randomUUID();
        var customer = customer(customerId);
        var document = mock(CommercialDocument.class);
        var payment = mock(DocumentPayment.class);
        var method = mock(PaymentMethod.class);
        when(customers.findByIdAndCompanyId(customerId, companyId))
                .thenReturn(Optional.of(customer));
        when(customers.outstandingDebt(customerId)).thenReturn(new BigDecimal("70.00"));
        when(customers.overdueDebt(customerId, LocalDate.of(2026, 7, 21)))
                .thenReturn(new BigDecimal("20.00"));
        when(documents.findCustomerAccountDocuments(storeId, customerId)).thenReturn(List.of(document));
        when(document.getClienteId()).thenReturn(customerId);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getNumero()).thenReturn("001-0001");
        when(document.getTipo()).thenReturn(CommercialDocumentType.FACTURA_VENTA);
        when(document.getFecha()).thenReturn(LocalDate.of(2026, 7, 1));
        when(document.getConfirmadoEn()).thenReturn(Instant.parse("2026-07-01T08:00:00Z"));
        when(document.getDueDate()).thenReturn(LocalDate.of(2026, 7, 15));
        when(document.getTotal()).thenReturn(new BigDecimal("50.00"));
        when(document.getPendingTotal()).thenReturn(new BigDecimal("30.00"));
        when(document.getPagos()).thenReturn(List.of(payment));
        when(payment.getId()).thenReturn(UUID.randomUUID());
        when(payment.getImporte()).thenReturn(new BigDecimal("20.00"));
        when(payment.getCreadoEn()).thenReturn(Instant.parse("2026-07-02T08:00:00Z"));
        when(payment.getMetodoPago()).thenReturn(method);
        when(method.getNombre()).thenReturn("Efectivo");

        var result = service.account(customerId, authentication);

        assertThat(result.outstandingDebt()).isEqualByComparingTo("70.00");
        assertThat(result.storeOutstandingDebt()).isEqualByComparingTo("30.00");
        assertThat(result.overdueDebt()).isEqualByComparingTo("20.00");
        assertThat(result.availableCredit()).isEqualByComparingTo("30.00");
        assertThat(result.openDocumentCount()).isOne();
        assertThat(result.overdueDocumentCount()).isOne();
        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries()).extracting(CustomerCreditAccountService.AccountEntry::kind)
                .containsExactly(CustomerCreditAccountService.EntryKind.PAYMENT,
                        CustomerCreditAccountService.EntryKind.SALE);
        assertThat(result.entries().getFirst().balance()).isEqualByComparingTo("30.00");
        verify(documents).findCustomerAccountDocuments(storeId, customerId);
    }

    @Test
    void rejectsCustomerOutsideCurrentCompanyBeforeReadingStoreDocuments() {
        var customerId = UUID.randomUUID();
        when(customers.findByIdAndCompanyId(customerId, companyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.account(customerId, authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("customer_credit_account_not_found");
        org.mockito.Mockito.verifyNoInteractions(documents);
    }

    private Customer customer(UUID customerId) {
        var customer = mock(Customer.class);
        when(customer.getId()).thenReturn(customerId);
        when(customer.getClientId()).thenReturn("C-001");
        when(customer.getFiscalName()).thenReturn("CLIENTE");
        when(customer.isCreditEnabled()).thenReturn(true);
        when(customer.getCreditLimit()).thenReturn(new BigDecimal("100.00"));
        when(customer.getPaymentTermDays()).thenReturn(30);
        return customer;
    }
}
