package com.tpverp.backend.terminal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "payment_terminal_receipt")
public class PaymentTerminalReceiptRecord {
    private static final Pattern SENSITIVE_LINE = Pattern.compile("(?im)^.*\\b(PAN|PIN|CVV|CVC|SECRET)\\b.*(?:\\R|$)");

    @Id private UUID id;
    @Column(name = "operation_id", nullable = false, unique = true) private UUID operationId;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "normalized_code", nullable = false, length = 64) private String normalizedCode;
    @Column(name = "receipt_text", nullable = false, length = 4000) private String receiptText;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected PaymentTerminalReceiptRecord() {}

    public static PaymentTerminalReceiptRecord create(UUID id, UUID operationId,
            PaymentTerminalReceipt receipt, Instant now) {
        var value = new PaymentTerminalReceiptRecord();
        value.id = Objects.requireNonNull(id);
        value.operationId = Objects.requireNonNull(operationId);
        value.status = receipt.status().name();
        value.normalizedCode = limited(receipt.code(), 64);
        value.receiptText = sanitize(receipt.text());
        value.createdAt = Objects.requireNonNull(now);
        value.updatedAt = now;
        return value;
    }

    public void replace(PaymentTerminalReceipt receipt, Instant now) {
        status = receipt.status().name();
        normalizedCode = limited(receipt.code(), 64);
        receiptText = sanitize(receipt.text());
        updatedAt = Objects.requireNonNull(now);
    }

    private static String sanitize(String text) {
        var withoutSensitiveLines = SENSITIVE_LINE.matcher(Objects.requireNonNull(text)).replaceAll("");
        return limited(PaymentTerminalSensitiveData.mask(withoutSensitiveLines).trim(), 4000);
    }

    private static String limited(String value, int max) {
        var trimmed = Objects.requireNonNull(value).trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    public UUID getOperationId() { return operationId; }
    public String getStatus() { return status; }
    public String getNormalizedCode() { return normalizedCode; }
    public String getReceiptText() { return receiptText; }
    public PaymentTerminalReceipt toReceipt() { return new PaymentTerminalReceipt(
            PaymentTerminalOperationStatus.valueOf(status), normalizedCode, receiptText); }
}
