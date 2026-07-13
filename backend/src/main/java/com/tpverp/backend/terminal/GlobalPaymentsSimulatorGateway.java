package com.tpverp.backend.terminal;
import org.springframework.stereotype.Component;
@Component public class GlobalPaymentsSimulatorGateway extends DeterministicPaymentTerminalSimulator { public GlobalPaymentsSimulatorGateway(){ super(PaymentTerminalProvider.GLOBAL_PAYMENTS); } }
