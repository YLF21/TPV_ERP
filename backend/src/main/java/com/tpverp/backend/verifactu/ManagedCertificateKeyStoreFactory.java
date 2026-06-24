package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.CurrentOrganization;
import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class ManagedCertificateKeyStoreFactory {

    private final ManagedVerifactuCertificateRepository certificates;
    private final VerifactuCertificateSecretStore secrets;
    private final CurrentOrganization organization;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ManagedCertificateKeyStoreFactory(
            ManagedVerifactuCertificateRepository certificates,
            VerifactuCertificateSecretStore secrets,
            CurrentOrganization organization,
            Clock clock) {
        this.certificates = certificates;
        this.secrets = secrets;
        this.organization = organization;
        this.clock = clock;
    }

    // Reconstruye un KeyStore temporal sin exponer la clave privada persistida.
    public ManagedKeyStore activeForCurrentCompany() {
        var companyId = organization.currentCompany().getId();
        var managed = certificates.findByCompanyIdAndStatus(
                        companyId, ManagedCertificateStatus.ACTIVO)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe un certificado VERI*FACTU activo"));
        byte[] privateKey = null;
        char[] password = null;
        try {
            var chain = chain(managed.getPublicChain());
            chain[0].checkValidity(java.util.Date.from(clock.instant()));
            privateKey = secrets.read(managed.getSecretPath());
            var key = KeyFactory.getInstance(chain[0].getPublicKey().getAlgorithm())
                    .generatePrivate(new PKCS8EncodedKeySpec(privateKey));
            password = randomPassword();
            var keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, password);
            keyStore.setKeyEntry("verifactu", key, password, chain);
            return new ManagedKeyStore(keyStore, password);
        } catch (java.security.cert.CertificateExpiredException exception) {
            throw new IllegalStateException("El certificado VERI*FACTU esta caducado", exception);
        } catch (java.security.cert.CertificateNotYetValidException exception) {
            throw new IllegalStateException(
                    "El certificado VERI*FACTU todavia no es valido", exception);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "No se pudo preparar el certificado VERI*FACTU", exception);
        } finally {
            clear(privateKey);
            clear(password);
        }
    }

    private static X509Certificate[] chain(byte[] encoded) throws Exception {
        var path = CertificateFactory.getInstance("X.509")
                .generateCertPath(new ByteArrayInputStream(encoded), "PKCS7");
        var result = new X509Certificate[path.getCertificates().size()];
        for (var index = 0; index < result.length; index++) {
            Certificate certificate = path.getCertificates().get(index);
            if (!(certificate instanceof X509Certificate x509)) {
                throw new IllegalArgumentException("La cadena publica no contiene X509");
            }
            result[index] = x509;
        }
        if (result.length == 0) {
            throw new IllegalArgumentException("La cadena publica esta vacia");
        }
        return result;
    }

    private char[] randomPassword() {
        var bytes = new byte[24];
        random.nextBytes(bytes);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray();
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    public static final class ManagedKeyStore implements AutoCloseable {
        private final KeyStore keyStore;
        private final char[] password;

        private ManagedKeyStore(KeyStore keyStore, char[] password) {
            this.keyStore = keyStore;
            this.password = password.clone();
        }

        public KeyStore keyStore() {
            return keyStore;
        }

        public char[] password() {
            return password.clone();
        }

        @Override
        public void close() {
            Arrays.fill(password, '\0');
        }
    }
}
