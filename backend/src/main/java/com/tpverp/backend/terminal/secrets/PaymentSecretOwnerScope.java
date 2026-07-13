package com.tpverp.backend.terminal.secrets;
import java.util.UUID;
public record PaymentSecretOwnerScope(UUID companyId,UUID storeId,UUID terminalId){
    private static final UUID TEST_ID=new UUID(0,1);
    static PaymentSecretOwnerScope testing(){return new PaymentSecretOwnerScope(TEST_ID,TEST_ID,TEST_ID);}
}
