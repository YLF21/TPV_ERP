package com.tpverp.backend.terminal;
import java.util.Objects; import java.util.UUID;
public record PaymentTerminalPairCommand(UUID pairingId) { public PaymentTerminalPairCommand { Objects.requireNonNull(pairingId); } }
