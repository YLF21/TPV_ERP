package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.SpanishTaxId;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
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
            throw new IllegalArgumentException("El certificado PKCS#12 es obligatorio");
        }
        var workingPassword = password == null ? new char[0] : password.clone();
        try {
            var keyStore = keyStores.loadContent(pkcs12, workingPassword);
            var alias = privateAlias(keyStore);
            var key = keyStore.getKey(alias, workingPassword);
            if (!(key instanceof PrivateKey privateKey)) {
                throw new IllegalArgumentException("El PKCS#12 no contiene una clave privada");
            }
            var chain = x509Chain(keyStore, alias);
            var leaf = chain.getFirst();
            var status = validator.validate(leaf);
            if (!status.valid()) {
                throw new IllegalArgumentException(status.warning());
            }
            var taxId = taxIds.extract(leaf.getSubjectX500Principal());
            if (!taxId.equals(SpanishTaxId.validate(expectedTaxId))) {
                throw new IllegalArgumentException(
                        "El NIF del certificado no coincide con la empresa");
            }
            verifyKeyPair(privateKey, leaf);
            return new ImportedCertificateMaterial(
                    leaf.getSubjectX500Principal().getName(),
                    leaf.getIssuerX500Principal().getName(),
                    leaf.getSerialNumber().toString(16).toUpperCase(java.util.Locale.ROOT),
                    taxId,
                    leaf.getNotBefore().toInstant(),
                    leaf.getNotAfter().toInstant(),
                    fingerprint(leaf),
                    publicChain(chain),
                    privateKey.getEncoded());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("No se pudo analizar el certificado PKCS#12", exception);
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
        if (privateAliases.size() != 1) {
            throw new IllegalArgumentException(
                    "El PKCS#12 debe contener exactamente una identidad privada");
        }
        return privateAliases.getFirst();
    }

    private static ArrayList<X509Certificate> x509Chain(
            KeyStore keyStore, String alias) throws Exception {
        var certificates = keyStore.getCertificateChain(alias);
        if (certificates == null || certificates.length == 0) {
            throw new IllegalArgumentException("El PKCS#12 no contiene certificado X509");
        }
        var chain = new ArrayList<X509Certificate>(certificates.length);
        for (var certificate : certificates) {
            if (!(certificate instanceof X509Certificate x509)) {
                throw new IllegalArgumentException("La cadena contiene un certificado no X509");
            }
            chain.add(x509);
        }
        return chain;
    }

    private static void verifyKeyPair(PrivateKey privateKey, X509Certificate certificate)
            throws Exception {
        var signature = Signature.getInstance(signatureAlgorithm(privateKey.getAlgorithm()));
        signature.initSign(privateKey);
        signature.update(KEY_CHALLENGE);
        var signed = signature.sign();
        signature.initVerify(certificate.getPublicKey());
        signature.update(KEY_CHALLENGE);
        if (!signature.verify(signed)) {
            throw new IllegalArgumentException(
                    "La clave privada no corresponde al certificado");
        }
    }

    private static String signatureAlgorithm(String keyAlgorithm) {
        return switch (keyAlgorithm.toUpperCase(java.util.Locale.ROOT)) {
            case "RSA" -> "SHA256withRSA";
            case "EC", "ECDSA" -> "SHA256withECDSA";
            case "DSA" -> "SHA256withDSA";
            default -> throw new IllegalArgumentException(
                    "Algoritmo de clave privada no compatible: " + keyAlgorithm);
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
