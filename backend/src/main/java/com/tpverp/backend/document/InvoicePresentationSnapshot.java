package com.tpverp.backend.document;

import java.util.List;
import java.util.UUID;

public record InvoicePresentationSnapshot(
        int schemaVersion,
        InvoiceFiscalProfile fiscalProfile,
        String observations,
        List<BankAccount> bankAccounts,
        TemplateReference template,
        TemplateReference ticketTemplate,
        LogoReference logo,
        Boolean showStoreName) {

    public InvoicePresentationSnapshot {
        if (schemaVersion < 1 || schemaVersion > 5) {
            throw new IllegalArgumentException("invoice_print_snapshot_version_invalid");
        }
        bankAccounts = bankAccounts == null ? List.of() : List.copyOf(bankAccounts);
    }

    public InvoicePresentationSnapshot(
            int schemaVersion,
            InvoiceFiscalProfile fiscalProfile,
            String observations,
            List<BankAccount> bankAccounts,
            TemplateReference template,
            TemplateReference ticketTemplate,
            LogoReference logo) {
        this(schemaVersion, fiscalProfile, observations, bankAccounts,
                template, ticketTemplate, logo, Boolean.TRUE);
    }

    public InvoicePresentationSnapshot(
            int schemaVersion,
            InvoiceFiscalProfile fiscalProfile,
            String observations,
            List<BankAccount> bankAccounts) {
        this(schemaVersion, fiscalProfile, observations, bankAccounts, null, null, null);
    }

    public InvoicePresentationSnapshot(
            int schemaVersion,
            InvoiceFiscalProfile fiscalProfile,
            String observations,
            List<BankAccount> bankAccounts,
            TemplateReference template) {
        this(schemaVersion, fiscalProfile, observations, bankAccounts, template, null, null);
    }

    public InvoicePresentationSnapshot(
            int schemaVersion,
            InvoiceFiscalProfile fiscalProfile,
            String observations,
            List<BankAccount> bankAccounts,
            TemplateReference template,
            LogoReference logo) {
        this(schemaVersion, fiscalProfile, observations, bankAccounts, template, null, logo);
    }

    public boolean shouldShowStoreName() {
        return showStoreName == null || showStoreName;
    }

    public record BankAccount(String bankName, String iban) {
    }

    public record TemplateReference(
            UUID id,
            String code,
            int version,
            int dataSchemaVersion,
            String sha256,
            boolean builtIn) {

        public TemplateReference {
            if (code == null || code.isBlank() || version <= 0 || dataSchemaVersion <= 0) {
                throw new IllegalArgumentException("invoice_print_template_reference_invalid");
            }
            if (!builtIn && id == null) {
                throw new IllegalArgumentException("invoice_print_template_reference_invalid");
            }
            if (sha256 != null && !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invoice_print_template_reference_invalid");
            }
        }
    }

    public record LogoReference(UUID id, String contentType, String sha256) {
        public LogoReference {
            if (id == null
                    || (!"image/png".equals(contentType)
                            && !"image/jpeg".equals(contentType))
                    || sha256 == null
                    || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("document_print_logo_reference_invalid");
            }
        }
    }
}
