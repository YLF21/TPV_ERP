package com.tpverp.backend.servertransfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.shared.crypto.*;
import com.tpverp.backend.verifactu.VerifactuCertificateSecretStore;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ServerTransferSecrets {
    private final InstallationIdentityStore identity;
    private final BackupKeyStore backups;
    private final VerifactuCertificateSecretStore certificates;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Path keyDirectory;
    private final Path tlsDirectory;
    public ServerTransferSecrets(InstallationIdentityStore identity,BackupKeyStore backups,
            VerifactuCertificateSecretStore certificates,JdbcTemplate jdbc,ObjectMapper mapper,
            @org.springframework.beans.factory.annotation.Value("${tpv.installation.key-directory}") Path keyDirectory,
            @org.springframework.beans.factory.annotation.Value("${tpv.server-transfer.tls-directory:C:/ProgramData/TPV ERP/HTTPS/tls}") Path tlsDirectory) {
        this.identity=identity;this.backups=backups;this.certificates=certificates;this.jdbc=jdbc;this.mapper=mapper;
        this.keyDirectory=keyDirectory;this.tlsDirectory=tlsDirectory;
    }
    public void preflight() {
        var material=collect();byte[] serialized=null;
        try {
            var publicKeys=jdbc.queryForList("select public_key from instalacion",String.class);
            if(publicKeys.size()!=1 || !MessageDigest.isEqual(material.get("identity.public"),Base64.getDecoder().decode(publicKeys.getFirst())))throw new IllegalStateException("TRANSFER_INSTALLATION_IDENTITY_MISMATCH");
            serialized=mapper.writeValueAsBytes(material);
            if(serialized.length>32*1024*1024)throw new IllegalStateException("TRANSFER_SECRET_CAPSULE_TOO_LARGE");
        }catch(java.io.IOException e){throw new IllegalStateException("TRANSFER_SECRET_PREFLIGHT_FAILED",e);}
        finally{if(serialized!=null)Arrays.fill(serialized,(byte)0);material.values().forEach(value->Arrays.fill(value,(byte)0));}
    }
    public String write(UUID transferId,UUID installationId,String destinationKey,Path artifact) {
        var material=collect(); byte[] plain=null;
        try {
            var key=TransferEnvelope.publicKey(destinationKey);
            var context=context(transferId,installationId,TransferEnvelope.fingerprint(key));
            plain=mapper.writeValueAsBytes(material);
            byte[] sealed=TransferEnvelope.seal(key,plain,context);
            var target=artifact.resolveSibling(artifact.getFileName()+".capsule");
            var temporary=Files.createTempFile(target.getParent(),".transfer-capsule-",".tmp");
            try {
                try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                    channel.write(java.nio.ByteBuffer.wrap(sealed));channel.force(true);
                }
                Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } finally {Files.deleteIfExists(temporary);}
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sealed));
        } catch(Exception e) { throw new IllegalStateException("TRANSFER_SECRET_CAPSULE_FAILED",e); }
        finally { if(plain!=null)Arrays.fill(plain,(byte)0);material.values().forEach(value->Arrays.fill(value,(byte)0)); }
    }
    public void preflightTls(String host,boolean destinationReprovisioned) throws Exception {
        if(destinationReprovisioned)return;
        var certificatePath=tlsDirectory.resolve("certificate.pem");var keyPath=tlsDirectory.resolve("private-key.pem");
        if(!Files.isRegularFile(certificatePath,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(keyPath,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(certificatePath)>1024*1024||Files.size(keyPath)>1024*1024)throw new IllegalStateException("TRANSFER_TLS_REPROVISION_REQUIRED");
        java.security.cert.X509Certificate certificate;
        try(var input=Files.newInputStream(certificatePath,LinkOption.NOFOLLOW_LINKS)){certificate=(java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input);}
        certificate.checkValidity();var names=certificate.getSubjectAlternativeNames();
        if(names==null || names.stream().noneMatch(name->(Integer.valueOf(2).equals(name.get(0))||Integer.valueOf(7).equals(name.get(0)))&&host.equalsIgnoreCase(name.get(1).toString())))throw new IllegalStateException("TRANSFER_TLS_DESTINATION_SAN_MISMATCH");
        String pem=Files.readString(keyPath);if(!pem.contains("-----BEGIN PRIVATE KEY-----"))throw new IllegalStateException("TRANSFER_TLS_REPROVISION_REQUIRED");
        byte[] der=Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----",""));
        try {
            var privateKey=KeyFactory.getInstance(certificate.getPublicKey().getAlgorithm()).generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
            var verifier=Signature.getInstance("EC".equals(privateKey.getAlgorithm())?"SHA256withECDSA":"SHA256withRSA");
            byte[] challenge=new byte[32];new SecureRandom().nextBytes(challenge);verifier.initSign(privateKey);verifier.update(challenge);byte[] signed=verifier.sign();
            verifier.initVerify(certificate.getPublicKey());verifier.update(challenge);if(!verifier.verify(signed))throw new IllegalStateException("TRANSFER_TLS_KEY_MISMATCH");
        }finally{Arrays.fill(der,(byte)0);}
    }
    private Map<String,byte[]> collect() {
        var result=new LinkedHashMap<String,byte[]>();
        try {
            var keys=identity.loadOrCreate();
            result.put("identity.public",keys.publicKey().getEncoded());result.put("identity.private",keys.privateKey().getEncoded());
            result.put("backup.brk",backups.loadForScheduledBackup());
            try {
                result.put("backup.recovery",backups.exportWrappedRecoveryKey());
                if(Files.isRegularFile(tlsDirectory.resolve("certificate.pem"),LinkOption.NOFOLLOW_LINKS)
                        && Files.isRegularFile(tlsDirectory.resolve("private-key.pem"),LinkOption.NOFOLLOW_LINKS)
                        && Files.isReadable(tlsDirectory.resolve("certificate.pem")) && Files.isReadable(tlsDirectory.resolve("private-key.pem"))) {
                    result.put("tls.certificate",Files.readAllBytes(tlsDirectory.resolve("certificate.pem")));
                    result.put("tls.private",Files.readAllBytes(tlsDirectory.resolve("private-key.pem")));
                }
            } catch(java.io.IOException e) {throw new IllegalStateException("TRANSFER_TLS_REPROVISION_REQUIRED",e);}
            jdbc.query("select secret_path,public_chain,fingerprint,status from certificado_verifactu where secret_path is not null and status <> 'ELIMINADO'", rs -> {
                var path=rs.getString(1);var material=certificates.read(path);result.put("fiscal/"+path,material);
                try{new com.tpverp.backend.verifactu.VerifactuCertificateValidator(java.time.Clock.systemUTC()).verifyTransferred(rs.getBytes(2),material,rs.getString(3),"ACTIVO".equals(rs.getString(4)));}
                catch(Exception invalid){throw new IllegalStateException("TRANSFER_FISCAL_CERTIFICATE_INVALID",invalid);}
            });
            var protector=new WindowsMachineDpapiSecretProtector();
            jdbc.query("select id,protected_value from payment_terminal_secret_reference where active=true", rs -> {
                result.put("payment/"+rs.getObject(1,UUID.class),protector.unprotect(rs.getBytes(2)));
            });
            return result;
        } catch(RuntimeException e) {result.values().forEach(value->Arrays.fill(value,(byte)0));throw new IllegalStateException("TRANSFER_SECRETS_REQUIRE_REPROVISION",e);}
    }
    public static String context(UUID transfer,UUID installation,String fingerprint) {
        return "TPV-SECRET-CAPSULE-V1\n"+transfer+"\n"+installation+"\n"+fingerprint;
    }

    /** Capture only rows visible to the caller's exported backup snapshot. Expiry is checked at activation. */
    public PortableCapture capturePortable(java.sql.Connection snapshot) {
        var material = new LinkedHashMap<String,byte[]>();
        var warnings = new ArrayList<String>();
        try {
            if (!Files.isRegularFile(keyDirectory.resolve("installation-private.dpapi"),LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(keyDirectory.resolve("installation-public.der"),LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("IDENTITY_MISSING");
            var pair = identity.loadOrCreate();
            try (var query = snapshot.createStatement(); var rows = query.executeQuery("select public_key from instalacion")) {
                if (!rows.next() || !MessageDigest.isEqual(pair.publicKey().getEncoded(), Base64.getDecoder().decode(rows.getString(1))) || rows.next())
                    throw new IllegalStateException("IDENTITY_MISMATCH");
            }
            material.put("identity.public",pair.publicKey().getEncoded()); material.put("identity.private",pair.privateKey().getEncoded());
        } catch (Exception invalid) { warnings.add("IDENTITY_UNAVAILABLE"); }
        try (var query = snapshot.createStatement(); var rows = query.executeQuery("select secret_path from certificado_verifactu where secret_path is not null and status <> 'ELIMINADO'")) {
            while (rows.next()) {
                try { material.put("fiscal/" + rows.getString(1),certificates.read(rows.getString(1))); }
                catch (RuntimeException invalid) { warnings.add("FISCAL_SECRET_UNAVAILABLE"); }
            }
        } catch (java.sql.SQLException invalid) { warnings.add("FISCAL_SECRET_INVENTORY_UNAVAILABLE"); }
        try (var query = snapshot.createStatement(); var rows = query.executeQuery("select id,protected_value from payment_terminal_secret_reference where active=true")) {
            var protector = new WindowsMachineDpapiSecretProtector();
            while (rows.next()) {
                try { material.put("payment/" + rows.getObject(1,UUID.class),protector.unprotect(rows.getBytes(2))); }
                catch (RuntimeException invalid) { warnings.add("PAYMENT_SECRET_UNAVAILABLE"); }
            }
        } catch (java.sql.SQLException invalid) { warnings.add("PAYMENT_SECRET_INVENTORY_UNAVAILABLE"); }
        // TLS is optional: its host binding normally needs provisioning on the replacement PC.
        for (var entry : Map.of("tls.certificate","certificate.pem","tls.private","private-key.pem").entrySet()) {
            try {
                Path file = tlsDirectory.resolve(entry.getValue());
                if (Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 1024 * 1024)
                    material.put(entry.getKey(),Files.readAllBytes(file));
            } catch (java.io.IOException ignored) { /* Reprovisioned and validated before activation. */ }
        }
        return new PortableCapture(material,List.copyOf(warnings));
    }
    public record PortableCapture(Map<String,byte[]> material,List<String> warnings) implements AutoCloseable {
        public boolean complete() { return warnings.isEmpty(); }
        @Override public void close() { material.values().forEach(value -> Arrays.fill(value,(byte)0)); }
    }
}
