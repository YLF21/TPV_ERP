package com.tpverp.backend.terminal;
import java.util.Objects; import java.util.UUID;
public record PaymentTerminalVoidCommand(UUID operationId,UUID originalOperationId,String reference) { public PaymentTerminalVoidCommand { Objects.requireNonNull(operationId); Objects.requireNonNull(originalOperationId); Objects.requireNonNull(reference); } }
