package com.tpverp.backend.licensing.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

public final class LicenseEnvelopeDecoder {

    private static final PSSParameterSpec PSS_SHA256 =
            new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);
    private static final OAEPParameterSpec OAEP_SHA256 =
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private final ObjectMapper objectMapper;

    public LicenseEnvelopeDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LicensePreview decode(
            String envelopeJson,
            String expectedInstallationId,
            String expectedInstallationReference,
            PrivateKey installationPrivateKey,
            PublicKey issuerPublicKey) {
        try {
            JsonNode envelope = objectMapper.readTree(envelopeJson);
            validateHeader(envelope);
            verifySignature(envelope, issuerPublicKey);
            requireExpected(envelope, "installationId", expectedInstallationId);
            requireExpected(envelope, "installationReference", expectedInstallationReference);

            SecretKey aesKey = unwrapKey(decode(envelope, "wrappedKey"), installationPrivateKey);
            byte[] plaintext = decrypt(
                    decode(envelope, "ciphertext"),
                    aesKey,
                    decode(envelope, "nonce"),
                    aad(expectedInstallationId, expectedInstallationReference));
            LicensePayload payload = objectMapper.readValue(plaintext, LicensePayload.class);
            validatePayload(payload, expectedInstallationId, expectedInstallationReference);

            var validFrom = LocalDate.parse(payload.validFrom()).atStartOfDay().toInstant(ZoneOffset.UTC);
            var validUntil = LocalDate.parse(payload.validUntil()).plusDays(1)
                    .atStartOfDay().toInstant(ZoneOffset.UTC);
            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(envelopeJson.getBytes(StandardCharsets.UTF_8)));
            return new LicensePreview(
                    "LIC-" + hash.substring(0, 20).toUpperCase(),
                    requireText(payload.company(), "company"),
                    requireText(payload.store(), "store"),
                    validFrom,
                    validUntil,
                    payload.maxWindows(),
                    payload.maxPda(),
                    required(envelope, "issuerKeyId"),
                    hash);
        } catch (LicenseValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LicenseValidationException("La licencia no es valida", exception);
        }
    }

    private void validateHeader(JsonNode envelope) {
        if (envelope.path("version").asInt(-1) != 1
                || !"AES-256-GCM".equals(required(envelope, "payloadEncryption"))
                || !"RSA-OAEP-256".equals(required(envelope, "keyEncryption"))
                || !"RSA-PSS-256".equals(required(envelope, "signatureAlgorithm"))) {
            throw new LicenseValidationException("Formato o algoritmos de licencia no admitidos");
        }
    }

    private void verifySignature(JsonNode envelope, PublicKey issuerPublicKey)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(PSS_SHA256);
        verifier.initVerify(issuerPublicKey);
        verifier.update(canonicalUnsigned(envelope));
        if (!verifier.verify(decode(envelope, "signature"))) {
            throw new LicenseValidationException("La firma del proveedor no es valida");
        }
    }

    private byte[] canonicalUnsigned(JsonNode source) {
        ObjectNode unsigned = objectMapper.createObjectNode();
        unsigned.put("version", source.path("version").asInt());
        for (String field : new String[] {
                "payloadEncryption", "keyEncryption", "signatureAlgorithm", "issuerKeyId",
                "installationId", "installationReference", "nonce", "wrappedKey", "ciphertext"
        }) {
            unsigned.put(field, required(source, field));
        }
        return unsigned.toString().getBytes(StandardCharsets.UTF_8);
    }

    private SecretKey unwrapKey(byte[] wrappedKey, PrivateKey installationPrivateKey)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.UNWRAP_MODE, installationPrivateKey, OAEP_SHA256);
        return (SecretKey) cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY);
    }

    private byte[] decrypt(byte[] ciphertext, SecretKey key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    private void validatePayload(
            LicensePayload payload,
            String expectedInstallationId,
            String expectedInstallationReference) {
        if (!expectedInstallationId.equals(payload.installationId())
                || !expectedInstallationReference.equals(payload.installationReference())) {
            throw new LicenseValidationException("La licencia pertenece a otra instalacion");
        }
        if (payload.maxWindows() < 1 || payload.maxPda() < 0) {
            throw new LicenseValidationException("Los cupos de la licencia no son validos");
        }
        LocalDate from = LocalDate.parse(payload.validFrom());
        LocalDate until = LocalDate.parse(payload.validUntil());
        if (!until.isAfter(from)) {
            throw new LicenseValidationException("La vigencia de la licencia no es valida");
        }
    }

    private void requireExpected(JsonNode node, String field, String expected) {
        if (!expected.equals(required(node, field))) {
            throw new LicenseValidationException("La licencia pertenece a otra instalacion");
        }
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return requireText(value, field);
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new LicenseValidationException("Falta el campo obligatorio " + field);
        }
        return value.trim();
    }

    private byte[] decode(JsonNode node, String field) {
        try {
            return Base64.getDecoder().decode(required(node, field));
        } catch (IllegalArgumentException exception) {
            throw new LicenseValidationException("El campo " + field + " no es Base64 valido", exception);
        }
    }

    private byte[] aad(String installationId, String installationReference) {
        return ("tpv-license-v1\0" + installationId + "\0" + installationReference)
                .getBytes(StandardCharsets.UTF_8);
    }
}
