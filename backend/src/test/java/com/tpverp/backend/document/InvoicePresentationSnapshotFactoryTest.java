package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.tpverp.backend.organization.StoreDocumentPrintConfigurationService;
import com.tpverp.backend.document.template.BuiltInDocumentJrxmlCatalog;
import com.tpverp.backend.document.template.DocumentTemplateResolver;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.DocumentTemplateOrigin;
import com.tpverp.backend.document.template.DocumentTemplatePresentationService;
import com.tpverp.backend.document.template.SafeJrxmlCompiler;
import com.tpverp.backend.document.template.DocumentTemplateScope;
import com.tpverp.backend.document.template.DocumentTemplateType;
import com.tpverp.backend.document.template.ResolvedDocumentTemplate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoicePresentationSnapshotFactoryTest {

    @Test
    void freezesIndependentIntegratedAndImportedInvoiceFormats() {
        var organization = mock(CurrentOrganization.class);
        var licenses = mock(LicenseRepository.class);
        var settings = mock(InvoicePrintSettingsRepository.class);
        var accounts = mock(InvoiceBankAccountRepository.class);
        var templates = mock(DocumentTemplateResolver.class);
        var presentations = mock(DocumentTemplatePresentationService.class);
        var store = mock(Store.class);
        var company = mock(Company.class);
        var storeId = UUID.randomUUID();
        var companyId = UUID.randomUUID();
        var ticketTemplateId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId)).thenReturn(List.of());
        when(accounts.findAllByCompanyIdAndActivaTrueOrderByOrdenAscIdAsc(companyId))
                .thenReturn(List.of());
        when(presentations.origin(
                DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4))
                .thenReturn(DocumentTemplateOrigin.INTEGRATED);
        when(presentations.origin(
                DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.TICKET_80))
                .thenReturn(DocumentTemplateOrigin.IMPORTED);
        when(templates.resolve(
                DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.TICKET_80)).thenReturn(
                new ResolvedDocumentTemplate(
                        ticketTemplateId,
                        DocumentTemplateType.FACTURA_VENTA,
                        DocumentTemplateFormat.TICKET_80,
                        DocumentTemplateScope.STORE,
                        "FACTURA_TICKET_TIENDA",
                        2,
                        1,
                        ticketTemplateId.toString(),
                        "e".repeat(64),
                        false));

        var factory = new InvoicePresentationSnapshotFactory(
                organization, licenses, settings, accounts, new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(new SafeJrxmlCompiler()));
        factory.setTemplateResolver(templates);
        factory.setTemplatePresentations(presentations);

        var snapshot = factory.read(factory.create());

        assertThat(snapshot.template().builtIn()).isTrue();
        assertThat(snapshot.template().code())
                .isEqualTo("INTEGRATED_FACTURA_VENTA_A4");
        assertThat(snapshot.ticketTemplate()).isEqualTo(
                new InvoicePresentationSnapshot.TemplateReference(
                        ticketTemplateId, "FACTURA_TICKET_TIENDA", 2, 1,
                        "e".repeat(64), false));
        verify(templates, never()).resolve(
                DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4);
    }

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
        var templateId = UUID.randomUUID();
        var templates = mock(DocumentTemplateResolver.class);
        when(templates.resolve(
                DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4)).thenReturn(
                new ResolvedDocumentTemplate(
                        templateId,
                        DocumentTemplateType.FACTURA_VENTA,
                        DocumentTemplateFormat.A4,
                        DocumentTemplateScope.STORE,
                        "FACTURA_TIENDA",
                        3,
                        1,
                        templateId.toString(),
                        "a".repeat(64),
                        false));
        var ticketTemplateId = UUID.randomUUID();
        when(templates.resolve(
                DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.TICKET_80)).thenReturn(
                new ResolvedDocumentTemplate(
                        ticketTemplateId,
                        DocumentTemplateType.FACTURA_VENTA,
                        DocumentTemplateFormat.TICKET_80,
                        DocumentTemplateScope.SYSTEM,
                        "FACTURA_TICKET_80",
                        1,
                        1,
                        ticketTemplateId.toString(),
                        "c".repeat(64),
                        false));

        var factory = new InvoicePresentationSnapshotFactory(
                organization, licenses, settings, accounts, new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(new SafeJrxmlCompiler()));
        factory.setTemplateResolver(templates);
        var snapshot = factory.read(factory.create());

        assertThat(snapshot.schemaVersion()).isEqualTo(5);
        assertThat(snapshot.shouldShowStoreName()).isTrue();
        assertThat(snapshot.fiscalProfile()).isEqualTo(InvoiceFiscalProfile.IGIC_MINORISTA);
        assertThat(snapshot.observations()).isEqualTo("Gracias por su confianza");
        assertThat(snapshot.bankAccounts()).containsExactly(
                new InvoicePresentationSnapshot.BankAccount(
                        "Banco", "ES91 2100 0418 4502 0005 1332"));
        assertThat(snapshot.template()).isEqualTo(
                new InvoicePresentationSnapshot.TemplateReference(
                        templateId, "FACTURA_TIENDA", 3, 1, "a".repeat(64), false));
        assertThat(snapshot.ticketTemplate()).isEqualTo(
                new InvoicePresentationSnapshot.TemplateReference(
                        ticketTemplateId, "FACTURA_TICKET_80", 1, 1,
                        "c".repeat(64), false));
    }

    @Test
    void deliveryNoteFreezesItsOwnTemplateAndNeverIncludesBankAccounts() {
        var organization = mock(CurrentOrganization.class);
        var licenses = mock(LicenseRepository.class);
        var settings = mock(InvoicePrintSettingsRepository.class);
        var accounts = mock(InvoiceBankAccountRepository.class);
        var store = mock(Store.class);
        var company = mock(Company.class);
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(settings.findById(companyId)).thenReturn(Optional.empty());
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId)).thenReturn(List.of());
        var templateId = UUID.randomUUID();
        var templates = mock(DocumentTemplateResolver.class);
        when(templates.resolve(
                DocumentTemplateType.ALBARAN_VENTA, DocumentTemplateFormat.A4)).thenReturn(
                new ResolvedDocumentTemplate(
                        templateId,
                        DocumentTemplateType.ALBARAN_VENTA,
                        DocumentTemplateFormat.A4,
                        DocumentTemplateScope.STORE,
                        "ALBARAN_A4",
                        2,
                        1,
                        templateId.toString(),
                        "b".repeat(64),
                        false));
        var factory = new InvoicePresentationSnapshotFactory(
                organization, licenses, settings, accounts, new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(new SafeJrxmlCompiler()));
        factory.setTemplateResolver(templates);

        var snapshot = factory.read(
                factory.create(DocumentTemplateType.ALBARAN_VENTA));

        assertThat(snapshot.bankAccounts()).isEmpty();
        assertThat(snapshot.template()).isEqualTo(
                new InvoicePresentationSnapshot.TemplateReference(
                        templateId, "ALBARAN_A4", 2, 1, "b".repeat(64), false));
        verify(accounts, never())
                .findAllByCompanyIdAndActivaTrueOrderByOrdenAscIdAsc(companyId);
    }

    @Test
    void freezesCurrentStoreLogoAndTypeSpecificObservations() {
        var organization = mock(CurrentOrganization.class);
        var licenses = mock(LicenseRepository.class);
        var settings = mock(InvoicePrintSettingsRepository.class);
        var accounts = mock(InvoiceBankAccountRepository.class);
        var storeConfiguration = mock(StoreDocumentPrintConfigurationService.class);
        var store = mock(Store.class);
        var company = mock(Company.class);
        var storeId = UUID.randomUUID();
        var companyId = UUID.randomUUID();
        var logoId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId)).thenReturn(List.of());
        when(storeConfiguration.presentation(DocumentTemplateType.TICKET)).thenReturn(
                new StoreDocumentPrintConfigurationService.Presentation(
                        "Gracias por su compra",
                        new StoreDocumentPrintConfigurationService.LogoReference(
                                logoId, "image/png", "c".repeat(64)),
                        false));
        var templateId = UUID.randomUUID();
        var templates = mock(DocumentTemplateResolver.class);
        when(templates.resolve(
                DocumentTemplateType.TICKET, DocumentTemplateFormat.TICKET_80)).thenReturn(
                new ResolvedDocumentTemplate(
                        templateId,
                        DocumentTemplateType.TICKET,
                        DocumentTemplateFormat.TICKET_80,
                        DocumentTemplateScope.STORE,
                        "TICKET_80",
                        1,
                        1,
                        templateId.toString(),
                        "d".repeat(64),
                        false));
        var factory = new InvoicePresentationSnapshotFactory(
                organization, licenses, settings, accounts, new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(new SafeJrxmlCompiler()));
        factory.setTemplateResolver(templates);
        factory.setStorePrintConfiguration(storeConfiguration);

        var snapshot = factory.read(factory.create(DocumentTemplateType.TICKET));

        assertThat(snapshot.observations()).isEqualTo("Gracias por su compra");
        assertThat(snapshot.logo()).isEqualTo(
                new InvoicePresentationSnapshot.LogoReference(
                        logoId, "image/png", "c".repeat(64)));
        assertThat(snapshot.shouldShowStoreName()).isFalse();
        assertThat(snapshot.template().code()).isEqualTo("TICKET_80");
        verify(settings, never()).findById(companyId);
        verify(accounts, never())
                .findAllByCompanyIdAndActivaTrueOrderByOrdenAscIdAsc(companyId);
    }
}
