package com.tpverp.backend.terminal;
import java.util.Objects;
public record PaymentTerminalReceipt(PaymentTerminalOperationStatus status,String code,String text) {
    public PaymentTerminalReceipt { Objects.requireNonNull(status); Objects.requireNonNull(code); Objects.requireNonNull(text); }
    public PaymentTerminalReceipt(String text){ this(PaymentTerminalOperationStatus.APPROVED,"RECEIPT_AVAILABLE",text); }
}
