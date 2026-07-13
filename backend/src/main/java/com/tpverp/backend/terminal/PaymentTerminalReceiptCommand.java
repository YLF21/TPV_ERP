package com.tpverp.backend.terminal;
import java.util.Objects; import java.util.UUID;
public record PaymentTerminalReceiptCommand(UUID operationId,String reference) { public PaymentTerminalReceiptCommand { Objects.requireNonNull(operationId); Objects.requireNonNull(reference); } }
