package com.tpverp.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoicePrintConfigurationServiceTest {

    @Test
    void addsAnAccountOnlyInsideTheCurrentCompany() {
        var company = new Company("B00000000", "Empresa", java.util.Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "España"));
        var organization = mock(CurrentOrganization.class);
        var settings = mock(InvoicePrintSettingsRepository.class);
        var accounts = mock(InvoiceBankAccountRepository.class);
        when(organization.currentCompany()).thenReturn(company);
        when(accounts.existsByCompanyIdAndIban(company.getId(), "ES9121000418450200051332"))
                .thenReturn(false);
        when(accounts.countByCompanyId(company.getId())).thenReturn(2L);
        when(accounts.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));

        var service = new InvoicePrintConfigurationService(organization, settings, accounts);
        var saved = service.addAccount("Banco", "ES91 2100 0418 4502 0005 1332");

        assertThat(saved.order()).isEqualTo(2);
        assertThat(saved.displayIban()).isEqualTo("ES91 2100 0418 4502 0005 1332");
        verify(accounts).existsByCompanyIdAndIban(company.getId(), "ES9121000418450200051332");
    }

    @Test
    void refusesADuplicateIbanWithinTheCompany() {
        var company = mock(Company.class);
        var companyId = UUID.randomUUID();
        var organization = mock(CurrentOrganization.class);
        var settings = mock(InvoicePrintSettingsRepository.class);
        var accounts = mock(InvoiceBankAccountRepository.class);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentCompany()).thenReturn(company);
        when(accounts.existsByCompanyIdAndIban(companyId, "ES9121000418450200051332"))
                .thenReturn(true);

        var service = new InvoicePrintConfigurationService(organization, settings, accounts);
        assertThatThrownBy(() -> service.addAccount(
                "Banco", "ES91 2100 0418 4502 0005 1332"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invoice_bank_iban_duplicate");
    }
}
