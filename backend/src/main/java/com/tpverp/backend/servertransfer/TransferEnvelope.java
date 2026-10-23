package com.tpverp.backend.servertransfer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Versioned authenticated envelope; payload plaintext is never persisted. */
public final class TransferEnvelope {
    private static final int MAGIC=0x54505631;
    private static final int MAX_BYTES=32*1024*1024;
    private TransferEnvelope() {}
    public static PublicKey publicKey(String encoded) throws GeneralSecurityException {
        if(encoded==null || encoded.length()>2048) throw new GeneralSecurityException("Invalid destination key");
        var key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        if(((RSAPublicKey)key).getModulus().bitLength()<3072) throw new GeneralSecurityException("RSA3072 required");
        return key;
    }
    public static String fingerprint(PublicKey key) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
    }
    public static String sign(PrivateKey key,String payload) throws GeneralSecurityException {
        var signer=Signature.getInstance("SHA256withRSA"); signer.initSign(key); signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }
    public static byte[] seal(PublicKey key,byte[] plaintext,String context) throws GeneralSecurityException,IOException {
        if(plaintext.length>MAX_BYTES) throw new IOException("Capsule too large");
        var generator=KeyGenerator.getInstance("AES"); generator.init(256); byte[] aes=generator.generateKey().getEncoded();
        try {
            byte[] iv=new byte[12]; new SecureRandom().nextBytes(iv);
            var rsa=rsa(Cipher.ENCRYPT_MODE,key); byte[] wrapped=rsa.doFinal(aes);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(aes,"AES"),new GCMParameterSpec(128,iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8)); byte[] encrypted=cipher.doFinal(plaintext);
            var output=new ByteArrayOutputStream();
            try(var data=new DataOutputStream(output)) { data.writeInt(MAGIC); data.writeInt(wrapped.length);data.write(wrapped);data.write(iv);data.write(encrypted); }
            return output.toByteArray();
        } finally { Arrays.fill(aes,(byte)0); }
    }
    public static byte[] open(PrivateKey key,byte[] envelope,String context) throws GeneralSecurityException,IOException {
        if(envelope.length>MAX_BYTES+2048) throw new IOException("Capsule too large");
        try(var data=new DataInputStream(new ByteArrayInputStream(envelope))) {
            if(data.readInt()!=MAGIC) throw new IOException("Capsule version unsupported");
            int length=data.readInt(); if(length<384 || length>1024) throw new IOException("Invalid wrapped key");
            byte[] wrapped=data.readNBytes(length),iv=data.readNBytes(12),aes=rsa(Cipher.DECRYPT_MODE,key).doFinal(wrapped);
            try {
                var cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(aes,"AES"),new GCMParameterSpec(128,iv));
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8)); return cipher.doFinal(data.readAllBytes());
            } finally { Arrays.fill(aes,(byte)0); }
        }
    }
    public static Cipher rsa(int mode,Key key) throws GeneralSecurityException {
        var cipher=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(mode,key,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT)); return cipher;
    }
}
