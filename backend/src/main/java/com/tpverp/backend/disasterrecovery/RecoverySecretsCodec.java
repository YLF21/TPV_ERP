package com.tpverp.backend.disasterrecovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Portable secrets remain encrypted even inside the already encrypted backup archive. */
public final class RecoverySecretsCodec {
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAGIC = 0x54505231;
    private static final ObjectMapper JSON = new ObjectMapper();
    private RecoverySecretsCodec() { }

    public static byte[] seal(Map<String, byte[]> material, byte[] brk, UUID installation, UUID backup) throws Exception {
        if (material.containsKey("backup.brk") || material.containsKey("recoverySecret"))
            throw new GeneralSecurityException("Raw recovery keys cannot be archived");
        byte[] plain = JSON.writeValueAsBytes(material);
        byte[] salt = new byte[32], nonce = new byte[12];
        new SecureRandom().nextBytes(salt); new SecureRandom().nextBytes(nonce);
        byte[] key = derive(brk, salt);
        try {
            if (plain.length > MAX_BYTES) throw new GeneralSecurityException("Portable material exceeds limit");
            byte[] header = ByteBuffer.allocate(49).putInt(MAGIC).put((byte) VERSION).put(salt).put(nonce).array();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header); cipher.updateAAD(context(installation, backup));
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(header.length + encrypted.length).put(header).put(encrypted).array();
        } finally { Arrays.fill(plain, (byte) 0); Arrays.fill(key, (byte) 0); }
    }

    public static Map<String, byte[]> open(byte[] encoded, byte[] brk, UUID installation, UUID backup) throws Exception {
        if (encoded == null || encoded.length < 65 || encoded.length > MAX_BYTES + 65)
            throw new GeneralSecurityException("Portable capsule size invalid");
        var input = ByteBuffer.wrap(encoded);
        if (input.getInt() != MAGIC || input.get() != VERSION) throw new GeneralSecurityException("Portable version unsupported");
        byte[] salt = new byte[32], nonce = new byte[12]; input.get(salt); input.get(nonce);
        byte[] key = derive(brk, salt), plain = null;
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(Arrays.copyOf(encoded, 49)); cipher.updateAAD(context(installation, backup));
            plain = cipher.doFinal(encoded, 49, encoded.length - 49);
            Map<String, byte[]> result = JSON.readValue(plain, new TypeReference<>() { });
            if (result.containsKey("backup.brk") || result.containsKey("recoverySecret")) {
                result.values().forEach(value -> Arrays.fill(value, (byte) 0));
                throw new GeneralSecurityException("Forbidden portable key material");
            }
            return result;
        } finally { Arrays.fill(key, (byte) 0); if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    private static byte[] derive(byte[] brk, byte[] salt) throws GeneralSecurityException {
        if (brk == null || brk.length != 32) throw new GeneralSecurityException("BRK length invalid");
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(brk);
        try {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update("TPV-PORTABLE-SECRETS-HKDF-V1".getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal(new byte[] { 1 });
        } finally { Arrays.fill(prk, (byte) 0); }
    }
    private static byte[] context(UUID installation, UUID backup) {
        return ("TPV-PORTABLE-SECRETS-V1\n" + installation + "\n" + backup).getBytes(StandardCharsets.US_ASCII);
    }
}
