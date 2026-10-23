package com.tpverp.backend.verifactu;

import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class VerifactuCertificateValidator {

    private final Clock clock;

    public VerifactuCertificateValidator(Clock clock) {
        this.clock = clock;
    }

    public VerifactuCertificateStatus validate(X509Certificate certificate) {
        certificate = Objects.requireNonNull(certificate, "certificate");
        var now = clock.instant();
        var notBefore = certificate.getNotBefore().toInstant();
        var notAfter = certificate.getNotAfter().toInstant();
        var warning = warning(certificate, now, notBefore, notAfter);
        return new VerifactuCertificateStatus(
                warning == null,
                warning,
                certificate.getSubjectX500Principal().getName(),
                notBefore,
                notAfter);
    }

    /** Validate transported key material without replacing historical certificate identities. */
    public void verifyTransferred(byte[] encodedChain,byte[] privateBytes,String fingerprint,boolean active) throws Exception {
        var path=java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertPath(new java.io.ByteArrayInputStream(encodedChain),"PKCS7");
        if(path.getCertificates().isEmpty() || !(path.getCertificates().getFirst() instanceof X509Certificate leaf))
            throw new IllegalStateException("Transferred fiscal certificate chain is empty");
        String actual=java.util.HexFormat.of().withUpperCase().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
        if(!actual.equals(fingerprint))throw new IllegalStateException("Transferred fiscal certificate fingerprint mismatch");
        if(active && !validate(leaf).valid())throw new IllegalStateException("Transferred active fiscal certificate is invalid: "+validate(leaf).warning());
        var key=java.security.KeyFactory.getInstance(leaf.getPublicKey().getAlgorithm())
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(privateBytes));
        var signature=java.security.Signature.getInstance("EC".equals(key.getAlgorithm())?"SHA256withECDSA":"SHA256withRSA");
        byte[] challenge=new byte[32];new java.security.SecureRandom().nextBytes(challenge);
        signature.initSign(key);signature.update(challenge);byte[] signed=signature.sign();
        signature.initVerify(leaf.getPublicKey());signature.update(challenge);
        if(!signature.verify(signed))throw new IllegalStateException("Transferred fiscal private key does not match its certificate");
        for(int i=0;i+1<path.getCertificates().size();i++)
            path.getCertificates().get(i).verify(path.getCertificates().get(i+1).getPublicKey());
    }
    // Evalua si el certificado puede usarse y devuelve avisos no bloqueantes para pantalla.

    private static String warning(
            X509Certificate certificate,
            java.time.Instant now,
            java.time.Instant notBefore,
            java.time.Instant notAfter) {
        if (now.isAfter(notAfter)) {
            return "CERTIFICATE_EXPIRED";
        }
        if (now.isBefore(notBefore)) {
            return "CERTIFICATE_NOT_YET_VALID";
        }
        // A signing certificate must be usable for digital signatures. Some
        // providers omit KeyUsage, in which case the issuer policy is the
        // authority and the importer performs the key-pair challenge.
        boolean[] keyUsage = certificate.getKeyUsage();
        if (keyUsage != null
                && (keyUsage.length == 0
                        || (!keyUsage[0] && (keyUsage.length < 2 || !keyUsage[1])))) {
            return "CERTIFICATE_KEY_USAGE_INVALID";
        }
        // A CA certificate must never be selected as the signing leaf.
        if (certificate.getBasicConstraints() >= 0) {
            return "CERTIFICATE_KEY_USAGE_INVALID";
        }
        return null;
    }
}
