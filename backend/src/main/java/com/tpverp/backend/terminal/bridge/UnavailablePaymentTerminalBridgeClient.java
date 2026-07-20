package com.tpverp.backend.terminal.bridge;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public final class UnavailablePaymentTerminalBridgeClient implements PaymentTerminalBridgeClient {
    private final LocalBridgeEndpoint endpoint;
    public UnavailablePaymentTerminalBridgeClient(){this.endpoint=null;}
    public UnavailablePaymentTerminalBridgeClient(LocalBridgeEndpoint endpoint, Duration timeout){
        this.endpoint=Objects.requireNonNull(endpoint);
        if(timeout==null||timeout.isZero()||timeout.isNegative()||timeout.compareTo(Duration.ofSeconds(30))>0)
            throw new IllegalArgumentException("Bridge timeout must be between 1ms and 30s");
    }
    public BridgeHealth health(){return new BridgeHealth(false,"SDK_NOT_INSTALLED",null);}
    public Set<String> capabilities(String provider,String mode){return Set.of();}
    public BridgeOperationResult pair(BridgePairingRequest request){Objects.requireNonNull(request);return unavailable();}
    public BridgeOperationResult operate(BridgeOperationRequest request){Objects.requireNonNull(request);return unavailable();}
    private BridgeOperationResult unavailable(){return new BridgeOperationResult(false,"SDK_NOT_INSTALLED",null);}
}
