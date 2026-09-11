package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.template.CustomerModel347JasperRenderer;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.FiscalAddress;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class CustomerModel347ServiceTest {

    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CustomerModel347Repository reports = mock(CustomerModel347Repository.class);
    private final CustomerModel347JasperRenderer renderer = mock(CustomerModel347JasperRenderer.class);
    private final CustomerModel347Service service = new CustomerModel347Service(organization, customers, reports, renderer);
    private final UUID customerId = UUID.randomUUID();
    private final Company company = new Company("B00123456", "Empresa emisora",
            Map.of("linea1", "Calle Uno 1", "codigoPostal", "35001", "ciudad", "Las Palmas",
                    "provincia", "Las Palmas", "pais", "ES"));

    @Test
    void fillsMissingQuartersAndSumsPersistedSignedTotalsUsingServerIdentity() {
        var customer = authorizedCustomer();
        when(customer.getFiscalAddress()).thenReturn(new FiscalAddress("Calle Dos 2", "35002", "Ciudad", null, "ES"));
        when(reports.quarterTotals(company.getId(), customerId, 2026)).thenReturn(List.of(
                new CustomerModel347Report.Quarter(4, new BigDecimal("-0.05"), 1),
                new CustomerModel347Report.Quarter(1, new BigDecimal("0.10"), 1),
                new CustomerModel347Report.Quarter(3, new BigDecimal("0.20"), 2)));
        when(renderer.render(any(), eq("es"))).thenReturn(new byte[]{1, 2, 3});

        assertThat(service.generate(customerId, 2026, "es", authentication("VENTA"))).containsExactly(1, 2, 3);

        var report = ArgumentCaptor.forClass(CustomerModel347Report.class);
        verify(renderer).render(report.capture(), eq("es"));
        var value = report.getValue();
        assertThat(value.year()).isEqualTo(2026);
        assertThat(value.issuer()).isEqualTo(new CustomerModel347Report.Party("", "B00123456", "Empresa emisora",
                "Calle Uno 1, 35001, Las Palmas, Las Palmas, ES"));
        assertThat(value.customer()).isEqualTo(new CustomerModel347Report.Party("C-001-000001", "00123456A",
                "Cliente 海洋", "Calle Dos 2, 35002, Ciudad, ES"));
        assertThat(value.quarters()).extracting(CustomerModel347Report.Quarter::number).containsExactly(1, 2, 3, 4);
        assertThat(value.quarters()).extracting(CustomerModel347Report.Quarter::documentCount).containsExactly(1L, 0L, 2L, 1L);
        assertThat(value.quarters().get(1).total()).isEqualByComparingTo("0");
        assertThat(value.annualTotal()).isEqualByComparingTo("0.25");
        verify(customers).findByIdAndCompanyId(customerId, company.getId());
        verify(reports).quarterTotals(company.getId(), customerId, 2026);
        verifyNoMoreInteractions(customers, reports);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VENTA", "GESTION_VENTAS", "INVOICES_READ", "ROLE_ADMIN"})
    void supportsEmptyYearsWithoutAnAmountThresholdForInvoiceReaders(String permission) {
        authorizedCustomer();
        when(reports.quarterTotals(company.getId(), customerId, 2025)).thenReturn(List.of());
        service.generate(customerId, 2025, "en", authentication(permission));
        var report = ArgumentCaptor.forClass(CustomerModel347Report.class);
        verify(renderer).render(report.capture(), eq("en"));
        assertThat(report.getValue().annualTotal()).isEqualByComparingTo("0.00");
        assertThat(report.getValue().quarters()).hasSize(4).allSatisfy(quarter -> {
            assertThat(quarter.total()).isEqualByComparingTo("0");
            assertThat(quarter.documentCount()).isZero();
        });
        assertThat(report.getValue().customer().address()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TICKETS_READ", "DELIVERY_NOTES_READ", "CUSTOMERS_READ", "GESTION_ALMACEN"})
    void deniesOtherReadersBeforeAccessingAnyCompanyData(String permission) {
        assertThatThrownBy(() -> service.generate(customerId, 2026, "es", authentication(permission)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(organization, customers, reports, renderer);
    }

    @Test
    void deniesUnauthenticatedCalls() {
        assertThatThrownBy(() -> service.generate(customerId, 2026, "es", null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(organization, customers, reports, renderer);
    }

    @Test
    void rejectsCustomerOutsideTheAuthenticatedCompany() {
        when(organization.currentCompany()).thenReturn(company);
        when(customers.findByIdAndCompanyId(customerId, company.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.generate(customerId, 2026, "es", authentication("VENTA")))
                .isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(reports, renderer);
    }

    @Test
    void rejectsInvalidYearsAndLanguagesBeforeReadingData() {
        var authentication = authentication("VENTA");
        for (int year : new int[]{-1, 0, 9999, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> service.generate(customerId, year, "es", authentication))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String locale : new String[]{null, "", "fr", "ES", "../../"}) {
            assertThatThrownBy(() -> service.generate(customerId, 2026, locale, authentication))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.generate(null, 2026, "es", authentication))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(organization, customers, reports, renderer);
    }

    @Test
    void usesOneReadOnlyDatabaseSnapshot() throws Exception {
        var transaction = CustomerModel347Service.class.getMethod("generate", UUID.class, int.class,
                String.class, Authentication.class).getAnnotation(Transactional.class);
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private Customer authorizedCustomer() {
        var customer = mock(Customer.class);
        when(organization.currentCompany()).thenReturn(company);
        when(customers.findByIdAndCompanyId(customerId, company.getId())).thenReturn(Optional.of(customer));
        when(customer.getClientId()).thenReturn("C-001-000001");
        when(customer.getFiscalName()).thenReturn("Cliente 海洋");
        when(customer.getDocumentNumber()).thenReturn("00123456A");
        return customer;
    }

    private Authentication authentication(String permission) {
        return new UsernamePasswordAuthenticationToken("tester", "unused",
                List.of(new SimpleGrantedAuthority(permission)));
    }
}
