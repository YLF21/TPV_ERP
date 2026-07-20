package com.tpverp.bridge.spi;

import java.util.Set;

/**
 * Stable plugin boundary implemented by an adapter around an official vendor SDK.
 * Implementations must never expose PAN, PIN, CVV/CVC, track data or EMV cryptograms.
 */
public interface PaymentTerminalAdapter extends AutoCloseable {
    String adapterId();
    String provider();
    default AdapterManifest manifest() { return AdapterManifest.unavailable(adapterId(), provider()); }
    default void initialize(AdapterRuntime runtime) { }
    boolean supports(TerminalProfile profile);
    Set<BridgeCapability> capabilities(TerminalProfile profile);
    AdapterHealth health(TerminalProfile profile);
    OperationResult pair(PairingRequest request, TerminalProfile profile);
    OperationResult operate(OperationRequest request, TerminalProfile profile);

    @Override
    default void close() throws Exception {
    }
}
