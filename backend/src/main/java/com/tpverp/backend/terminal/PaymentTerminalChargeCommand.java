package com.tpverp.backend.terminal;
import java.math.BigDecimal; import java.util.Objects; import java.util.UUID;
public record PaymentTerminalChargeCommand(UUID operationId, BigDecimal amount) { public PaymentTerminalChargeCommand { Objects.requireNonNull(operationId); Objects.requireNonNull(amount); if(amount.signum()<=0) throw new IllegalArgumentException("amount must be positive"); } }
