package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.SpanishTaxId;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class VerifactuCertificateImporter {

    private static final byte[] KEY_CHALLENGE =
            "TPV-ERP-VERIFACTU-CERTIFICATE-CHECK".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final VerifactuPkcs12KeyStoreLoader keyStores;
    private final CertificateTaxIdExtractor taxIds;
    private final VerifactuCertificateValidator validator;

    public VerifactuCertificateImporter(
            VerifactuPkcs12KeyStoreLoader keyStores,
            CertificateTaxIdExtractor taxIds,
            VerifactuCertificateValidator validator) {
        this.keyStores = keyStores;
        this.taxIds = taxIds;
        this.validator = validator;
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

            var keyPairFailure = keyPairFailure(privateKey, leaf);
            if (keyPairFailure != null) {
                failures.add(keyPairFailure);
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

    private static VerifactuCertificateImportException.Failure invalidValidity(String warning) {
        return switch (warning) {
            case "CERTIFICATE_EXPIRED" -> VerifactuCertificateImportException.Failure.EXPIRED;
            case "CERTIFICATE_NOT_YET_VALID" ->
                    VerifactuCertificateImportException.Failure.NOT_YET_VALID;
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
        return switch (keyAlgorithm.toUpperCase(java.util.Locale.ROOT)) {
            case "RSA" -> "SHA256withRSA";
            case "EC", "ECDSA" -> "SHA256withECDSA";
            case "DSA" -> "SHA256withDSA";
            default -> null;
        };
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
