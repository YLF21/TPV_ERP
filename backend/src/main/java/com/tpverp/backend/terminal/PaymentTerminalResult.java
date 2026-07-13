package com.tpverp.backend.terminal;
import java.util.Objects;
public record PaymentTerminalResult(PaymentTerminalOperationStatus status,String code,String reference,String authorization,String message) { public PaymentTerminalResult { Objects.requireNonNull(status); Objects.requireNonNull(code); Objects.requireNonNull(message); } }
