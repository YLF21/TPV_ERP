package com.tpverp.bridge.globalpayments;

import com.tpverp.bridge.spi.AdapterHealth;
import com.tpverp.bridge.spi.BridgeCapability;
import com.tpverp.bridge.spi.OperationRequest;
import com.tpverp.bridge.spi.OperationResult;
import com.tpverp.bridge.spi.PairingRequest;
import com.tpverp.bridge.spi.TerminalProfile;
import com.tpverp.bridge.spi.TerminalExecutionMode;
import java.util.Set;

/**
 * Device/protocol boundary used by the Global Payments universal adapter.
 *
 * <p>A certified SDK integration implements this interface in its own JAR and
 * registers it with {@link java.util.ServiceLoader}. The driver owns all
 * vendor-specific communication and must never return cardholder data.
 */
public interface GlobalPaymentsTerminalDriver extends AutoCloseable {
    default void initialize(com.tpverp.bridge.spi.AdapterRuntime runtime) { }
    String protocol();
    TerminalExecutionMode mode();
    Set<String> connectionTypes();
    default boolean certifiedForLivePayments() { return false; }
    boolean supports(TerminalProfile profile);
    Set<BridgeCapability> capabilities(TerminalProfile profile);
    AdapterHealth health(TerminalProfile profile);
    OperationResult pair(PairingRequest request, TerminalProfile profile);
    OperationResult operate(OperationRequest request, TerminalProfile profile);

    @Override
    default void close() throws Exception {
    }
}
