package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.SpanishTaxId;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.GeneralSecurityException;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.springframework.stereotype.Component;

@Component
public class VerifactuCertificateImporter {

    private static final byte[] KEY_CHALLENGE =
            "TPV-ERP-VERIFACTU-CERTIFICATE-CHECK".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final VerifactuPkcs12KeyStoreLoader keyStores;
    private final CertificateTaxIdExtractor taxIds;
    private final VerifactuCertificateValidator validator;
    private final FiscalRuntimeProperties runtime;

    public VerifactuCertificateImporter(
            VerifactuPkcs12KeyStoreLoader keyStores,
            CertificateTaxIdExtractor taxIds,
            VerifactuCertificateValidator validator,
            FiscalRuntimeProperties runtime) {
        this.keyStores = keyStores;
        this.taxIds = taxIds;
        this.validator = validator;
        this.runtime = runtime;
    }

    // Converts a validated PKCS#12 into public material and DPAPI-ready PKCS#8.
    public ImportedCertificateMaterial importPkcs12(
            byte[] pkcs12, char[] password, String expectedTaxId) {
        if (pkcs12 == null || pkcs12.length == 0) {
            throw VerifactuCertificateImportException.of(
                    VerifactuCertificateImportException.Failure.PASSWORD_OR_FILE_INVALID);
        }
        var workingPassword = password == null ? new char[0] : password.clone();
        try {
            var keyStore = keyStores.loadContent(pkcs12, workingPassword);
            var alias = privateAlias(keyStore);
            var key = keyStore.getKey(alias, workingPassword);
            if (!(key instanceof PrivateKey privateKey)) {
                throw VerifactuCertificateImportException.of(
                        VerifactuCertificateImportException.Failure.PRIVATE_KEY_MISSING);
            }
            var chain = x509Chain(keyStore, alias);
            var leaf = chain.getFirst();
            var failures = new ArrayList<VerifactuCertificateImportException.Failure>();
            if (!validChain(chain)) {
                failures.add(VerifactuCertificateImportException.Failure.CERTIFICATE_CHAIN_INVALID);
            }
            if (!runtime.isSandbox() && !trustedByJvm(chain)) {
                failures.add(
                        VerifactuCertificateImportException.Failure.CERTIFICATE_CHAIN_UNTRUSTED);
            }
            if (!runtime.isSandbox() && isSelfSigned(leaf)) {
                failures.add(
                        VerifactuCertificateImportException.Failure.SELF_SIGNED_NOT_ALLOWED);
            }

            try {
                var status = validator.validate(leaf);
                if (!status.valid()) {
                    failures.add(invalidValidity(status.warning()));
                }
            } catch (RuntimeException exception) {
                failures.add(VerifactuCertificateImportException.Failure.STRUCTURE_INVALID);
            }

            String taxId;
            try {
                taxId = taxIds.extract(leaf.getSubjectX500Principal());
            } catch (IllegalArgumentException exception) {
                taxId = null;
                failures.add(
                        VerifactuCertificateImportException.Failure.TAX_ID_MISSING_OR_INVALID);
            }

            if (taxId != null && !Company.DEMO_TAX_ID.equals(expectedTaxId)) {
                try {
                    if (!taxId.equals(SpanishTaxId.validate(expectedTaxId))) {
                        failures.add(
                                VerifactuCertificateImportException.Failure.TAX_ID_MISMATCH);
                    }
                } catch (IllegalArgumentException exception) {
                    failures.add(VerifactuCertificateImportException.Failure.STRUCTURE_INVALID);
                }
            }

            if (!isRsa(privateKey.getAlgorithm())
                    || !isRsa(leaf.getPublicKey().getAlgorithm())) {
                failures.add(
                        VerifactuCertificateImportException.Failure.KEY_ALGORITHM_UNSUPPORTED);
            } else {
                var keyPairFailure = keyPairFailure(privateKey, leaf);
                if (keyPairFailure != null) {
                    failures.add(keyPairFailure);
                }
            }
            if (!failures.isEmpty()) {
                throw VerifactuCertificateImportException.of(failures);
            }

            var privateKeyPkcs8 = privateKey.getEncoded();
            if (privateKeyPkcs8 == null || privateKeyPkcs8.length == 0) {
                throw VerifactuCertificateImportException.of(
                        VerifactuCertificateImportException.Failure.PRIVATE_KEY_ENCODING_INVALID);
            }
            return new ImportedCertificateMaterial(
                    leaf.getSubjectX500Principal().getName(),
                    leaf.getIssuerX500Principal().getName(),
                    leaf.getSerialNumber().toString(16).toUpperCase(java.util.Locale.ROOT),
                    taxId,
                    leaf.getNotBefore().toInstant(),
                    leaf.getNotAfter().toInstant(),
                    fingerprint(leaf),
                    publicChain(chain),
                    privateKeyPkcs8);
        } catch (VerifactuCertificateImportException exception) {
            throw exception;
        } catch (Exception exception) {
            throw VerifactuCertificateImportException.of(
                    VerifactuCertificateImportException.Failure.STRUCTURE_INVALID,
                    exception);
        } finally {
            Arrays.fill(workingPassword, '\0');
        }
    }

    private static String privateAlias(KeyStore keyStore) throws Exception {
        var aliases = keyStore.aliases();
        var privateAliases = new ArrayList<String>();
        while (aliases.hasMoreElements()) {
            var alias = aliases.nextElement();
            if (keyStore.isKeyEntry(alias)) {
                privateAliases.add(alias);
            }
        }
        if (privateAliases.isEmpty()) {
            throw VerifactuCertificateImportException.of(
                    VerifactuCertificateImportException.Failure.PRIVATE_KEY_MISSING);
        }
        if (privateAliases.size() > 1) {
            throw VerifactuCertificateImportException.of(
                    VerifactuCertificateImportException.Failure.MULTIPLE_PRIVATE_KEYS);
        }
        return privateAliases.getFirst();
    }

    private static ArrayList<X509Certificate> x509Chain(
            KeyStore keyStore, String alias) throws Exception {
        var certificates = keyStore.getCertificateChain(alias);
        if (certificates == null || certificates.length == 0) {
            throw VerifactuCertificateImportException.of(
                    VerifactuCertificateImportException.Failure.CERTIFICATE_CHAIN_INVALID);
        }
        var chain = new ArrayList<X509Certificate>(certificates.length);
        for (var certificate : certificates) {
            if (!(certificate instanceof X509Certificate x509)) {
                throw VerifactuCertificateImportException.of(
                        VerifactuCertificateImportException.Failure.CERTIFICATE_CHAIN_INVALID);
            }
            chain.add(x509);
        }
        return chain;
    }

    private static boolean validChain(ArrayList<X509Certificate> chain) {
        try {
            for (int index = 0; index < chain.size() - 1; index++) {
                X509Certificate child = chain.get(index);
                X509Certificate issuer = chain.get(index + 1);
                child.verify(issuer.getPublicKey());
            }
            if (chain.size() == 1) {
                // Development certificates are intentionally self-signed. They
                // are accepted only for structural checks; REAL activation still
                // requires an operator-managed qualified certificate.
                chain.getFirst().verify(chain.getFirst().getPublicKey());
            }
            return true;
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    private static boolean isSelfSigned(X509Certificate certificate) {
        if (!certificate.getSubjectX500Principal().equals(
                certificate.getIssuerX500Principal())) {
            return false;
        }
        try {
            certificate.verify(certificate.getPublicKey());
            return true;
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    private static boolean trustedByJvm(ArrayList<X509Certificate> chain) {
        try {
            var factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            var anchors = new LinkedHashSet<TrustAnchor>();
            for (var manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    for (var issuer : x509.getAcceptedIssuers()) {
                        anchors.add(new TrustAnchor(issuer, null));
                    }
                }
            }
            if (anchors.isEmpty()) {
                return false;
            }
            var pathCertificates = new ArrayList<>(chain);
            var finalCertificate = pathCertificates.getLast();
            if (anchors.stream().map(TrustAnchor::getTrustedCert)
                    .anyMatch(finalCertificate::equals)) {
                pathCertificates.removeLast();
            }
            if (pathCertificates.isEmpty()) {
                return false;
            }
            var path = CertificateFactory.getInstance("X.509")
                    .generateCertPath(pathCertificates);
            var parameters = new PKIXParameters(anchors);
            // Revocation needs an explicit OCSP/CRL policy and network boundary;
            // trust anchoring remains fail-closed independently of that future step.
            parameters.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(path, parameters);
            return true;
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    private static boolean isRsa(String algorithm) {
        return "RSA".equalsIgnoreCase(algorithm);
    }

    private static VerifactuCertificateImportException.Failure invalidValidity(String warning) {
        return switch (warning) {
            case "CERTIFICATE_EXPIRED" -> VerifactuCertificateImportException.Failure.EXPIRED;
            case "CERTIFICATE_NOT_YET_VALID" ->
                    VerifactuCertificateImportException.Failure.NOT_YET_VALID;
            case "CERTIFICATE_KEY_USAGE_INVALID" ->
                    VerifactuCertificateImportException.Failure.KEY_USAGE_INVALID;
            default -> VerifactuCertificateImportException.Failure.STRUCTURE_INVALID;
        };
    }

    private static VerifactuCertificateImportException.Failure keyPairFailure(
            PrivateKey privateKey,
            X509Certificate certificate) {
        var algorithm = signatureAlgorithm(privateKey.getAlgorithm());
        if (algorithm == null) {
            return VerifactuCertificateImportException.Failure.KEY_ALGORITHM_UNSUPPORTED;
        }
        try {
            var signature = Signature.getInstance(algorithm);
            signature.initSign(privateKey);
            signature.update(KEY_CHALLENGE);
            var signed = signature.sign();
            signature.initVerify(certificate.getPublicKey());
            signature.update(KEY_CHALLENGE);
            return signature.verify(signed)
                    ? null
                    : VerifactuCertificateImportException.Failure.KEY_PAIR_MISMATCH;
        } catch (GeneralSecurityException exception) {
            return VerifactuCertificateImportException.Failure.KEY_PAIR_MISMATCH;
        }
    }

    private static String signatureAlgorithm(String keyAlgorithm) {
        return isRsa(keyAlgorithm) ? "SHA256withRSA" : null;
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    private static byte[] publicChain(ArrayList<X509Certificate> chain) throws Exception {
        return CertificateFactory.getInstance("X.509")
                .generateCertPath(chain)
                .getEncoded("PKCS7");
    }
}
