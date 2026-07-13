package com.tpverp.backend.terminal;

import java.util.Set;

public final class UnavailableLivePaymentTerminalGateway implements CardTerminalGateway {
    private final PaymentTerminalProvider provider;
    public UnavailableLivePaymentTerminalGateway(PaymentTerminalProvider provider) { if(provider==null||provider==PaymentTerminalProvider.NONE)throw new IllegalArgumentException("provider required"); this.provider=provider; }
    @Override public boolean supports(PaymentTerminalProvider candidate,boolean testMode){ return candidate==provider&&!testMode; }
    @Override public Set<PaymentTerminalCapability> capabilities(){ return Set.of(); }
    @Override public CardTerminalResult testConnection(CardTerminalConfiguration configuration){ var r=unavailable(); return new CardTerminalResult(r.status(),null,null,r.message()); }
    @Override public CardTerminalResult charge(CardTerminalRequest request,CardTerminalConfiguration configuration){ var r=unavailable(); return new CardTerminalResult(r.status(),null,null,r.message()); }
    @Override public PaymentTerminalResult pair(PaymentTerminalPairCommand command,PaymentTerminalGatewayContext context){ return unavailable(); }
    @Override public PaymentTerminalResult pairingStatus(PaymentTerminalPairCommand command,PaymentTerminalGatewayContext context){ return unavailable(); }
    @Override public PaymentTerminalResult charge(PaymentTerminalChargeCommand command,PaymentTerminalGatewayContext context){ return unavailable(); }
    @Override public PaymentTerminalResult query(PaymentTerminalQueryCommand command,PaymentTerminalGatewayContext context){ return unavailable(); }
    @Override public PaymentTerminalResult voidAuthorization(PaymentTerminalVoidCommand command,PaymentTerminalGatewayContext context){ return unavailable(); }
    @Override public PaymentTerminalResult refund(PaymentTerminalRefundCommand command,PaymentTerminalGatewayContext context){ return unavailable(); }
    @Override public PaymentTerminalReceipt receipt(PaymentTerminalReceiptCommand command,PaymentTerminalGatewayContext context){ return new PaymentTerminalReceipt(PaymentTerminalOperationStatus.ERROR,"SDK_NOT_INSTALLED","SDK oficial de "+provider+" no instalado"); }
    @Override public PaymentTerminalResult reconcile(PaymentTerminalReconciliationCommand command,PaymentTerminalGatewayContext context){ return unavailable(); }
    private PaymentTerminalResult unavailable(){ return new PaymentTerminalResult(PaymentTerminalOperationStatus.ERROR,"SDK_NOT_INSTALLED",null,null,"SDK oficial de "+provider+" no instalado"); }
}
