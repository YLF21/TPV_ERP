package com.tpverp.backend.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.CommercialProfile;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.document.template.DocumentTemplateResolver;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.DocumentTemplateType;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.InvoiceBankAccountRepository;
import com.tpverp.backend.organization.InvoicePrintSettingsRepository;
import com.tpverp.backend.organization.StoreDocumentPrintConfigurationService;
import java.util.Comparator;
import org.springframework.stereotype.Component;

@Component
public class InvoicePresentationSnapshotFactory {

    private final CurrentOrganization organization;
    private final LicenseRepository licenses;
    private final InvoicePrintSettingsRepository settings;
    private final InvoiceBankAccountRepository bankAccounts;
    private final ObjectMapper mapper;
    private DocumentTemplateResolver templates;
    private StoreDocumentPrintConfigurationService storePrintConfiguration;

    public InvoicePresentationSnapshotFactory(CurrentOrganization organization,
            LicenseRepository licenses, InvoicePrintSettingsRepository settings,
            InvoiceBankAccountRepository bankAccounts, ObjectMapper mapper) {
        this.organization = organization;
        this.licenses = licenses;
        this.settings = settings;
        this.bankAccounts = bankAccounts;
        this.mapper = mapper;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setTemplateResolver(DocumentTemplateResolver templates) {
        this.templates = templates;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setStorePrintConfiguration(
            StoreDocumentPrintConfigurationService storePrintConfiguration) {
        this.storePrintConfiguration = storePrintConfiguration;
    }

    public String create() {
        return create(DocumentTemplateType.FACTURA_VENTA);
    }

    public String create(DocumentTemplateType templateType) {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var license = licenses.findByTiendaIdOrderByValidaDesdeDesc(store.getId()).stream()
                .filter(com.tpverp.backend.licensing.License::isActiva)
                .max(Comparator.comparing(com.tpverp.backend.licensing.License::getValidaDesde))
                .orElse(null);
        InvoiceFiscalProfile profile = license == null
                ? InvoiceFiscalProfile.IVA
                : profile(license.getRegimenImpuesto(), license.getCommercialProfile());
        var storePresentation = storePrintConfiguration == null ? null
                : storePrintConfiguration.presentation(templateType);
        String observations = storePresentation == null
                ? settings.findById(company.getId())
                        .map(com.tpverp.backend.organization.InvoicePrintSettings::getObservaciones)
                        .orElse(null)
                : storePresentation.observations();
        var accounts = templateType == DocumentTemplateType.FACTURA_VENTA
                ? bankAccounts
                        .findAllByCompanyIdAndActivaTrueOrderByOrdenAscIdAsc(company.getId()).stream()
                        .map(account -> new InvoicePresentationSnapshot.BankAccount(
                                account.getBankName(), formatIban(account.getIban())))
                        .toList()
                : java.util.List.<InvoicePresentationSnapshot.BankAccount>of();
        var template = templateReference(templateType, DocumentTemplateFormat.defaultFor(templateType));
        var ticketTemplate = templateType == DocumentTemplateType.FACTURA_VENTA
                ? templateReference(templateType, DocumentTemplateFormat.TICKET_80)
                : null;
        var logo = storePresentation == null || storePresentation.logo() == null ? null
                : new InvoicePresentationSnapshot.LogoReference(
                        storePresentation.logo().id(),
                        storePresentation.logo().contentType(),
                        storePresentation.logo().sha256());
        try {
            return mapper.writeValueAsString(
                    new InvoicePresentationSnapshot(
                            4, profile, observations, accounts, template, ticketTemplate, logo));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("invoice_print_snapshot_serialization_failed", error);
        }
    }

    public InvoicePresentationSnapshot read(String value) {
        try {
            var snapshot = mapper.readValue(value, InvoicePresentationSnapshot.class);
            if (snapshot.schemaVersion() < 1 || snapshot.schemaVersion() > 4) {
                throw new IllegalStateException("invoice_print_snapshot_version_invalid");
            }
            return snapshot;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalStateException("invoice_print_snapshot_invalid", error);
        }
    }

    private static InvoiceFiscalProfile profile(
            TaxRegime taxRegime, CommercialProfile commercialProfile) {
        if (taxRegime == TaxRegime.IVA) return InvoiceFiscalProfile.IVA;
        return commercialProfile == CommercialProfile.MINORISTA
                ? InvoiceFiscalProfile.IGIC_MINORISTA
                : InvoiceFiscalProfile.IGIC;
    }

    private static String formatIban(String iban) {
        return iban.replaceAll("(.{4})(?!$)", "$1 ");
    }

    public String logoDataUri(InvoicePresentationSnapshot snapshot, java.util.UUID storeId) {
        if (snapshot == null || snapshot.logo() == null || storePrintConfiguration == null) {
            return null;
        }
        var logo = snapshot.logo();
        return storePrintConfiguration.logoDataUri(storeId,
                new StoreDocumentPrintConfigurationService.LogoReference(
                        logo.id(), logo.contentType(), logo.sha256()));
    }

    private InvoicePresentationSnapshot.TemplateReference templateReference(
            DocumentTemplateType templateType,
            DocumentTemplateFormat format) {
        if (templates == null) {
            var builtInCode = switch (templateType) {
                case FACTURA_VENTA -> format == DocumentTemplateFormat.TICKET_80
                        ? "FACTURA_TICKET_80" : "FACTURA_A4";
                case ALBARAN_VENTA -> "ALBARAN_A4";
                case TICKET -> "TICKET_80";
            };
            return new InvoicePresentationSnapshot.TemplateReference(
                    null,
                    builtInCode,
                    1, 1, null, true);
        }
        var resolved = templates.resolve(templateType, format);
        return new InvoicePresentationSnapshot.TemplateReference(
                resolved.id(),
                resolved.code(),
                resolved.version(),
                resolved.schemaVersion(),
                resolved.sha256(),
                resolved.builtIn());
    }
}
