package com.tpverp.backend.terminal;
import java.util.Objects;
public record PaymentTerminalResult(PaymentTerminalOperationStatus status,String code,String reference,String authorization,String message,
        boolean finalOutcome) {
    public PaymentTerminalResult(PaymentTerminalOperationStatus status,String code,String reference,String authorization,String message) {
        this(status,code,reference,authorization,message,defaultFinalOutcome(status));
    }
    public PaymentTerminalResult { Objects.requireNonNull(status); Objects.requireNonNull(code); Objects.requireNonNull(message); }
    private static boolean defaultFinalOutcome(PaymentTerminalOperationStatus status) {
        return status==PaymentTerminalOperationStatus.APPROVED
                || status==PaymentTerminalOperationStatus.DECLINED
                || status==PaymentTerminalOperationStatus.CANCELLED
                || status==PaymentTerminalOperationStatus.REFUNDED
                || status==PaymentTerminalOperationStatus.PARTIALLY_REFUNDED;
    }
}
