package com.tpverp.saas.servertransfer;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

final class TransferCryptography {
    private TransferCryptography() {}
    static PublicKey key(String encoded) {
        try {
            if (encoded == null || encoded.length() > 2048) throw new IllegalArgumentException();
            var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (((RSAPublicKey) key).getModulus().bitLength() < 3072) throw new IllegalArgumentException();
            return key;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid destination key", e); }
    }
    static String fingerprint(String encoded) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key(encoded).getEncoded())); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static boolean verify(String key, String payload, String signature) {
        try {
            if (signature == null || signature.length() > 2048) return false;
            var verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key(key)); verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) { return false; }
    }
    static boolean verifyDestination(String key,String proof) {
        try {
            var node=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Base64.getDecoder().decode(proof));
            String release=node.path("releaseId").asText(),schema=node.path("schemaVersion").asText(),issued=node.path("issuedAt").asText();long bytes=node.path("freeBytes").asLong();
            var at=java.time.Instant.parse(issued);var now=java.time.Instant.now();
            String host=node.path("publicHost").asText();boolean tlsReady=node.path("tlsReady").asBoolean();
            if(host.isBlank()||host.length()>253||host.contains("\n")||!node.path("tlsReady").isBoolean())return false;
            if(release.isBlank() || release.length()>128 || !schema.matches("V[0-9]+") || bytes<2L*1024*1024*1024 || at.isAfter(now.plusSeconds(60)) || at.isBefore(now.minusSeconds(86400)))return false;
            return verify(key,"TPV-DESTINATION-V2\n"+fingerprint(key)+"\n"+release+"\n"+schema+"\n"+bytes+"\n"+issued+"\n"+host+"\n"+tlsReady,node.path("signature").asText());
        }catch(Exception e){return false;}
    }
    static String encrypt(String key, String token) {
        try {
            var cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key(key), new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            return Base64.getEncoder().encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
