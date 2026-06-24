package com.tpverp.backend.verifactu;

import java.time.Instant;

public record ImportedCertificateMaterial(
        String subject,
        String issuer,
        String serialNumber,
        String taxId,
        Instant validFrom,
        Instant validUntil,
        String fingerprint,
        byte[] publicChainPkcs7,
        byte[] privateKeyPkcs8) {

    public ImportedCertificateMaterial {
        publicChainPkcs7 = publicChainPkcs7.clone();
        privateKeyPkcs8 = privateKeyPkcs8.clone();
    }

    @Override
    public byte[] publicChainPkcs7() {
        return publicChainPkcs7.clone();
    }

    @Override
    public byte[] privateKeyPkcs8() {
        return privateKeyPkcs8.clone();
    }
}
