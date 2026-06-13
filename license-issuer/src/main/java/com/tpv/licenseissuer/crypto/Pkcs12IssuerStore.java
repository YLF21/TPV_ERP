package com.tpv.licenseissuer.crypto;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public final class Pkcs12IssuerStore {
    private static final String ALIAS = "tpv-license-issuer";

    public IssuerIdentity loadOrCreate(Path path, char[] password) {
        if (path == null || password == null || password.length == 0) {
            throw new IllegalArgumentException("path and non-empty password are required");
        }
        try {
            if (Files.exists(path)) {
                return load(path, password);
            }
            return create(path, password);
        } catch (Exception exception) {
            throw new IllegalArgumentException("cannot load or create issuer PKCS#12", exception);
        }
    }

    private IssuerIdentity load(Path path, char[] password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            keyStore.load(input, password);
        }
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(ALIAS, password);
        Certificate certificate = keyStore.getCertificate(ALIAS);
        if (privateKey == null || certificate == null) {
            throw new IllegalArgumentException("issuer entry is missing");
        }
        return new IssuerIdentity(privateKey, certificate.getPublicKey());
    }

    private IssuerIdentity create(Path path, char[] password) throws Exception {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair keyPair = generator.generateKeyPair();
        X509Certificate certificate = selfSignedCertificate(keyPair);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry(ALIAS, keyPair.getPrivate(), password, new Certificate[]{certificate});
        try (OutputStream output = Files.newOutputStream(path)) {
            keyStore.store(output, password);
        }
        return new IssuerIdentity(keyPair.getPrivate(), keyPair.getPublic());
    }

    private X509Certificate selfSignedCertificate(KeyPair keyPair) throws Exception {
        Instant now = Instant.now();
        X500Name subject = new X500Name("CN=TPV ERP License Issuer");
        BigInteger serial = new BigInteger(160, new SecureRandom()).abs();
        var builder = new JcaX509v3CertificateBuilder(
                subject,
                serial,
                Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(3650, ChronoUnit.DAYS)),
                subject,
                keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }
}
