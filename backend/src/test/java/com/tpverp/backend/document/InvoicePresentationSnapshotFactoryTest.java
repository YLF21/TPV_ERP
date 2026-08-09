package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.CommercialProfile;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.InvoiceBankAccount;
import com.tpverp.backend.organization.InvoiceBankAccountRepository;
import com.tpverp.backend.organization.InvoicePrintSettings;
import com.tpverp.backend.organization.InvoicePrintSettingsRepository;
import com.tpverp.backend.organization.Store;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoicePresentationSnapshotFactoryTest {

    @Test
    void freezesRetailIgicObservationsAndEveryActiveBankAccount() {
        var organization = mock(CurrentOrganization.class);
        var licenses = mock(LicenseRepository.class);
        var settings = mock(InvoicePrintSettingsRepository.class);
        var accounts = mock(InvoiceBankAccountRepository.class);
        var store = mock(Store.class);
        var company = mock(Company.class);
        var license = mock(License.class);
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(license.isActiva()).thenReturn(true);
        when(license.getValidaDesde()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(license.getRegimenImpuesto()).thenReturn(TaxRegime.IGIC);
        when(license.getCommercialProfile()).thenReturn(CommercialProfile.MINORISTA);
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId)).thenReturn(List.of(license));
        var printSettings = new InvoicePrintSettings(companyId);
        printSettings.updateObservations("Gracias por su confianza");
        when(settings.findById(companyId)).thenReturn(Optional.of(printSettings));
        var account = new InvoiceBankAccount(
                companyId, "Banco", "ES91 2100 0418 4502 0005 1332", 0);
        when(accounts.findAllByCompanyIdAndActivaTrueOrderByOrdenAscIdAsc(companyId))
                .thenReturn(List.of(account));

        var factory = new InvoicePresentationSnapshotFactory(
                organization, licenses, settings, accounts, new ObjectMapper());
        var snapshot = factory.read(factory.create());

        assertThat(snapshot.fiscalProfile()).isEqualTo(InvoiceFiscalProfile.IGIC_MINORISTA);
        assertThat(snapshot.observations()).isEqualTo("Gracias por su confianza");
        assertThat(snapshot.bankAccounts()).containsExactly(
                new InvoicePresentationSnapshot.BankAccount(
                        "Banco", "ES91 2100 0418 4502 0005 1332"));
    }
}
