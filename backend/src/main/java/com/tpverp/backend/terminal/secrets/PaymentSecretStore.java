package com.tpverp.backend.terminal.secrets;

public interface PaymentSecretStore {
    SecretReferenceView create(String provider, char[] material);
    SecretReferenceView rotate(String opaqueReference, char[] material);
    void delete(String opaqueReference);
    byte[] resolve(String opaqueReference);
    SecretMetadata describe(String opaqueReference);

    record SecretReferenceView(String reference, int version, boolean present) {}
    record SecretMetadata(String reference,String provider,int version) {}
}
