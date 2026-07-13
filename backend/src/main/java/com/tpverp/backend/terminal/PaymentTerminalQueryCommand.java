package com.tpverp.backend.terminal;
import java.util.Objects; import java.util.UUID;
public record PaymentTerminalQueryCommand(UUID operationId,String reference) { public PaymentTerminalQueryCommand { Objects.requireNonNull(operationId); Objects.requireNonNull(reference); } }
