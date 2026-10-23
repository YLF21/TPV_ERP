package com.tpverp.backend.servertransfer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.tpverp.backend.backup.application.*;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.shared.crypto.*;
import com.tpverp.backend.verifactu.VerifactuCertificateSecretStore;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Maintenance entry point. Never starts Spring, a scheduler, or an HTTP listener. */
public final class OfflineServerTransferCli {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final SecretProtector PROTECTOR=new WindowsMachineDpapiSecretProtector();
    private final Path directory,keys;
    private final Properties release=new Properties();
    private OfflineServerTransferCli(Path directory) throws Exception {
        this.directory=safe(directory);this.keys=safe(Path.of(env("TPV_KEY_DIRECTORY")));
        try(var input=getClass().getResourceAsStream("/META-INF/tpv-erp-release.properties")) {
            if(input==null)throw new IOException("Release metadata missing");release.load(input);
        }
    }
    public static void main(String[] arguments) throws Exception {
        var args=parse(arguments);
        var cli=new OfflineServerTransferCli(Path.of(required(args,"directory")));
        String action=required(args,"action");
        if(Set.of("request","receive","activate").contains(action)) {
            serviceAccount("TPV_BACKEND_SERVICE_ACCOUNT");serviceAccount("TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT");
        }
        if(!"status".equals(action)) cli.requireStopped();
        Files.createDirectories(cli.directory);
        try(var channel=FileChannel.open(cli.directory.resolve("maintenance.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException("Another transfer maintenance operation is running");
            try(var output=openResultFile(cli.directory,args.get("result-file"))) {
                Object result=switch(action) {
                    case "request" -> cli.request();
                    case "receive" -> cli.receive(safe(Path.of(required(args,"backup"))));
                    case "activate" -> cli.activate();
                    case "status" -> cli.journal();
                    case "cancel" -> cli.cancelSource();
                    default -> throw new IllegalArgumentException("Unsupported action");
                };
                if(output==null)System.out.println(JSON.writeValueAsString(result));
                else output.write(publicResult(result));
            }
        }
    }
    // Reserve a fresh private result before executing any action; stdout remains attached to the console.
    static OutputStream openResultFile(Path directory,String name)throws Exception {
        if(name==null)return null;
        if(!name.matches("maintenance-result-[0-9a-f]{32}\\.json"))throw new IllegalArgumentException("Invalid maintenance result name");
        TransferSecretHandoff.requireProtectedDirectory(directory);
        return Files.newOutputStream(directory.resolve(name),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
    }
    static byte[] publicResult(Object result)throws IOException {
        JsonNode source=JSON.valueToTree(result);
        var output=JSON.createObjectNode();
        for(String name:List.of("state","transferId","recoveryId","destinationPublicKey","destinationKeyFingerprint"))
            if(source.hasNonNull(name))output.set(name,source.get(name));
        return JSON.writeValueAsBytes(output);
    }
    private Map<String,Object> request() throws Exception {
        TransferSecretHandoff.requireProtectedDirectory(keys);
        requireEmptyDatabase();
        requireSpace(directory,2L*1024*1024*1024);
        if(Files.exists(keys.resolve("installation-private.dpapi")))throw new IllegalStateException("Destination already has an installation identity");
        var identity=new InstallationIdentityStore(directory.resolve("destination-identity"),PROTECTOR).loadOrCreate();
        boolean tlsReady=Boolean.parseBoolean(System.getenv("TPV_TRANSFER_TLS_REPROVISIONED"));
        if(tlsReady)validateTls(Map.of());
        String fingerprint=TransferEnvelope.fingerprint(identity.publicKey());
        var result=new LinkedHashMap<String,Object>();
        result.put("state","DESTINATION_READY");result.put("destinationPublicKey",Base64.getEncoder().encodeToString(identity.publicKey().getEncoded()));
        result.put("destinationProof",DestinationPreflight.issue(identity.privateKey(),identity.publicKey(),release.getProperty("release.id"),release.getProperty("schema.version"),Files.getFileStore(directory).getUsableSpace(),env("TPV_TRANSFER_PUBLIC_HOST"),tlsReady));
        result.put("destinationKeyFingerprint",fingerprint);result.put("releaseId",release.getProperty("release.id"));
        write(directory.resolve("destination-request.json"),JSON.writeValueAsBytes(result));
        return result;
    }
    private Object receive(Path backup) throws Exception {
        var prior=journal();
        if("RECEIVED".equals(prior.path("state").asText()) || "ACTIVE".equals(prior.path("state").asText()))return prior;
        if(!Files.isRegularFile(backup,LinkOption.NOFOLLOW_LINKS))throw new IOException("Backup file required");
        var manifest=JSON.readTree(readBounded(sibling(backup,".transfer.json"),128*1024));
        UUID id=uuid(manifest,"transferId"),installation=uuid(manifest,"installationId");
        String digest=hash(backup),capsuleHash=hash(sibling(backup,".capsule"));
        boolean initial="NONE".equals(prior.path("state").asText());
        if(!initial) {
            if(!id.toString().equals(text(prior,"transferId")) || !digest.equals(text(prior,"backupSha256")) || !capsuleHash.equals(text(prior,"capsuleSha256")))throw new IOException("Another transfer already owns this destination journal");
            if("VALIDATING".equals(prior.path("state").asText())) {
                try(var db=database();var stmt=db.createStatement();var rows=stmt.executeQuery("select to_regclass('server_transfer')")) {
                    rows.next();initial=rows.getString(1)==null;
                }
            }
        }
        if(initial){requireEmptyDatabase();requireEmptyTree(Path.of(env("TPV_PRODUCT_IMAGE_DIRECTORY")));requireEmptyTree(Path.of(env("TPV_DOCUMENT_TEMPLATE_DIRECTORY")));}
        else {
            try(var db=database();var query=db.prepareStatement("select count(*) from server_transfer where id=? and installation_id=? and state in ('FROZEN','RECEIVED')")) {
                query.setObject(1,id);query.setObject(2,installation);try(var rows=query.executeQuery()){if(!rows.next() || rows.getLong(1)!=1)throw new IOException("Destination resume database does not match journal");}
            }
        }
        if(!digest.equals(text(manifest,"backupSha256")) || !capsuleHash.equals(text(manifest,"capsuleSha256")))throw new IOException("Transfer artifacts do not match");
        var identity=destinationIdentity();String fingerprint=TransferEnvelope.fingerprint(identity.publicKey());
        if(!fingerprint.equals(text(manifest,"destinationKeyFingerprint")))throw new IOException("Transfer belongs to another destination");
        var central=inspect(id,installation,identity.privateKey());requireCentral(central,id,installation,"SEALED",digest,capsuleHash,fingerprint);
        requireSpace(directory,Math.max(2L*1024*1024*1024,Math.multiplyExact(Files.size(backup),4)));
        byte[] plain=TransferEnvelope.open(identity.privateKey(),readBounded(sibling(backup,".capsule"),34*1024*1024),ServerTransferSecrets.context(id,installation,fingerprint));
        Map<String,byte[]> secrets=null;
        Path archive=directory.resolve("validated-transfer.zip"),staging=directory.resolve("staging-"+UUID.randomUUID());
        try {
            secrets=JSON.readValue(plain,new TypeReference<Map<String,byte[]>>(){});
            // Freeze outside PostgreSQL before restoring even a single byte of business state.
            new ServerTransferJournal(keys).freeze(id);
            if(initial)saveJournal(id,installation,"VALIDATING",backup,digest,capsuleHash,fingerprint);
            new BackupFileCrypto(1024*1024).decrypt(backup,archive,secret(secrets,"backup.brk"));
            var internal=readManifest(archive);validateInternal(internal,id,installation,fingerprint);
            validateTls(secrets);
            var archives=new BackupArchiveService(500_000,1024L*1024*1024,10L*1024*1024*1024);
            archives.extractToStaging(archive,staging.resolve("database.backup"),staging.resolve("images"),staging.resolve("templates"));
            // Recheck immutable content and central authorization immediately before DB creation.
            if(!digest.equals(hash(backup)) || !capsuleHash.equals(hash(sibling(backup,".capsule"))))throw new IOException("Artifacts changed during validation");
            requireCentral(inspect(id,installation,identity.privateKey()),id,installation,"SEALED",digest,capsuleHash,fingerprint);
            if(initial)restore(staging.resolve("database.backup"));
            saveJournal(id,installation,"DATABASE_RESTORED",backup,digest,capsuleHash,fingerprint);
            try(var db=database()) {validateDatabase(db,internal,installation,secrets);}
            installSecrets(secrets,id);
            archives.replaceTrees(staging.resolve("images"),Path.of(env("TPV_PRODUCT_IMAGE_DIRECTORY")),staging.resolve("templates"),Path.of(env("TPV_DOCUMENT_TEMPLATE_DIRECTORY")));
            try(var db=database()) {
                db.setAutoCommit(false);maintenance(db,id);
                TransferBackupCatalogFinalizer.finalizeReceived(db,internal,backup,Path.of(env("TPV_BACKUP_DIRECTORY")),digest);
                try(var update=db.prepareStatement("update server_transfer set state='RECEIVED',backup_sha256=?,capsule_sha256=? where id=?")) {
                    update.setString(1,digest);update.setString(2,capsuleHash);update.setObject(3,id);if(update.executeUpdate()!=1)throw new SQLException("Missing transfer state");
                }
                db.commit();
            }
            saveJournal(id,installation,"RECEIVED",backup,digest,capsuleHash,fingerprint);
            return journal();
        } finally {Arrays.fill(plain,(byte)0);if(secrets!=null)secrets.values().forEach(v->Arrays.fill(v,(byte)0));Files.deleteIfExists(archive);}
    }
    private Object activate() throws Exception {
        var state=journal();String phase=state.path("state").asText();
        if("ACTIVE".equals(phase))return state;
        if(!Set.of("RECEIVED","ACTIVATING").contains(phase))throw new IllegalStateException("Destination is not ready for activation");
        UUID id=uuid(state,"transferId"),installation=uuid(state,"installationId");
        String digest=text(state,"backupSha256"),capsule=text(state,"capsuleSha256"),fingerprint=text(state,"destinationKeyFingerprint");
        var identity=destinationIdentity();
        if(!fingerprint.equals(TransferEnvelope.fingerprint(identity.publicKey())))throw new IllegalStateException("Destination identity mismatch");
        verifyInstalledSecrets(id);
        var central=inspect(id,installation,identity.privateKey());
        if(!Set.of("SEALED","ACTIVATED").contains(central.path("state").asText()))throw new IllegalStateException("Transfer cancelled or not sealed");
        requireCentral(central,id,installation,central.path("state").asText(),digest,capsule,fingerprint);
        saveJournal(id,installation,"ACTIVATING",Path.of(text(state,"backup")),digest,capsule,fingerprint);
        String payload="TPV-ACTIVATE-V1\n"+id+"\n"+installation+"\n"+digest+"\n"+capsule+"\n"+fingerprint;
        var activation=post("/"+id+"/activate",Map.of("installationId",installation,"backupSha256",digest,"capsuleSha256",capsule,"destinationProof",TransferEnvelope.sign(identity.privateKey(),payload)),null);
        requireCentral(activation.path("status"),id,installation,"ACTIVATED",digest,capsule,fingerprint);
        // The actual Windows service account writes its CurrentUser-DPAPI token at first boot.
        var handoff=TransferSecretHandoff.read(keys);handoff.put("phase","ACTIVATED");TransferSecretHandoff.write(keys,handoff);
        // The SaaS result is durable and recoverable by the destination proof if this commit/ack is lost.
        try(var db=database()) {
            db.setAutoCommit(false);
            try {
                maintenance(db,id);
                try(var query=db.prepareStatement("select state from server_transfer where id=? for update")) {
                    query.setObject(1,id);try(var rows=query.executeQuery()) {
                        if(!rows.next() || !Set.of("RECEIVED","ACTIVE").contains(rows.getString(1)))throw new SQLException("Destination database phase mismatch");
                        if(!"ACTIVE".equals(rows.getString(1))) {
                            try(var stmt=db.createStatement()) {
                                stmt.executeUpdate("update sesion set revocada_en=coalesce(revocada_en,now()),revoke_reason='SERVER_TRANSFER',version=version+1 where revocada_en is null");
                                stmt.executeUpdate("update terminal_physical_binding set status='RELEASED',ended_at=now(),expires_at=null,version=version+1 where terminal_id in (select id from terminal where workstation_code='001') and status in ('PENDING','ACTIVE','DISABLED')");
                            }
                            try(var update=db.prepareStatement("update terminal set current_binding_id=null,credential_hash=?,version=version+1 where workstation_code='001'")) {
                                update.setString(1,new BCryptPasswordEncoder(12).encode(UUID.randomUUID()+UUID.randomUUID().toString()));
                                if(update.executeUpdate()!=1)throw new SQLException("Server001 identity missing or ambiguous");
                            }
                            try(var update=db.prepareStatement("update server_transfer set state='ACTIVE' where id=?")){update.setObject(1,id);update.executeUpdate();}
                        }
                    }
                }
                db.commit();
            } catch(Exception e){db.rollback();throw e;}
        }
        // This is destination-only activation proof, never a source cancellation shortcut.
        // The external fence stays until service-user DPAPI rewrap succeeds during early bootstrap.
        saveJournal(id,installation,"ACTIVE",Path.of(text(state,"backup")),digest,capsule,fingerprint);
        return journal();
    }
    private Object cancelSource() throws Exception {
        try(var db=database();var stmt=db.createStatement();var rows=stmt.executeQuery("select id,installation_id,state from server_transfer where state in ('PREPARED','FROZEN','SEALED','ACTIVATED') order by created_at desc limit 1")) {
            if(!rows.next())throw new IllegalStateException("No pending source transfer");
            UUID id=rows.getObject(1,UUID.class),installation=rows.getObject(2,UUID.class);
            var console=System.console();if(console==null)throw new IllegalStateException("Secure console required for protected ADMIN reauthentication");
            char[] password=console.readPassword("Contraseña ADMIN (no se mostrará): ");
            try(var account=db.createStatement();var admin=account.executeQuery("select password_hash from usuario where nombre='ADMIN' and protegido=true and activo=true and tienda_id is null")) {
                if(password==null || !admin.next() || !new BCryptPasswordEncoder().matches(new String(password),admin.getString(1)) || admin.next())throw new IllegalStateException("Protected ADMIN password invalid");
            } finally{if(password!=null)Arrays.fill(password,'\0');}
            var tokenPath=keys.resolve("server-transfer-source-token.dpapi");
            byte[] tokenBytes=PROTECTOR.unprotect(readBounded(tokenPath,1024*1024));
            JsonNode escrow;try{escrow=JSON.readTree(tokenBytes);}finally{Arrays.fill(tokenBytes,(byte)0);}
            if(!id.toString().equals(text(escrow,"transferId")) || !installation.toString().equals(text(escrow,"installationId")))throw new IllegalStateException("Source cancellation escrow mismatch");
            var token=text(escrow,"token");
            var proof=post("/"+id+"/cancel",Map.of("installationId",installation),token);
            if(!id.toString().equals(text(proof,"transferId")) || !installation.toString().equals(text(proof,"installationId")) || !"CANCELLED".equals(text(proof,"state")))throw new IllegalStateException("Cancellation not confirmed centrally");
            try(var update=db.prepareStatement("update server_transfer set state='CANCELLED' where id=?")){update.setObject(1,id);update.executeUpdate();}
            new ServerTransferJournal(keys).releaseAfterCentralCancellation(id);
            Files.deleteIfExists(tokenPath);
            return Map.of("state","CANCELLED","transferId",id);
        }
    }
    private void installSecrets(Map<String,byte[]> secrets,UUID id) throws Exception {
        Files.createDirectories(keys);
        var handoff=JSON.createObjectNode();handoff.setAll((com.fasterxml.jackson.databind.node.ObjectNode)journal());
        handoff.put("phase","RECEIVED");handoff.put("destinationPrivate",destinationIdentity().privateKey().getEncoded());
        var material=handoff.putObject("secrets");
        for(String name:List.of("identity.public","identity.private","backup.brk","backup.recovery"))material.put(name,secret(secrets,name));
        TransferSecretHandoff.write(keys,handoff);
        Path fiscalRoot=safe(Path.of(env("TPV_VERIFACTU_SECRET_DIRECTORY")));
        var fiscalStore=VerifactuCertificateSecretStore.forTransfer(fiscalRoot,PROTECTOR,serviceAccount("TPV_BACKEND_SERVICE_ACCOUNT"));
        for(var entry:secrets.entrySet())if(entry.getKey().startsWith("fiscal/")) {
            var parts=entry.getKey().split("/");
            if(parts.length!=4 || !"private-key.dpapi".equals(parts[3]))throw new IOException("Invalid fiscal secret path");
            UUID company=UUID.fromString(parts[1]),certificate=UUID.fromString(parts[2]);
            fiscalStore.write(company,certificate,entry.getValue());
        }
        try(var db=database()) {
            db.setAutoCommit(false);maintenance(db,id);
            try(var update=db.prepareStatement("update payment_terminal_secret_reference set protected_value=? where id=? and active=true")) {
                for(var entry:secrets.entrySet())if(entry.getKey().startsWith("payment/")) {
                    update.setBytes(1,PROTECTOR.protect(entry.getValue()));update.setObject(2,UUID.fromString(entry.getKey().substring(8)));
                    if(update.executeUpdate()!=1)throw new SQLException("Payment secret identity mismatch");
                }
            }
            db.commit();
        }
        if(!Boolean.parseBoolean(System.getenv("TPV_TRANSFER_TLS_REPROVISIONED")) && secrets.containsKey("tls.certificate") && secrets.containsKey("tls.private")) {
            var tls=com.tpverp.backend.verifactu.TransferSecretDirectory.tls(safe(Path.of(env("TPV_TRANSFER_TLS_DIRECTORY"))),serviceAccount("TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT"));
            tls.write("certificate.pem",secrets.get("tls.certificate"));tls.write("private-key.pem",secrets.get("tls.private"));
        }
    }
    private void verifyInstalledSecrets(UUID id) throws Exception {
        var identity=TransferSecretHandoff.read(keys).path("secrets").path("identity.public").binaryValue();
        try(var db=database();var stmt=db.createStatement();var rows=stmt.executeQuery("select public_key from instalacion")) {
            if(!rows.next() || !MessageDigest.isEqual(identity,Base64.getDecoder().decode(rows.getString(1))))throw new IOException("Installation identity was not restored");
        }
        var tls=Path.of(env("TPV_TRANSFER_TLS_DIRECTORY"));
        com.tpverp.backend.verifactu.TransferSecretDirectory.tls(tls,serviceAccount("TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT")).verifyFile("private-key.pem");
        validateTls(Map.of("tls.certificate",readBounded(tls.resolve("certificate.pem"),1024*1024),"tls.private",readBounded(tls.resolve("private-key.pem"),1024*1024)));
        Path fiscalRoot=safe(Path.of(env("TPV_VERIFACTU_SECRET_DIRECTORY")));
        var fiscalStore=VerifactuCertificateSecretStore.forTransfer(fiscalRoot,PROTECTOR,serviceAccount("TPV_BACKEND_SERVICE_ACCOUNT"));
        var validator=new com.tpverp.backend.verifactu.VerifactuCertificateValidator(java.time.Clock.systemUTC());
        try(var db=database();var stmt=db.createStatement();var rows=stmt.executeQuery("select secret_path,public_chain,fingerprint,status from certificado_verifactu where secret_path is not null and status <> 'ELIMINADO'")) {
            while(rows.next()){
                byte[] material=fiscalStore.read(rows.getString(1));
                try{validator.verifyTransferred(rows.getBytes(2),material,rows.getString(3),"ACTIVO".equals(rows.getString(4)));}
                finally{Arrays.fill(material,(byte)0);}
            }
        }
        try(var db=database();var stmt=db.createStatement();var rows=stmt.executeQuery("select protected_value from payment_terminal_secret_reference where active=true")) {
            while(rows.next()){byte[] material=PROTECTOR.unprotect(rows.getBytes(1));Arrays.fill(material,(byte)0);}
        }
    }
    private void validateTls(Map<String,byte[]> secrets) throws Exception {
        byte[] certificate=secrets.get("tls.certificate"),privateBytes=secrets.get("tls.private");
        if(Boolean.parseBoolean(System.getenv("TPV_TRANSFER_TLS_REPROVISIONED")) || certificate==null || privateBytes==null) {
            var tls=Path.of(env("TPV_TRANSFER_TLS_DIRECTORY"));
            var protectedTls=com.tpverp.backend.verifactu.TransferSecretDirectory.tls(tls,serviceAccount("TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT"));
            protectedTls.verifyFile("private-key.pem");
            certificate=readBounded(tls.resolve("certificate.pem"),1024*1024);privateBytes=readBounded(tls.resolve("private-key.pem"),1024*1024);
        }
        var cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate));cert.checkValidity();
        String host=env("TPV_TRANSFER_PUBLIC_HOST");
        var names=cert.getSubjectAlternativeNames();
        if(names==null || names.stream().noneMatch(name->(Integer.valueOf(2).equals(name.get(0))||Integer.valueOf(7).equals(name.get(0))) && host.equalsIgnoreCase(name.get(1).toString())))
            throw new IOException("TLS certificate must be reprovisioned for the destination host/SAN");
        String pem=new String(privateBytes,StandardCharsets.US_ASCII);
        if(!pem.contains("-----BEGIN PRIVATE KEY-----"))throw new IOException("TLS private key is not exportable PKCS8; reprovision TLS before activation");
        byte[] der=Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----",""));
        try {
            var key=KeyFactory.getInstance(cert.getPublicKey().getAlgorithm()).generatePrivate(new PKCS8EncodedKeySpec(der));
            var signature=Signature.getInstance("EC".equals(key.getAlgorithm())?"SHA256withECDSA":"SHA256withRSA");
            byte[] challenge=new byte[32];new SecureRandom().nextBytes(challenge);signature.initSign(key);signature.update(challenge);byte[] signed=signature.sign();
            signature.initVerify(cert.getPublicKey());signature.update(challenge);if(!signature.verify(signed))throw new IOException("TLS key and certificate differ");
        } finally {Arrays.fill(der,(byte)0);}
    }
    private void validateInternal(JsonNode value,UUID transfer,UUID installation,String fingerprint) throws Exception {
        var linked=value.path("serverTransfer");
        if(!installation.toString().equals(text(value,"installationId")) || !transfer.toString().equals(text(linked,"transferId"))
                || !installation.toString().equals(text(linked,"installationId")) || !fingerprint.equals(text(linked,"destinationKeyFingerprint")))throw new IOException("Backup is not the authorized final transfer snapshot");
        if(!release.getProperty("release.id").equals(text(value,"releaseId")) || !release.getProperty("schema.version").equals(text(value,"databaseSchemaVersion")))
            throw new IOException("Destination must use exactly the source release and schema");
    }
    private void validateDatabase(Connection db,JsonNode manifest,UUID installation,Map<String,byte[]> secrets) throws Exception {
        try(var stmt=db.createStatement();var rows=stmt.executeQuery("select id,public_key from instalacion")) {
            if(!rows.next() || !installation.equals(rows.getObject(1,UUID.class)) || !MessageDigest.isEqual(secret(secrets,"identity.public"),Base64.getDecoder().decode(rows.getString(2))) || rows.next())throw new SQLException("Restored logical installation mismatch");
        }
        BackupSnapshotService.verifyRestoredSnapshot(db,manifest);
        try(var stmt=db.createStatement();var rows=stmt.executeQuery("select version from flyway_schema_history where success=true and version is not null order by installed_rank desc limit 1")) {
            if(!rows.next() || !("V"+rows.getString(1)).equals(release.getProperty("schema.version")))throw new SQLException("Restored database schema mismatch");
        }
    }
    private static void maintenance(Connection db,UUID id) throws SQLException {
        try(var stmt=db.prepareStatement("select set_config('tpv.transfer.maintenance_id',?,true)")){stmt.setString(1,id.toString());stmt.execute();}
    }
    private void restore(Path dump) throws Exception {
        String url=env("TPV_DB_URL").replaceFirst("^jdbc:","");
        var process=new ProcessBuilder(System.getenv().getOrDefault("TPV_PG_RESTORE_COMMAND","pg_restore"),"--exit-on-error","--single-transaction","--no-owner","--no-privileges","--no-password","--username="+env("TPV_DB_USERNAME"),"--dbname="+url,dump.toAbsolutePath().toString());
        process.environment().put("PGPASSWORD",env("TPV_DB_PASSWORD"));process.redirectErrorStream(true);
        var child=process.start();try(var input=child.getInputStream()){input.transferTo(OutputStream.nullOutputStream());}
        if(child.waitFor()!=0)throw new IOException("pg_restore failed; destination remains blocked");
    }
    private void requireEmptyDatabase() throws Exception {
        try(var db=database();var stmt=db.createStatement();var rows=stmt.executeQuery("select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema') and table_type='BASE TABLE'")) {
            if(!rows.next() || rows.getLong(1)!=0)throw new IllegalStateException("Destination database must be empty");
        }
    }
    private void requireStopped() throws Exception {
        int port=Integer.parseInt(System.getenv().getOrDefault("TPV_BACKEND_PORT","8080"));
        try(var socket=new Socket()) {socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),port),400);throw new IllegalStateException("Stop backend before transfer maintenance");}
        catch(ConnectException expected) { /* Stopped. */ }
    }
    private static Connection database() throws SQLException {return DriverManager.getConnection(env("TPV_DB_URL"),env("TPV_DB_USERNAME"),env("TPV_DB_PASSWORD"));}
    private static String serviceAccount(String name) {
        String account=env(name);
        com.sun.jna.platform.win32.Advapi32Util.getAccountByName(account);
        return account;
    }
    private InstallationIdentity destinationIdentity() throws Exception {
        if(!Files.isRegularFile(directory.resolve("destination-identity/installation-private.dpapi"),LinkOption.NOFOLLOW_LINKS))throw new IOException("Persisted destination identity missing");
        return new InstallationIdentityStore(directory.resolve("destination-identity"),PROTECTOR).loadOrCreate();
    }
    private JsonNode inspect(UUID id,UUID installation,PrivateKey key) throws Exception {
        return post("/"+id+"/inspect",Map.of("installationId",installation,"destinationProof",TransferEnvelope.sign(key,"TPV-INSPECT-V1\n"+id+"\n"+installation)),null);
    }
    private JsonNode post(String route,Object body,String token) throws Exception {
        URI base=URI.create(env("TPV_LICENSE_SAAS_URL").replaceAll("/+$",""));
        if(!"https".equalsIgnoreCase(base.getScheme()) || base.getUserInfo()!=null)throw new IOException("HTTPS SaaS required");
        var request=HttpRequest.newBuilder(URI.create(base+"/api/v1/server-transfers"+route)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
        if(token!=null)request.header("X-TPV-Installation-Token",token);
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        var response=client.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IOException("SaaS transfer rejected or unavailable: "+response.statusCode());
        return JSON.readTree(response.body());
    }
    private static void requireCentral(JsonNode value,UUID transfer,UUID installation,String state,String backup,String capsule,String fingerprint) throws IOException {
        if(!transfer.toString().equals(text(value,"transferId")) || !installation.toString().equals(text(value,"installationId")) || !state.equals(text(value,"state")) || !backup.equals(text(value,"backupSha256")) || !capsule.equals(text(value,"capsuleSha256")) || !fingerprint.equals(text(value,"destinationKeyFingerprint")))throw new IOException("Central transfer proof mismatch");
    }
    private JsonNode journal() throws IOException {
        var path=directory.resolve("journal.json");return Files.exists(path)?JSON.readTree(readBounded(path,128*1024)):JSON.valueToTree(Map.of("state","NONE"));
    }
    private void saveJournal(UUID id,UUID installation,String state,Path backup,String digest,String capsule,String fingerprint) throws IOException {
        write(directory.resolve("journal.json"),JSON.writeValueAsBytes(Map.of("transferId",id,"installationId",installation,"state",state,"backup",backup.toAbsolutePath().toString(),"backupSha256",digest,"capsuleSha256",capsule,"destinationKeyFingerprint",fingerprint)));
    }
    private static JsonNode readManifest(Path archive) throws IOException {
        try(var zip=new ZipFile(archive.toFile())) {
            var entry=zip.getEntry("manifest.json");if(entry==null || entry.getSize()>16*1024*1024)throw new IOException("Transfer manifest missing or oversized");
            try(var input=zip.getInputStream(entry)){byte[] bytes=input.readNBytes(16*1024*1024+1);if(bytes.length>16*1024*1024)throw new IOException("Manifest too large");return JSON.readTree(bytes);}
        }
    }
    private static void requireEmptyTree(Path path) throws IOException {
        safe(path);if(!Files.exists(path))return;try(var entries=Files.list(path)){if(entries.findAny().isPresent())throw new IOException("Destination file directory must be empty");}
    }
    private static void requireSpace(Path directory,long bytes) throws IOException {
        Path parent=directory;while(!Files.exists(parent))parent=parent.getParent();if(parent==null || Files.getFileStore(parent).getUsableSpace()<bytes)throw new IOException("Insufficient destination free space");
    }
    private static byte[] readBounded(Path path,int limit) throws IOException {
        safe(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>limit)throw new IOException("Missing or oversized transfer file");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Oversized transfer file");return bytes;}
    }
    private static Path safe(Path raw) throws IOException {
        Path path=raw.toAbsolutePath().normalize();
        for(Path part=path;part!=null;part=part.getParent())if(Files.exists(part,LinkOption.NOFOLLOW_LINKS)) {
            var attributes=Files.readAttributes(part,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(attributes.isSymbolicLink() || attributes.isOther())throw new IOException("Links/reparse points are not allowed in maintenance paths");
        }
        return path;
    }
    private static void write(Path path,byte[] bytes) throws IOException {
        safe(path);Files.createDirectories(path.getParent());var temporary=Files.createTempFile(path.getParent(),".transfer-",".tmp");
        try {
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.write(ByteBuffer.wrap(bytes));channel.force(true);}
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temporary);}
    }
    private static String hash(Path file) throws Exception {
        safe(file);var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[64*1024];for(int n;(n=input.read(buffer))!=-1;)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());
    }
    private static Path sibling(Path path,String suffix){return path.resolveSibling(path.getFileName()+suffix);}
    private static byte[] secret(Map<String,byte[]> secrets,String key) throws IOException {var value=secrets.get(key);if(value==null || value.length==0)throw new IOException("Transfer secret missing: "+key);return value;}
    private static UUID uuid(JsonNode node,String key)throws IOException {return UUID.fromString(text(node,key));}
    private static String text(JsonNode node,String key)throws IOException {var value=node.path(key);if(!value.isTextual() || value.textValue().isBlank())throw new IOException("Transfer field missing: "+key);return value.textValue();}
    private static String env(String key){var value=System.getenv(key);if(value==null || value.isBlank())throw new IllegalStateException("Required maintenance environment: "+key);return value;}
    private static String required(Map<String,String> args,String key){var value=args.get(key);if(value==null || value.isBlank())throw new IllegalArgumentException("Missing --"+key);return value;}
    private static Map<String,String> parse(String[] args){var result=new HashMap<String,String>();if(args.length%2!=0)throw new IllegalArgumentException("Named argument pairs required");for(int i=0;i<args.length;i+=2){if(!args[i].startsWith("--") || result.put(args[i].substring(2),args[i+1])!=null)throw new IllegalArgumentException("Invalid argument");}return result;}
}
