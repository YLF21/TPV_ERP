package com.tpverp.backend.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "cuenta_bancaria_factura", uniqueConstraints = @UniqueConstraint(
        columnNames = {"empresa_id", "iban"}))
public class InvoiceBankAccount {

    @Id
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;

    @Column(name = "entidad", nullable = false, length = 120)
    private String bankName;

    @Column(nullable = false, length = 34)
    private String iban;

    @Column(nullable = false)
    private boolean activa = true;

    @Column(nullable = false)
    private int orden;

    @Version
    private long version;

    protected InvoiceBankAccount() {
    }

    public InvoiceBankAccount(UUID companyId, String bankName, String iban, int order) {
        this.id = UUID.randomUUID();
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        update(bankName, iban);
        if (order < 0) {
            throw new IllegalArgumentException("invoice_bank_account_order_invalid");
        }
        this.orden = order;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public String getBankName() { return bankName; }
    public String getIban() { return iban; }
    public boolean isActive() { return activa; }
    public int getOrder() { return orden; }

    public void update(String bankName, String iban) {
        this.bankName = required(bankName, "invoice_bank_name_required", 120);
        this.iban = normalizeAndValidateIban(iban);
    }

    public void setActive(boolean active) {
        this.activa = active;
    }

    static String normalizeAndValidateIban(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")) {
            throw new IllegalArgumentException("invoice_bank_iban_invalid");
        }
        String rearranged = normalized.substring(4) + normalized.substring(0, 4);
        int remainder = 0;
        for (int index = 0; index < rearranged.length(); index++) {
            char character = rearranged.charAt(index);
            String digits = Character.isDigit(character)
                    ? String.valueOf(character)
                    : String.valueOf(character - 'A' + 10);
            for (int digit = 0; digit < digits.length(); digit++) {
                remainder = (remainder * 10 + digits.charAt(digit) - '0') % 97;
            }
        }
        if (remainder != 1) {
            throw new IllegalArgumentException("invoice_bank_iban_invalid");
        }
        return normalized;
    }

    private static String required(String value, String error, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(error);
        if (normalized.length() > maxLength) throw new IllegalArgumentException(error);
        return normalized;
    }
}
