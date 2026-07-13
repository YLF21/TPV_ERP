package com.tpverp.backend.terminal;
import org.springframework.stereotype.Component;
@Component public class PaycometSimulatorGateway extends DeterministicPaymentTerminalSimulator { public PaycometSimulatorGateway(){ super(PaymentTerminalProvider.PAYCOMET); } }
