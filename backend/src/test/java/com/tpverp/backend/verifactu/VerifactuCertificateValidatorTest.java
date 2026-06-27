package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Set;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;

class VerifactuCertificateValidatorTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-06-16T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void marcaCertificadoVigente() {
        var status = validator().validate(certificate(
                "CN=Company SL, SERIALNUMBER=B12345674",
                "2026-01-01T00:00:00Z",
                "2027-01-01T00:00:00Z"));

        assertThat(status.valid()).isTrue();
        assertThat(status.warning()).isNull();
        assertThat(status.subject()).contains("Company SL");
    }

    @Test
    void avisaSiElCertificadoEstaCaducadoONoVigenteTodavia() {
        assertThat(validator().validate(certificate(
                "CN=Company SL", "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z")))
                .extracting(VerifactuCertificateStatus::valid, VerifactuCertificateStatus::warning)
                .containsExactly(false, "CERTIFICATE_EXPIRED");

        assertThat(validator().validate(certificate(
                "CN=Company SL", "2026-07-01T00:00:00Z", "2027-01-01T00:00:00Z")))
                .extracting(VerifactuCertificateStatus::valid, VerifactuCertificateStatus::warning)
                .containsExactly(false, "CERTIFICATE_NOT_YET_VALID");
    }

    private static VerifactuCertificateValidator validator() {
        return new VerifactuCertificateValidator(CLOCK);
    }

    private static X509Certificate certificate(String subject, String notBefore, String notAfter) {
        return new TestCertificate(subject, Instant.parse(notBefore), Instant.parse(notAfter));
    }

    @SuppressWarnings("deprecation")
    private static final class TestCertificate extends X509Certificate {
        private final String subject;
        private final Instant notBefore;
        private final Instant notAfter;

        private TestCertificate(String subject, Instant notBefore, Instant notAfter) {
            this.subject = subject;
            this.notBefore = notBefore;
            this.notAfter = notAfter;
        }

        @Override public X500Principal getSubjectX500Principal() {
            return new X500Principal(subject);
        }

        @Override public Date getNotBefore() {
            return Date.from(notBefore);
        }

        @Override public Date getNotAfter() {
            return Date.from(notAfter);
        }

        @Override public void checkValidity() {
        }

        @Override public void checkValidity(Date date) {
        }

        @Override public int getVersion() {
            return 3;
        }

        @Override public BigInteger getSerialNumber() {
            return BigInteger.ONE;
        }

        @Override public Principal getIssuerDN() {
            return () -> "CN=Issuer";
        }

        @Override public Principal getSubjectDN() {
            return () -> subject;
        }

        @Override public byte[] getTBSCertificate() {
            return new byte[0];
        }

        @Override public byte[] getSignature() {
            return new byte[0];
        }

        @Override public String getSigAlgName() {
            return "NONE";
        }

        @Override public String getSigAlgOID() {
            return "0.0";
        }

        @Override public byte[] getSigAlgParams() {
            return new byte[0];
        }

        @Override public boolean[] getIssuerUniqueID() {
            return null;
        }

        @Override public boolean[] getSubjectUniqueID() {
            return null;
        }

        @Override public boolean[] getKeyUsage() {
            return null;
        }

        @Override public int getBasicConstraints() {
            return -1;
        }

        @Override public byte[] getEncoded() {
            return new byte[0];
        }

        @Override public void verify(PublicKey key) {
        }

        @Override public void verify(PublicKey key, String sigProvider) {
        }

        @Override public String toString() {
            return subject;
        }

        @Override public PublicKey getPublicKey() {
            return null;
        }

        @Override public Set<String> getCriticalExtensionOIDs() {
            return Set.of();
        }

        @Override public byte[] getExtensionValue(String oid) {
            return new byte[0];
        }

        @Override public Set<String> getNonCriticalExtensionOIDs() {
            return Set.of();
        }

        @Override public boolean hasUnsupportedCriticalExtension() {
            return false;
        }
    }
}
