package com.tpverp.backend.terminal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.tpverp.backend.terminal.bridge.PaymentTerminalBridgeClient;

@Configuration
class LivePaymentTerminalGatewayConfiguration {
    @Bean CardTerminalGateway liveRedsysGateway(PaymentTerminalBridgeClient bridge){ return new BridgeLivePaymentTerminalGateway(PaymentTerminalProvider.REDSYS_TPV_PC,bridge); }
    @Bean CardTerminalGateway livePaytefGateway(PaymentTerminalBridgeClient bridge){ return new BridgeLivePaymentTerminalGateway(PaymentTerminalProvider.PAYTEF,bridge); }
    @Bean CardTerminalGateway livePaycometGateway(PaymentTerminalBridgeClient bridge){ return new BridgeLivePaymentTerminalGateway(PaymentTerminalProvider.PAYCOMET,bridge); }
    @Bean CardTerminalGateway liveGlobalPaymentsGateway(PaymentTerminalBridgeClient bridge){ return new BridgeLivePaymentTerminalGateway(PaymentTerminalProvider.GLOBAL_PAYMENTS,bridge); }
}
