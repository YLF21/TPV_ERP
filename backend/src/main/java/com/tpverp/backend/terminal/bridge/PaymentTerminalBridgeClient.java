package com.tpverp.backend.terminal.bridge;

import java.util.Set;

public interface PaymentTerminalBridgeClient {
    BridgeHealth health();
    Set<String> capabilities();
    BridgeOperationResult pair(String pairingCode);
    BridgeOperationResult operate(BridgeOperationRequest request);
}
