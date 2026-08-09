package com.tpverp.backend.document;

import java.util.List;

public record InvoicePresentationSnapshot(
        int schemaVersion,
        InvoiceFiscalProfile fiscalProfile,
        String observations,
        List<BankAccount> bankAccounts) {

    public InvoicePresentationSnapshot {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("invoice_print_snapshot_version_invalid");
        }
        bankAccounts = bankAccounts == null ? List.of() : List.copyOf(bankAccounts);
    }

    public record BankAccount(String bankName, String iban) {
    }
}
