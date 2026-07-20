package com.tpverp.bridge.vendor;

import com.tpverp.bridge.spi.AdapterHealth;
import com.tpverp.bridge.spi.AdapterRuntime;
import com.tpverp.bridge.spi.BridgeCapability;
import com.tpverp.bridge.spi.OperationRequest;
import com.tpverp.bridge.spi.OperationResult;
import com.tpverp.bridge.spi.PairingRequest;
import com.tpverp.bridge.spi.TerminalExecutionMode;
import com.tpverp.bridge.spi.TerminalProfile;
import java.util.Set;

/** Boundary implemented by a licensed Redsys, PAYTEF or PAYCOMET SDK package. */
public interface VendorTerminalDriver extends AutoCloseable {
    String provider();
    String protocol();
    TerminalExecutionMode mode();
    Set<String> connectionTypes();
    default boolean certifiedForLivePayments() { return false; }
    default void initialize(AdapterRuntime runtime) { }
    boolean supports(TerminalProfile profile);
    Set<BridgeCapability> capabilities(TerminalProfile profile);
    AdapterHealth health(TerminalProfile profile);
    OperationResult pair(PairingRequest request, TerminalProfile profile);
    OperationResult operate(OperationRequest request, TerminalProfile profile);
    @Override default void close() throws Exception { }
}
