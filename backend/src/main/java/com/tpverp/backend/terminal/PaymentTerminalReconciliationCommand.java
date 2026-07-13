package com.tpverp.backend.terminal;
import java.util.Objects; import java.util.UUID;
public record PaymentTerminalReconciliationCommand(UUID reconciliationId) { public PaymentTerminalReconciliationCommand { Objects.requireNonNull(reconciliationId); } }
