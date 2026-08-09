package com.tpverp.backend.organization;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvoicePrintConfigurationService {

    private final CurrentOrganization organization;
    private final InvoicePrintSettingsRepository settings;
    private final InvoiceBankAccountRepository accounts;

    public InvoicePrintConfigurationService(CurrentOrganization organization,
            InvoicePrintSettingsRepository settings, InvoiceBankAccountRepository accounts) {
        this.organization = organization;
        this.settings = settings;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public Configuration configuration() {
        UUID companyId = organization.currentCompany().getId();
        String observations = settings.findById(companyId)
                .map(InvoicePrintSettings::getObservaciones).orElse(null);
        return new Configuration(observations, accounts(companyId, false));
    }

    @Transactional
    public Configuration updateObservations(String observations) {
        UUID companyId = organization.currentCompany().getId();
        InvoicePrintSettings value = settings.findById(companyId)
                .orElseGet(() -> new InvoicePrintSettings(companyId));
        value.updateObservations(observations);
        settings.save(value);
        return new Configuration(value.getObservaciones(), accounts(companyId, false));
    }

    @Transactional
    public BankAccount addAccount(String bankName, String iban) {
        UUID companyId = organization.currentCompany().getId();
        String normalizedIban = InvoiceBankAccount.normalizeAndValidateIban(iban);
        if (accounts.existsByCompanyIdAndIban(companyId, normalizedIban)) {
            throw new IllegalArgumentException("invoice_bank_iban_duplicate");
        }
        int order = Math.toIntExact(accounts.countByCompanyId(companyId));
        return BankAccount.from(accounts.save(
                new InvoiceBankAccount(companyId, bankName, normalizedIban, order)));
    }

    @Transactional
    public BankAccount updateAccount(UUID id, String bankName, String iban) {
        UUID companyId = organization.currentCompany().getId();
        InvoiceBankAccount account = required(id, companyId);
        String normalizedIban = InvoiceBankAccount.normalizeAndValidateIban(iban);
        if (!account.getIban().equals(normalizedIban)
                && accounts.existsByCompanyIdAndIban(companyId, normalizedIban)) {
            throw new IllegalArgumentException("invoice_bank_iban_duplicate");
        }
        account.update(bankName, normalizedIban);
        return BankAccount.from(account);
    }

    @Transactional
    public BankAccount setActive(UUID id, boolean active) {
        InvoiceBankAccount account = required(id, organization.currentCompany().getId());
        account.setActive(active);
        return BankAccount.from(account);
    }

    @Transactional(readOnly = true)
    public List<BankAccount> activeAccounts(UUID companyId) {
        return accounts(companyId, true);
    }

    private InvoiceBankAccount required(UUID id, UUID companyId) {
        return accounts.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new IllegalArgumentException("invoice_bank_account_not_found"));
    }

    private List<BankAccount> accounts(UUID companyId, boolean activeOnly) {
        var values = activeOnly
                ? accounts.findAllByCompanyIdAndActivaTrueOrderByOrdenAscIdAsc(companyId)
                : accounts.findAllByCompanyIdOrderByOrdenAscIdAsc(companyId);
        return values.stream().map(BankAccount::from).toList();
    }

    public record Configuration(String observations, List<BankAccount> bankAccounts) {
    }

    public record BankAccount(UUID id, String bankName, String iban,
            String displayIban, boolean active, int order) {
        static BankAccount from(InvoiceBankAccount value) {
            return new BankAccount(value.getId(), value.getBankName(), value.getIban(),
                    value.getIban().replaceAll("(.{4})(?!$)", "$1 "),
                    value.isActive(), value.getOrder());
        }
    }
}
