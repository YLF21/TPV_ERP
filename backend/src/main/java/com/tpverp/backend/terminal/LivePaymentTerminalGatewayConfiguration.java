package com.tpverp.backend.terminal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class LivePaymentTerminalGatewayConfiguration {
    @Bean CardTerminalGateway unavailableLiveRedsysGateway(){ return new UnavailableLivePaymentTerminalGateway(PaymentTerminalProvider.REDSYS_TPV_PC); }
    @Bean CardTerminalGateway unavailableLivePaytefGateway(){ return new UnavailableLivePaymentTerminalGateway(PaymentTerminalProvider.PAYTEF); }
    @Bean CardTerminalGateway unavailableLivePaycometGateway(){ return new UnavailableLivePaymentTerminalGateway(PaymentTerminalProvider.PAYCOMET); }
    @Bean CardTerminalGateway unavailableLiveGlobalPaymentsGateway(){ return new UnavailableLivePaymentTerminalGateway(PaymentTerminalProvider.GLOBAL_PAYMENTS); }
}
