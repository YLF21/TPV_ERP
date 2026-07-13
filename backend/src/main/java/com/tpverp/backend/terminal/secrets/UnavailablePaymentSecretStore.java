package com.tpverp.backend.terminal.secrets;

final class UnavailablePaymentSecretStore implements PaymentSecretStore {
    private PaymentSecretUnavailableException unavailable(){return new PaymentSecretUnavailableException("Windows machine protection is unavailable");}
    public SecretReferenceView create(String p,char[] m){throw unavailable();} public SecretReferenceView rotate(String r,char[] m){throw unavailable();}
    public void delete(String r){throw unavailable();} public byte[] resolve(String r){throw unavailable();}
    public SecretMetadata describe(String r){throw unavailable();}
}
