package com.tpverp.backend.terminal.bridge;
public record BridgeOperationRequest(String operationId,String command,long amountMinor,String currency) {
    public BridgeOperationRequest {
        if(operationId==null||operationId.isBlank())throw new IllegalArgumentException("operationId");
        if(command==null||!java.util.Set.of("CHARGE","QUERY","VOID","REFUND","RECEIPT").contains(command))
            throw new IllegalArgumentException("Bridge command is not allowed");
        if(amountMinor<0)throw new IllegalArgumentException("amountMinor");
        if(!"EUR".equals(currency))throw new IllegalArgumentException("Only EUR is allowed");
    }
}
