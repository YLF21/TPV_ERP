package com.tpverp.backend.terminal.bridge;

import java.util.Set;

public interface PaymentTerminalBridgeClient {
    BridgeHealth health();
    Set<String> capabilities(String provider, String mode);
    default Set<String> capabilities(String provider) { return capabilities(provider, "LIVE"); }
    default Set<String> capabilities() { return capabilities(null, "LIVE"); }
    BridgeOperationResult pair(BridgePairingRequest request);
    BridgeOperationResult operate(BridgeOperationRequest request);
}
