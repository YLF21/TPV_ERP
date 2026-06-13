package com.tpverp.backend.licensing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LicenseEnvelopeDecoderTest {

    private static final PSSParameterSpec PSS_SHA256 =
            new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);
    private static final OAEPParameterSpec OAEP_SHA256 =
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private KeyPair installation;
    private KeyPair issuer;
    private LicenseEnvelopeDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        installation = rsaKeyPair();
        issuer = rsaKeyPair();
        decoder = new LicenseEnvelopeDecoder(objectMapper);
    }

    @Test
    void previewsAValidIssuerEnvelope() throws Exception {
        String envelope = issue("installation-id", "INST-REFERENCE", 2, "IGIC");

        LicensePreview preview = decoder.decode(
                envelope,
                "installation-id",
                "INST-REFERENCE",
                installation.getPrivate(),
                issuer.getPublic());

        assertThat(preview.company()).isEqualTo("EMPRESA SL");
        assertThat(preview.store()).isEqualTo("TIENDA PRINCIPAL");
        assertThat(preview.validFrom()).isEqualTo(Instant.parse("2026-06-08T00:00:00Z"));
        assertThat(preview.validUntil()).isEqualTo(Instant.parse("2027-06-09T00:00:00Z"));
        assertThat(preview.maxWindows()).isEqualTo(3);
        assertThat(preview.maxPda()).isEqualTo(2);
        assertThat(preview.impuestos()).isEqualTo(TaxRegime.IGIC);
    }

    @Test
    void rejectsATamperedEnvelope() throws Exception {
        ObjectNode envelope = (ObjectNode) objectMapper.readTree(
                issue("installation-id", "INST-REFERENCE", 2, "IVA"));
        envelope.put("installationReference", "ALTERED");

        assertThatThrownBy(() -> decoder.decode(
                envelope.toString(),
                "installation-id",
                "INST-REFERENCE",
                installation.getPrivate(),
                issuer.getPublic()))
                .isInstanceOf(LicenseValidationException.class)
                .hasMessageContaining("firma");
    }

    @Test
    void rejectsALicenseForAnotherInstallation() throws Exception {
        String envelope = issue("another-installation", "OTHER-REFERENCE", 2, "IVA");

        assertThatThrownBy(() -> decoder.decode(
                envelope,
                "installation-id",
                "INST-REFERENCE",
                installation.getPrivate(),
                issuer.getPublic()))
                .isInstanceOf(LicenseValidationException.class)
                .hasMessageContaining("otra instalacion");
    }

    @Test
    void rejectsVersionOneLicenses() throws Exception {
        String envelope = issue("installation-id", "INST-REFERENCE", 1, "IVA");

        assertThatThrownBy(() -> decoder.decode(
                envelope,
                "installation-id",
                "INST-REFERENCE",
                installation.getPrivate(),
                issuer.getPublic()))
                .isInstanceOf(LicenseValidationException.class)
                .hasMessageContaining("Formato");
    }

    @Test
    void rejectsVersionTwoPayloadsWithoutATaxRegime() throws Exception {
        String envelope = issue("installation-id", "INST-REFERENCE", 2, null);

        assertThatThrownBy(() -> decoder.decode(
                envelope,
                "installation-id",
                "INST-REFERENCE",
                installation.getPrivate(),
                issuer.getPublic()))
                .isInstanceOf(LicenseValidationException.class)
                .hasMessageContaining("impuestos");
    }

    @Test
    void rejectsUnknownTaxRegimes() throws Exception {
        String envelope = issue("installation-id", "INST-REFERENCE", 2, "VAT");

        assertThatThrownBy(() -> decoder.decode(
                envelope,
                "installation-id",
                "INST-REFERENCE",
                installation.getPrivate(),
                issuer.getPublic()))
                .isInstanceOf(LicenseValidationException.class);
    }

    private String issue(
            String installationId,
            String installationReference,
            int version,
            String taxRegime) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("installationId", installationId);
        payload.put("installationReference", installationReference);
        payload.put("company", "EMPRESA SL");
        payload.put("store", "TIENDA PRINCIPAL");
        payload.put("validFrom", "2026-06-08");
        payload.put("validUntil", "2027-06-08");
        payload.put("maxWindows", 3);
        payload.put("maxPda", 2);
        payload.put("issuedAt", "2026-06-08T10:15:30Z");
        if (taxRegime != null) {
            payload.put("impuestos", taxRegime);
        }
        byte[] payloadBytes = objectMapper.writeValueAsBytes(payload);
        var keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(256);
        var aesKey = keyGenerator.generateKey();
        byte[] nonce = new byte[12];
        new java.security.SecureRandom().nextBytes(nonce);

        Cipher payloadCipher = Cipher.getInstance("AES/GCM/NoPadding");
        payloadCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, nonce));
        payloadCipher.updateAAD(aad(version, installationId, installationReference));
        byte[] ciphertext = payloadCipher.doFinal(payloadBytes);

        Cipher keyCipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        keyCipher.init(Cipher.WRAP_MODE, installation.getPublic(), OAEP_SHA256);
        byte[] wrappedKey = keyCipher.wrap(aesKey);

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("version", version);
        envelope.put("payloadEncryption", "AES-256-GCM");
        envelope.put("keyEncryption", "RSA-OAEP-256");
        envelope.put("signatureAlgorithm", "RSA-PSS-256");
        envelope.put("issuerKeyId", Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(issuer.getPublic().getEncoded())));
        envelope.put("installationId", installationId);
        envelope.put("installationReference", installationReference);
        envelope.put("nonce", Base64.getEncoder().encodeToString(nonce));
        envelope.put("wrappedKey", Base64.getEncoder().encodeToString(wrappedKey));
        envelope.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));

        Signature signature = Signature.getInstance("RSASSA-PSS");
        signature.setParameter(PSS_SHA256);
        signature.initSign(issuer.getPrivate());
        signature.update(envelope.toString().getBytes(StandardCharsets.UTF_8));
        envelope.put("signature", Base64.getEncoder().encodeToString(signature.sign()));
        return envelope.toString();
    }

    private byte[] aad(int version, String installationId, String installationReference) {
        return ("tpv-license-v" + version + "\0" + installationId + "\0" + installationReference)
                .getBytes(StandardCharsets.UTF_8);
    }

    private KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
