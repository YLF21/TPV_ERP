package com.tpverp.backend.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.CommercialProfile;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.InvoiceBankAccountRepository;
import com.tpverp.backend.organization.InvoicePrintSettingsRepository;
import java.util.Comparator;
import org.springframework.stereotype.Component;

@Component
public class InvoicePresentationSnapshotFactory {

    private final CurrentOrganization organization;
    private final LicenseRepository licenses;
    private final InvoicePrintSettingsRepository settings;
    private final InvoiceBankAccountRepository bankAccounts;
    private final ObjectMapper mapper;

    public InvoicePresentationSnapshotFactory(CurrentOrganization organization,
            LicenseRepository licenses, InvoicePrintSettingsRepository settings,
            InvoiceBankAccountRepository bankAccounts, ObjectMapper mapper) {
        this.organization = organization;
        this.licenses = licenses;
        this.settings = settings;
        this.bankAccounts = bankAccounts;
        this.mapper = mapper;
    }

    public String create() {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var license = licenses.findByTiendaIdOrderByValidaDesdeDesc(store.getId()).stream()
                .filter(com.tpverp.backend.licensing.License::isActiva)
                .max(Comparator.comparing(com.tpverp.backend.licensing.License::getValidaDesde))
                .orElse(null);
        InvoiceFiscalProfile profile = license == null
                ? InvoiceFiscalProfile.IVA
                : profile(license.getRegimenImpuesto(), license.getCommercialProfile());
        String observations = settings.findById(company.getId())
                .map(com.tpverp.backend.organization.InvoicePrintSettings::getObservaciones)
                .orElse(null);
        var accounts = bankAccounts
                .findAllByCompanyIdAndActivaTrueOrderByOrdenAscIdAsc(company.getId()).stream()
                .map(account -> new InvoicePresentationSnapshot.BankAccount(
                        account.getBankName(), formatIban(account.getIban())))
                .toList();
        try {
            return mapper.writeValueAsString(
                    new InvoicePresentationSnapshot(1, profile, observations, accounts));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("invoice_print_snapshot_serialization_failed", error);
        }
    }

    public InvoicePresentationSnapshot read(String value) {
        try {
            var snapshot = mapper.readValue(value, InvoicePresentationSnapshot.class);
            if (snapshot.schemaVersion() != 1) {
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
}
