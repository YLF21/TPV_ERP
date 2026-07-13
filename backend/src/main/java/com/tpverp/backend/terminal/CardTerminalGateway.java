package com.tpverp.backend.terminal;

import java.util.Set;

public interface CardTerminalGateway {

    boolean supports(PaymentTerminalProvider provider, boolean testMode);

    CardTerminalResult testConnection(CardTerminalConfiguration configuration);

    CardTerminalResult charge(CardTerminalRequest request, CardTerminalConfiguration configuration);

    Set<PaymentTerminalCapability> capabilities();
    PaymentTerminalResult pair(PaymentTerminalPairCommand command, PaymentTerminalGatewayContext context);
    PaymentTerminalResult pairingStatus(PaymentTerminalPairCommand command, PaymentTerminalGatewayContext context);
    PaymentTerminalResult charge(PaymentTerminalChargeCommand command, PaymentTerminalGatewayContext context);
    PaymentTerminalResult query(PaymentTerminalQueryCommand command, PaymentTerminalGatewayContext context);
    PaymentTerminalResult voidAuthorization(PaymentTerminalVoidCommand command, PaymentTerminalGatewayContext context);
    PaymentTerminalResult refund(PaymentTerminalRefundCommand command, PaymentTerminalGatewayContext context);
    PaymentTerminalReceipt receipt(PaymentTerminalReceiptCommand command, PaymentTerminalGatewayContext context);
    PaymentTerminalResult reconcile(PaymentTerminalReconciliationCommand command, PaymentTerminalGatewayContext context);
}
