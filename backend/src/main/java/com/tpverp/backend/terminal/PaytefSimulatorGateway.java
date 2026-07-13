package com.tpverp.backend.terminal;
import org.springframework.stereotype.Component;
@Component public class PaytefSimulatorGateway extends DeterministicPaymentTerminalSimulator { public PaytefSimulatorGateway(){ super(PaymentTerminalProvider.PAYTEF); } }
