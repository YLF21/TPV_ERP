package com.tpverp.backend.terminal;
import java.math.BigDecimal; import java.util.Objects; import java.util.UUID;
public record PaymentTerminalRefundCommand(UUID operationId,UUID originalOperationId,BigDecimal amount,String reference) { public PaymentTerminalRefundCommand { Objects.requireNonNull(operationId); Objects.requireNonNull(originalOperationId); Objects.requireNonNull(amount); Objects.requireNonNull(reference); if(amount.signum()<=0) throw new IllegalArgumentException("amount must be positive"); } }
