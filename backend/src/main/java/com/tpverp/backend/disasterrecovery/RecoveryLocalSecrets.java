package com.tpverp.backend.disasterrecovery;

import com.tpverp.backend.shared.crypto.*;
import com.tpverp.backend.verifactu.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.*;
import java.util.*;

/** Maintenance-only portable material import; fiscal expiry/TLS suitability is tested here, not at backup creation. */
final class RecoveryLocalSecrets {
    private static final SecretProtector PROTECTOR=new WindowsMachineDpapiSecretProtector();
    private RecoveryLocalSecrets() { }
    static void install(Connection db,Map<String,byte[]> secrets) throws Exception {
        var store=VerifactuCertificateSecretStore.forTransfer(Path.of(env("TPV_VERIFACTU_SECRET_DIRECTORY")),PROTECTOR,env("TPV_BACKEND_SERVICE_ACCOUNT"));
        for(var entry:secrets.entrySet())if(entry.getKey().startsWith("fiscal/")) {
            var parts=entry.getKey().split("/");
            if(parts.length!=4 || !parts[3].equals("private-key.dpapi"))throw new IOException("Invalid fiscal secret path");
            store.write(UUID.fromString(parts[1]),UUID.fromString(parts[2]),entry.getValue());
        }
        try(var update=db.prepareStatement("update payment_terminal_secret_reference set protected_value=? where id=? and active=true")) {
            for(var entry:secrets.entrySet())if(entry.getKey().startsWith("payment/")) {
                update.setBytes(1,PROTECTOR.protect(entry.getValue()));update.setObject(2,UUID.fromString(entry.getKey().substring(8)));
                if(update.executeUpdate()!=1)throw new SQLException("Recovery payment identity mismatch");
            }
        }
        if(!Boolean.parseBoolean(System.getenv("TPV_TRANSFER_TLS_REPROVISIONED")) && secrets.containsKey("tls.certificate") && secrets.containsKey("tls.private")) {
            var tls=TransferSecretDirectory.tls(Path.of(env("TPV_TRANSFER_TLS_DIRECTORY")),env("TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT"));
            tls.write("certificate.pem",secrets.get("tls.certificate"));tls.write("private-key.pem",secrets.get("tls.private"));
        }
    }
    static void verify(Connection db,Map<String,byte[]> secrets) throws Exception {
        byte[] pub=required(secrets,"identity.public"),priv=required(secrets,"identity.private");
        try(var query=db.createStatement();var rows=query.executeQuery("select public_key from instalacion")) {
            if(!rows.next() || !MessageDigest.isEqual(pub,Base64.getDecoder().decode(rows.getString(1))) || rows.next())throw new IOException("Recovery identity mismatch");
        }
        var privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(priv));
        var publicKey=com.tpverp.backend.servertransfer.TransferEnvelope.publicKey(Base64.getEncoder().encodeToString(pub));
        var verifier=Signature.getInstance("SHA256withRSA");byte[] challenge=new byte[32];new SecureRandom().nextBytes(challenge);
        verifier.initSign(privateKey);verifier.update(challenge);byte[] signed=verifier.sign();verifier.initVerify(publicKey);verifier.update(challenge);
        if(!verifier.verify(signed))throw new IOException("Recovery identity key pair mismatch");
        var store=VerifactuCertificateSecretStore.forTransfer(Path.of(env("TPV_VERIFACTU_SECRET_DIRECTORY")),PROTECTOR,env("TPV_BACKEND_SERVICE_ACCOUNT"));
        var validator=new VerifactuCertificateValidator(java.time.Clock.systemUTC());
        try(var query=db.createStatement();var rows=query.executeQuery("select secret_path,public_chain,fingerprint,status from certificado_verifactu where secret_path is not null and status<>'ELIMINADO'")) {
            while(rows.next()) {
                byte[] material=store.read(rows.getString(1));
                try{validator.verifyTransferred(rows.getBytes(2),material,rows.getString(3),"ACTIVO".equals(rows.getString(4)));}
                finally{Arrays.fill(material,(byte)0);}
            }
        }
        try(var query=db.createStatement();var rows=query.executeQuery("select protected_value from payment_terminal_secret_reference where active=true")) {
            while(rows.next()){byte[] material=PROTECTOR.unprotect(rows.getBytes(1));Arrays.fill(material,(byte)0);}
        }
        verifyTls();
    }
    static void verifyTls() throws Exception {
        var root=Path.of(env("TPV_TRANSFER_TLS_DIRECTORY"));
        var protectedRoot=TransferSecretDirectory.tls(root,env("TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT"));protectedRoot.verifyFile("private-key.pem");
        byte[] certBytes=read(root.resolve("certificate.pem")),privateBytes=read(root.resolve("private-key.pem"));
        try {
            var cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certBytes));cert.checkValidity();
            String host=env("TPV_TRANSFER_PUBLIC_HOST");var names=cert.getSubjectAlternativeNames();
            if(names==null || names.stream().noneMatch(name->(Integer.valueOf(2).equals(name.get(0))||Integer.valueOf(7).equals(name.get(0)))&&host.equalsIgnoreCase(name.get(1).toString())))throw new IOException("TLS destination SAN mismatch; reprovision TLS");
            String pem=new String(privateBytes,StandardCharsets.US_ASCII);
            if(!pem.contains("-----BEGIN PRIVATE KEY-----"))throw new IOException("TLS PKCS8 key required");
            byte[] encoded=Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----",""));
            try {
                var key=KeyFactory.getInstance(cert.getPublicKey().getAlgorithm()).generatePrivate(new PKCS8EncodedKeySpec(encoded));
                var verifier=Signature.getInstance("EC".equals(key.getAlgorithm())?"SHA256withECDSA":"SHA256withRSA");
                byte[] challenge=new byte[32];new SecureRandom().nextBytes(challenge);verifier.initSign(key);verifier.update(challenge);byte[] signature=verifier.sign();
                verifier.initVerify(cert.getPublicKey());verifier.update(challenge);if(!verifier.verify(signature))throw new IOException("TLS key mismatch");
            }finally{Arrays.fill(encoded,(byte)0);}
        }finally{Arrays.fill(privateBytes,(byte)0);}
    }
    static byte[] required(Map<String,byte[]> material,String name) throws IOException {var value=material.get(name);if(value==null||value.length==0)throw new IOException("Recovery secret missing: "+name);return value;}
    private static byte[] read(Path path) throws Exception {if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>1024*1024)throw new IOException("TLS material invalid");return Files.readAllBytes(path);}
    static String env(String name) {var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Required maintenance environment: "+name);return value;}
}
