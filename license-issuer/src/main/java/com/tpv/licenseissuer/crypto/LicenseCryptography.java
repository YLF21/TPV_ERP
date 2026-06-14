package com.tpv.licenseissuer.crypto;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tpv.licenseissuer.model.LicenseDetails;
import com.tpv.licenseissuer.request.InstallationRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

public final class LicenseCryptography {
    private static final int VERSION = 3;
    private static final String PAYLOAD_ALGORITHM = "AES-256-GCM";
    private static final String KEY_ALGORITHM = "RSA-OAEP-256";
    private static final String SIGNATURE_ALGORITHM = "RSA-PSS-256";
    private static final PSSParameterSpec PSS_SHA256 =
            new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);
    private static final OAEPParameterSpec OAEP_SHA256 =
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final java.time.Clock clock;
    private final SecureRandom random;

    public LicenseCryptography(java.time.Clock clock) {
        this(clock, new SecureRandom());
    }

    LicenseCryptography(java.time.Clock clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    public String issue(
            InstallationRequest request,
            LicenseDetails details,
            IssuerIdentity issuer) {
        try {
            LicensePayload payload = new LicensePayload(
                    request.id(), request.reference(), details.taxId(), details.taxpayerType(),
                    details.company(), details.store(),
                    details.validFrom().toString(), details.validUntil().toString(),
                    details.maxWindows(), details.maxPda(), details.impuestos(),
                    Instant.now(clock).toString());
            byte[] payloadBytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
            SecretKey aesKey = generateAesKey();
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            byte[] encryptedPayload = encryptPayload(payloadBytes, aesKey, nonce, aad(request));
            byte[] wrappedKey = wrapKey(aesKey, request.publicKey());

            JsonObject unsigned = unsignedEnvelope(request, issuer.publicKey(), nonce, wrappedKey, encryptedPayload);
            byte[] signature = sign(canonicalBytes(unsigned), issuer.privateKey());
            unsigned.addProperty("signature", base64(signature));
            return gson.toJson(unsigned);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("cannot issue license", exception);
        }
    }

    public boolean verify(String envelopeJson, PublicKey issuerPublicKey) {
        try {
            JsonObject parsed = JsonParser.parseString(envelopeJson).getAsJsonObject();
            byte[] signatureBytes = decode(required(parsed, "signature"));
            JsonObject unsigned = canonicalUnsigned(parsed);
            Signature signature = Signature.getInstance("RSASSA-PSS");
            signature.setParameter(PSS_SHA256);
            signature.initVerify(issuerPublicKey);
            signature.update(canonicalBytes(unsigned));
            return signature.verify(signatureBytes);
        } catch (RuntimeException | GeneralSecurityException exception) {
            return false;
        }
    }

    public LicensePayload decryptAndVerify(
            String envelopeJson,
            PrivateKey installationPrivateKey,
            PublicKey issuerPublicKey) {
        if (!verify(envelopeJson, issuerPublicKey)) {
            throw new IllegalArgumentException("invalid license signature");
        }
        try {
            JsonObject envelope = JsonParser.parseString(envelopeJson).getAsJsonObject();
            SecretKey aesKey = unwrapKey(decode(required(envelope, "wrappedKey")), installationPrivateKey);
            byte[] plaintext = decryptPayload(
                    decode(required(envelope, "ciphertext")),
                    aesKey,
                    decode(required(envelope, "nonce")),
                    aad(required(envelope, "installationId"), required(envelope, "installationReference")));
            return gson.fromJson(new String(plaintext, StandardCharsets.UTF_8), LicensePayload.class);
        } catch (RuntimeException | GeneralSecurityException exception) {
            throw new IllegalArgumentException("cannot decrypt license", exception);
        }
    }

    private SecretKey generateAesKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256, random);
        return generator.generateKey();
    }

    private byte[] encryptPayload(byte[] payload, SecretKey key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce), random);
        cipher.updateAAD(aad);
        return cipher.doFinal(payload);
    }

    private byte[] decryptPayload(byte[] encrypted, SecretKey key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(encrypted);
    }

    private byte[] wrapKey(SecretKey key, PublicKey publicKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.WRAP_MODE, publicKey, OAEP_SHA256, random);
        return cipher.wrap(key);
    }

    private SecretKey unwrapKey(byte[] wrapped, PrivateKey privateKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.UNWRAP_MODE, privateKey, OAEP_SHA256);
        return (SecretKey) cipher.unwrap(wrapped, "AES", Cipher.SECRET_KEY);
    }

    private byte[] sign(byte[] data, PrivateKey privateKey) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("RSASSA-PSS");
        signature.setParameter(PSS_SHA256);
        signature.initSign(privateKey, random);
        signature.update(data);
        return signature.sign();
    }

    private JsonObject unsignedEnvelope(
            InstallationRequest request,
            PublicKey issuerPublicKey,
            byte[] nonce,
            byte[] wrappedKey,
            byte[] ciphertext) throws GeneralSecurityException {
        JsonObject object = new JsonObject();
        object.addProperty("version", VERSION);
        object.addProperty("payloadEncryption", PAYLOAD_ALGORITHM);
        object.addProperty("keyEncryption", KEY_ALGORITHM);
        object.addProperty("signatureAlgorithm", SIGNATURE_ALGORITHM);
        object.addProperty("issuerKeyId", keyId(issuerPublicKey));
        object.addProperty("installationId", request.id());
        object.addProperty("installationReference", request.reference());
        object.addProperty("nonce", base64(nonce));
        object.addProperty("wrappedKey", base64(wrappedKey));
        object.addProperty("ciphertext", base64(ciphertext));
        return object;
    }

    private JsonObject canonicalUnsigned(JsonObject source) {
        JsonObject object = new JsonObject();
        object.addProperty("version", source.get("version").getAsInt());
        for (String field : new String[]{"payloadEncryption", "keyEncryption", "signatureAlgorithm",
                "issuerKeyId", "installationId", "installationReference", "nonce", "wrappedKey", "ciphertext"}) {
            object.addProperty(field, required(source, field));
        }
        return object;
    }

    private byte[] aad(InstallationRequest request) {
        return aad(request.id(), request.reference());
    }

    private byte[] aad(String id, String reference) {
        return ("tpv-license-v3\0" + id + "\0" + reference).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] canonicalBytes(JsonObject object) {
        return gson.toJson(object).getBytes(StandardCharsets.UTF_8);
    }

    private String keyId(PublicKey key) throws GeneralSecurityException {
        return base64(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
    }

    private String required(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException("missing field: " + field);
        }
        return object.get(field).getAsString();
    }

    private String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }
}
