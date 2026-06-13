package com.tpv.licenseissuer.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;

public record IssuerIdentity(PrivateKey privateKey, PublicKey publicKey) {
    public IssuerIdentity {
        if (privateKey == null || publicKey == null) {
            throw new IllegalArgumentException("issuer key pair is required");
        }
    }
}
