package com.tpverp.backend.terminal;
import org.springframework.stereotype.Component;
@Component public class RedsysSimulatorGateway extends DeterministicPaymentTerminalSimulator { public RedsysSimulatorGateway(){ super(PaymentTerminalProvider.REDSYS_TPV_PC); } }
