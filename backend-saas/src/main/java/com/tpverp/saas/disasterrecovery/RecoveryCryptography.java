package com.tpverp.saas.disasterrecovery;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

final class RecoveryCryptography {
    private RecoveryCryptography() { }
    static PublicKey key(String encoded) {
        try {
            if (encoded == null || encoded.length() > 2048) throw new IllegalArgumentException();
            var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (((RSAPublicKey) key).getModulus().bitLength() < 3072) throw new IllegalArgumentException();
            return key;
        } catch (Exception invalid) { throw new IllegalArgumentException("RECOVERY_DESTINATION_KEY_INVALID", invalid); }
    }
    static String fingerprint(String key) { return hash(key(key).getEncoded()); }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
    static boolean verify(String key, String payload, String proof) {
        try {
            if (proof == null || proof.length() > 2048) return false;
            var signature = Signature.getInstance("SHA256withRSA"); signature.initVerify(key(key));
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(proof));
        } catch (Exception invalid) { return false; }
    }
    static String encrypt(String key, String token) {
        try {
            var cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE,key(key),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
            return Base64.getEncoder().encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
}
