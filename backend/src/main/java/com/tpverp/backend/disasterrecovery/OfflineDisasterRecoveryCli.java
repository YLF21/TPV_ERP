package com.tpverp.backend.disasterrecovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.backup.application.*;
import com.tpverp.backend.shared.crypto.*;
import com.tpverp.backend.servertransfer.TransferEnvelope;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** No Spring/web/schedulers. All clear secrets stay in memory or an ACL-restricted machine-DPAPI handoff. */
public final class OfflineDisasterRecoveryCli {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final SecretProtector MACHINE=new WindowsMachineDpapiSecretProtector();
    private final Path directory,keys;
    private final Properties release=new Properties();
    private final RecoveryCentralClient central;
    private final java.util.function.Supplier<char[]> recoverySecret;
    private OfflineDisasterRecoveryCli(Path directory,java.util.function.Supplier<char[]> recoverySecret)throws Exception {
        this.directory=safe(directory);keys=safe(Path.of(env("TPV_KEY_DIRECTORY")));
        this.recoverySecret=recoverySecret;
        central=new RecoveryCentralClient(env("TPV_LICENSE_SAAS_URL"));
        try(var in=getClass().getResourceAsStream("/META-INF/tpv-erp-release.properties")){if(in==null)throw new IOException("Release metadata missing");release.load(in);}
    }
    public static void main(String[] arguments)throws Exception {
        execute(arguments,()->{
            var console=System.console();if(console==null)throw new IllegalStateException("Secure console required for recovery secret");
            return console.readPassword("Clave de recuperación (no se mostrará): ");
        });
    }
    // Package-private dependency seam: production always obtains the secret from the secure console above.
    static void execute(String[] arguments,java.util.function.Supplier<char[]> recoverySecret)throws Exception {
        var args=parse(arguments);var cli=new OfflineDisasterRecoveryCli(Path.of(required(args,"directory")),recoverySecret);
        String action=required(args,"action");if(!action.equals("status"))cli.requireStopped();
        RecoverySecretHandoff.requireProtectedDirectory(cli.directory);RecoverySecretHandoff.requireProtectedDirectory(cli.keys);
        try(var channel=java.nio.channels.FileChannel.open(cli.directory.resolve("maintenance.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
            if(lock==null)throw new IOException("Another recovery operation is running");
            try(var output=openResultFile(cli.directory,args.get("result-file"))) {
                Object result=switch(action){
                    case "request"->cli.request();case "status"->cli.status();
                    case "receive"->cli.receive(UUID.fromString(required(args,"recovery-id")));
                    case "activate"->cli.activate();default->throw new IllegalArgumentException("Unsupported recovery action");};
                if(output==null)System.out.println(JSON.writeValueAsString(result));
                else output.write(publicResult(result));
            }
        }
    }
    // Reserve a fresh private result before executing any action; stdout remains attached to the console.
    static OutputStream openResultFile(Path directory,String name)throws Exception {
        if(name==null)return null;
        if(!name.matches("maintenance-result-[0-9a-f]{32}\\.json"))throw new IllegalArgumentException("Invalid maintenance result name");
        RecoverySecretHandoff.requireProtectedDirectory(directory);
        return Files.newOutputStream(directory.resolve(name),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
    }
    static byte[] publicResult(Object result)throws IOException {
        JsonNode source=JSON.valueToTree(result);
        var output=JSON.createObjectNode();
        for(String name:List.of("state","transferId","recoveryId","destinationPublicKey","destinationKeyFingerprint"))
            if(source.hasNonNull(name))output.set(name,source.get(name));
        return JSON.writeValueAsBytes(output);
    }
    private JsonNode request()throws Exception {
        requireEmptyDatabase();requireEmptyTree(Path.of(env("TPV_PRODUCT_IMAGE_DIRECTORY")));requireEmptyTree(Path.of(env("TPV_DOCUMENT_TEMPLATE_DIRECTORY")));
        if(Files.exists(keys.resolve("installation-private.dpapi"),LinkOption.NOFOLLOW_LINKS))throw new IOException("Destination already has an installation");
        if(Files.exists(keys.resolve("server-transfer.freeze"),LinkOption.NOFOLLOW_LINKS))throw new IOException("Destination has a planned transfer");
        var identity=new InstallationIdentityStore(directory.resolve("destination-identity"),MACHINE).loadOrCreate();
        var result=JSON.createObjectNode().put("state","DESTINATION_READY").put("destinationPublicKey",Base64.getEncoder().encodeToString(identity.publicKey().getEncoded()))
                .put("destinationKeyFingerprint",TransferEnvelope.fingerprint(identity.publicKey()));
        write(directory.resolve("destination-request.json"),result);writeJournal(result);return result;
    }
    private JsonNode status()throws Exception {
        ObjectNode state=journal();
        if(Set.of("SUPPORT_REVIEW","RECEIVING","ACTIVATING").contains(state.path("state").asText()) && state.hasNonNull("recoveryId")) {
            try {var remote=central.call(UUID.fromString(state.path("recoveryId").asText()),"inspect","",destination().privateKey());
                String remoteState=remote.path("state").asText();
                // Lost activation ACK still needs local finalization; never start the service based only on SaaS state.
                if("ACTIVATING".equals(state.path("state").asText())&&"ACTIVATED".equals(remoteState))remoteState="ACTIVATION_APPROVED";
                state.put("state",remoteState);state.set("blockingReasons",remote.path("blockingReasons"));}
            catch(Exception unavailable){state.put("centralUnavailable",true);}
        }
        return publicStatus(state);
    }
    private JsonNode receive(UUID id)throws Exception {
        var previous=journal();
        if(previous.hasNonNull("recoveryId")&&!id.toString().equals(previous.path("recoveryId").asText()))throw new IOException("Another recovery owns this destination");
        if(Set.of("ACTIVATED","COMPLETE").contains(previous.path("state").asText()))return publicStatus(previous);
        var identity=destination();var authorized=central.call(id,"inspect","",identity.privateKey());
        if(!Set.of("DOWNLOAD_AUTHORIZED","SUPPORT_REVIEW","ACTIVATION_APPROVED").contains(authorized.path("state").asText()))throw new IOException("Recovery cannot receive in this state");
        var descriptor=authorized.path("backupDescriptor");UUID installation=UUID.fromString(descriptor.path("installationId").asText()),backupId=UUID.fromString(descriptor.path("backupId").asText());
        String digest=authorized.path("backupSha256").asText();
        if(!TransferEnvelope.fingerprint(identity.publicKey()).equals(authorized.path("destinationKeyFingerprint").asText()))throw new IOException("Recovery destination mismatch");
        boolean restored=hasRestored(id,installation);
        boolean resumeSnapshot=!restored&&"RECEIVING".equals(previous.path("state").asText())
                &&digest.equals(previous.path("backupSha256").asText())&&hasInstallationTable();
        if(!restored&&!resumeSnapshot){requireEmptyDatabase();requireEmptyTree(Path.of(env("TPV_PRODUCT_IMAGE_DIRECTORY")));requireEmptyTree(Path.of(env("TPV_DOCUMENT_TEMPLATE_DIRECTORY")));}
        long bytes=descriptor.path("sizeBytes").asLong(-1);
        if(bytes<1 || Files.getFileStore(directory).getUsableSpace()<Math.max(2L*1024*1024*1024,Math.multiplyExact(bytes,4)))throw new IOException("Insufficient recovery space");
        Path backup=directory.resolve("recovery.tpvb"),wrapped=directory.resolve("tpv-backup-recovery.key");
        download(id,"artifact",digest,identity.privateKey(),backup,bytes,digest);
        download(id,"wrapped-key",digest,identity.privateKey(),wrapped,1024*1024,descriptor.path("recoveryKeySha256").asText());
        char[] secret=recoverySecret.get();if(secret==null||secret.length==0)throw new IOException("Recovery secret required");
        byte[] brk=null;Map<String,byte[]> material=null;
        Path archive=directory.resolve("recovery-validated.zip"),stage=directory.resolve("staging-"+UUID.randomUUID());
        try {
            brk=new BackupKeyStore(keys,MACHINE).loadForRestore(wrapped,secret);Arrays.fill(secret,'\0');secret=null;
            new BackupFileCrypto(1024*1024).decrypt(backup,archive,brk);
            var manifest=manifest(archive);validateManifest(manifest,installation,backupId);
            material=RecoverySecretsCodec.open(new BackupArchiveService().readPortableCapsule(archive),brk,installation,backupId);
            DisasterRecoveryJournal.freeze(keys,id);
            ObjectNode state=JSON.createObjectNode().put("state","RECEIVING").put("recoveryId",id.toString()).put("installationId",installation.toString())
                    .put("backupId",backupId.toString()).put("backupSha256",digest).put("destinationKeyFingerprint",authorized.path("destinationKeyFingerprint").asText());
            state.set("manifest",manifest);state.set("descriptor",descriptor);writeJournal(state);
            var archives=new BackupArchiveService(500_000,1024L*1024*1024,10L*1024*1024*1024);
            clearEmptyStaging(stage);archives.extractToStaging(archive,stage.resolve("database.backup"),stage.resolve("images"),stage.resolve("templates"));
            if(!restored) {
                var latest=central.call(id,"inspect","",identity.privateKey());if(!Set.of("DOWNLOAD_AUTHORIZED","SUPPORT_REVIEW").contains(latest.path("state").asText()))throw new IOException("Recovery authorization changed");
                if(!resumeSnapshot)restore(stage.resolve("database.backup"));
                try(var db=database()) {
                    BackupSnapshotService.verifyRestoredSnapshot(db,manifest);validateInstallation(db,installation);
                    db.setAutoCommit(false);cancelRestoredTransfers(db);
                    try(var statement=db.prepareStatement("insert into disaster_recovery(id,installation_id,backup_id,backup_sha256,state) values (?,?,?,?,'QUARANTINED')")) {
                        statement.setObject(1,id);statement.setObject(2,installation);statement.setObject(3,backupId);statement.setString(4,digest);statement.executeUpdate();}
                    db.commit();
                }
            }
            // Repeat promotion on resume too: a crash after DB commit must not skip missing file trees.
            archives.replaceTrees(stage.resolve("images"),Path.of(env("TPV_PRODUCT_IMAGE_DIRECTORY")),stage.resolve("templates"),Path.of(env("TPV_DOCUMENT_TEMPLATE_DIRECTORY")));
            boolean safe=false;long unresolved;
            try(var db=database()) {
                db.setAutoCommit(false);maintenance(db,id);RecoveryLocalSecrets.install(db,material);repairCatalogue(db,manifest,descriptor,backup,digest);db.commit();
                try{RecoveryLocalSecrets.verify(db,material);safe=true;}catch(Exception incomplete){state.put("validationCode","RECOVERY_SECRETS_OR_TLS_REQUIRE_REVIEW");}
                unresolved=count(db,"select count(*) from payment_terminal_operation where status in ('PENDING','SENT','TIMEOUT','REVIEW_REQUIRED')");
            }
            var handoff=state.deepCopy();handoff.put("phase","RECEIVED");handoff.put("destinationPrivate",identity.privateKey().getEncoded());
            handoff.put("maintenanceDirectory",directory.toString());
            var secrets=handoff.putObject("secrets");material.forEach(secrets::put);secrets.put("backup.brk",brk);secrets.put("backup.recovery",Files.readAllBytes(wrapped));
            RecoverySecretHandoff.write(keys,handoff);
            var report=report(id,digest,identity.privateKey());report.put("backupSha256",digest).put("snapshotVerified",true).put("localSafetyReady",safe)
                    .put("portableSecretsComplete",safe).put("unresolvedPayments",unresolved);
            var remote=central.call(id,"received",digest,identity.privateKey(),hash(report.toString().getBytes(StandardCharsets.UTF_8)),report);
            state.put("state","SUPPORT_REVIEW");state.set("blockingReasons",remote.path("blockingReasons"));writeJournal(state);return publicStatus(state);
        }finally{if(secret!=null)Arrays.fill(secret,'\0');if(brk!=null)Arrays.fill(brk,(byte)0);if(material!=null)material.values().forEach(value->Arrays.fill(value,(byte)0));Files.deleteIfExists(archive);Files.deleteIfExists(stage.resolve("database.backup"));}
    }
    private JsonNode activate()throws Exception {
        var state=journal();UUID id=UUID.fromString(state.path("recoveryId").asText());String digest=state.path("backupSha256").asText();
        if("COMPLETE".equals(state.path("state").asText()))return publicStatus(state);
        if(!Set.of("SUPPORT_REVIEW","ACTIVATING","ACTIVATED").contains(state.path("state").asText()))throw new IOException("Destination not ready");
        var handoff=RecoverySecretHandoff.read(keys);Map<String,byte[]> material=JSON.convertValue(handoff.path("secrets"),new TypeReference<>(){});
        try(var db=database()) {RecoveryLocalSecrets.verify(db,material);}finally{material.values().forEach(value->Arrays.fill(value,(byte)0));}
        state.put("state","ACTIVATING");writeJournal(state);
        var activated=central.call(id,"activate",digest,destination().privateKey());var status=activated.path("status");
        if(!"ACTIVATED".equals(status.path("state").asText()) || !digest.equals(status.path("backupSha256").asText()))throw new IOException("Central activation mismatch");
        try(var db=database()) {
            db.setAutoCommit(false);maintenance(db,id);
            String phase;
            try(var query=db.prepareStatement("select state from disaster_recovery where id=? for update")){query.setObject(1,id);try(var rows=query.executeQuery()){if(!rows.next())throw new SQLException("Recovery state missing");phase=rows.getString(1);}}
            if("QUARANTINED".equals(phase)) {
            try(var statement=db.createStatement()) {
                statement.executeUpdate("update sesion set revocada_en=coalesce(revocada_en,now()),revoke_reason='DISASTER_RECOVERY',version=version+1 where revocada_en is null");
                statement.executeUpdate("update terminal_physical_binding set status='RELEASED',ended_at=now(),expires_at=null,version=version+1 where terminal_id in (select id from terminal where workstation_code='001') and status in ('PENDING','ACTIVE','DISABLED')");
            }
            try(var statement=db.prepareStatement("update terminal set current_binding_id=null,credential_hash=?,version=version+1 where workstation_code='001'")) {
                statement.setString(1,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode(UUID.randomUUID().toString()+UUID.randomUUID()));
                if(statement.executeUpdate()!=1)throw new SQLException("Server001 missing or ambiguous");
            }
            try(var statement=db.prepareStatement("update configuracion_backup set backup_sequence=greatest(backup_sequence,?),version=version+1")){statement.setLong(1,status.path("sequenceFloor").asLong());statement.executeUpdate();}
            try(var statement=db.prepareStatement("update disaster_recovery set state='ACTIVE' where id=?")){statement.setObject(1,id);statement.executeUpdate();}
            } else if(!Set.of("ACTIVE","COMPLETE").contains(phase))throw new SQLException("Recovery activation state conflict");
            db.commit();
        }
        handoff.put("phase","ACTIVATED");RecoverySecretHandoff.write(keys,handoff);state.put("state","ACTIVATED");writeJournal(state);return publicStatus(state);
    }
    private ObjectNode report(UUID id,String digest,PrivateKey key)throws Exception {
        String after="",checkpoint=null;long checked=0;boolean matches=true;long expected=-1;
        try(var db=database();var query=db.prepareStatement("select tipo_entidad,entidad_id,operacion,store_sequence,payload::text from sync_outbox where event_id=?")) {
            do {
                var page=central.call(id,"evidence",digest,key,after,null);String current=page.path("checkpoint").asText();
                if(checkpoint!=null&&!checkpoint.equals(current))matches=false;checkpoint=current;expected=page.path("eventCount").asLong();
                for(var event:page.path("events")) {
                    checked++;query.setObject(1,UUID.fromString(event.path("eventId").asText()));
                    try(var rows=query.executeQuery()) {
                        if(!rows.next()){matches=false;continue;}
                        var payload=JSON.readValue(rows.getString(5),new TypeReference<Map<String,Object>>(){});
                        String encoded=JSON.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(payload);
                        if(!event.path("payloadHash").asText().equals(hash(encoded.getBytes(StandardCharsets.UTF_8)))
                                || !event.path("entityType").asText().equals(rows.getString(1)) || !event.path("entityId").asText().equals(rows.getString(2))
                                || !event.path("operation").asText().equals(rows.getString(3))
                                || (event.has("storeSequence") && event.path("storeSequence").asLong()!=rows.getLong(4)))matches=false;
                    }
                }
                after=page.path("afterId").asText("");
            }while(!after.isBlank());
        }
        return JSON.createObjectNode().put("centralCheckpoint",checkpoint).put("centralEventCount",checked).put("centralMatches",matches&&checked==expected);
    }
    private void repairCatalogue(Connection db,JsonNode manifest,JsonNode descriptor,Path backup,String digest)throws Exception {
        UUID execution=UUID.fromString(manifest.path("executionId").asText());long sequence=manifest.path("sequenceNumber").asLong();
        Path root=safe(Path.of(env("TPV_BACKUP_DIRECTORY"))),daily=root.resolve("daily");Files.createDirectories(daily);Path target=daily.resolve("%06d.tpvb".formatted(sequence));
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!hash(target).equals(digest))throw new IOException("Backup sequence file conflict");}
        else {
            Path pending=Files.createTempFile(daily,".recovery-",".tmp");
            try {
                Files.copy(backup,pending,StandardCopyOption.REPLACE_EXISTING);
                if(!hash(pending).equals(digest))throw new IOException("Recovered backup copy digest mismatch");
                try(var channel=java.nio.channels.FileChannel.open(pending,StandardOpenOption.WRITE)){channel.force(true);}
                Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE);
            } finally {Files.deleteIfExists(pending);}
        }
        var metadata=JSON.createObjectNode().put("path",target.toString()).put("sizeBytes",Files.size(target)).put("verifiedAt",java.time.Instant.now().toString()).put("cloudRequired",true).put("cloudAlreadyStored",true);metadata.set("manifest",manifest);
        try(var statement=db.prepareStatement("update ejecucion_backup set metadata=jsonb_set(coalesce(metadata,'{}'::jsonb),'{restoreSourceUnavailable}','true'::jsonb) where id<>?")){statement.setObject(1,execution);statement.executeUpdate();}
        try(var statement=db.prepareStatement("update ejecucion_backup set result='EXITO',phase='COMPLETED',finalizada_en=greatest(iniciada_en,now()),lease_until=greatest(iniciada_en,now()),heartbeat_at=now(),metadata=?::jsonb,error_reason=null where id=? and sequence_number=?")) {
            statement.setString(1,metadata.toString());statement.setObject(2,execution);statement.setLong(3,sequence);if(statement.executeUpdate()!=1)throw new SQLException("Recovered execution missing");}
        try(var statement=db.prepareStatement("update configuracion_backup set destino=jsonb_set(coalesce(destino,'{}'::jsonb),'{path}',to_jsonb(cast(? as text))),backup_sequence=greatest(backup_sequence,?),version=version+1 where id=(select configuracion_id from ejecucion_backup where id=?)")) {
            statement.setString(1,root.toString());statement.setLong(2,sequence);statement.setObject(3,execution);statement.executeUpdate();}
        try(var statement=db.createStatement()){statement.executeUpdate("update cloud_backup_delivery set state='RETIRED',lease_token=null,lease_until=null,error_reason='SOURCE_SPOOL_NOT_RECOVERED',updated_at=now() where state<>'SENT'");}
        try(var statement=db.prepareStatement("insert into cloud_backup_delivery(backup_id,installation_id,descriptor,spool_sha256,state,uploaded_at) values (?,?,?::jsonb,?,'SENT',now()) on conflict(backup_id) do update set state='SENT',lease_token=null,lease_until=null,uploaded_at=now(),error_reason=null,descriptor=excluded.descriptor")) {
            statement.setObject(1,execution);statement.setObject(2,UUID.fromString(manifest.path("installationId").asText()));statement.setString(3,descriptor.toString());statement.setString(4,digest);statement.executeUpdate();}
    }
    private void validateManifest(JsonNode manifest,UUID installation,UUID backup)throws Exception {
        if(!installation.toString().equals(manifest.path("installationId").asText())||!backup.toString().equals(manifest.path("executionId").asText())
                ||manifest.path("portableSecretsVersion").asInt()!=1 ||!release.getProperty("release.id").equals(manifest.path("releaseId").asText())
                ||!release.getProperty("schema.version").equals(manifest.path("databaseSchemaVersion").asText()))throw new IOException("Recovery backup identity/release/schema mismatch");
    }
    private boolean hasRestored(UUID id,UUID installation)throws Exception {
        try(var db=database();var statement=db.createStatement();var exists=statement.executeQuery("select to_regclass('disaster_recovery')")) {
            exists.next();if(exists.getString(1)==null)return false;
            try(var query=db.prepareStatement("select installation_id from disaster_recovery where id=? and state in ('QUARANTINED','ACTIVE')")) {
                query.setObject(1,id);try(var row=query.executeQuery()){if(!row.next())return false;if(!installation.equals(row.getObject(1,UUID.class)))throw new IOException("Restored recovery identity mismatch");return true;}
            }
        }
    }
    private static void cancelRestoredTransfers(Connection db)throws Exception {
        try(var query=db.createStatement();var rows=query.executeQuery("select id from server_transfer where state in ('FROZEN','SEALED','ACTIVATED','RECEIVED')")) {
            var ids=new ArrayList<UUID>();while(rows.next())ids.add(rows.getObject(1,UUID.class));
            if(ids.size()>1)throw new IOException("Ambiguous transfer state");
            for(UUID id:ids)try(var config=db.prepareStatement("select set_config('tpv.transfer.maintenance_id',?,true)")){config.setString(1,id.toString());config.execute();}
        }
        try(var statement=db.createStatement()){statement.executeUpdate("update server_transfer set state='CANCELLED' where state in ('PREPARED','FROZEN','SEALED','ACTIVATED','RECEIVED')");}
    }
    static void maintenance(Connection db,UUID id)throws SQLException {try(var statement=db.prepareStatement("select set_config('tpv.recovery.maintenance_id',?,true)")){statement.setString(1,id.toString());statement.execute();}}
    private static void validateInstallation(Connection db,UUID id)throws Exception {try(var statement=db.createStatement();var rows=statement.executeQuery("select id from instalacion")){if(!rows.next()||!id.equals(rows.getObject(1,UUID.class))||rows.next())throw new IOException("Restored installation mismatch");}}
    private static long count(Connection db,String sql)throws SQLException {try(var statement=db.createStatement();var rows=statement.executeQuery(sql)){rows.next();return rows.getLong(1);}}
    private void download(UUID id,String action,String digest,PrivateKey key,Path target,long limit,String expected)throws Exception {
        Path tmp=directory.resolve(action+".download");
        try(var input=central.download(id,action,digest,key);var out=Files.newOutputStream(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer=new byte[64*1024];long total=0;int n;while((n=input.read(buffer))!=-1){total=Math.addExact(total,n);if(total>limit)throw new IOException("Recovery download exceeds size");out.write(buffer,0,n);}
        }catch(Exception failure){Files.deleteIfExists(tmp);throw failure;}
        if(!hash(tmp).equals(expected)){Files.deleteIfExists(tmp);throw new IOException("Recovery download hash mismatch");}
        Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private void restore(Path dump)throws Exception {
        var process=new ProcessBuilder(System.getenv().getOrDefault("TPV_PG_RESTORE_COMMAND","pg_restore"),"--exit-on-error","--single-transaction","--no-owner","--no-privileges","--no-password","--username="+env("TPV_DB_USERNAME"),"--dbname="+env("TPV_DB_URL").replaceFirst("^jdbc:",""),dump.toString());
        process.environment().put("PGPASSWORD",env("TPV_DB_PASSWORD"));process.redirectErrorStream(true);var child=process.start();try(var input=child.getInputStream()){input.transferTo(OutputStream.nullOutputStream());}if(child.waitFor()!=0)throw new IOException("Recovery database restore failed; destination stays quarantined");
    }
    private static void requireEmptyDatabase()throws Exception {try(var db=database()){if(count(db,"select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema') and table_type='BASE TABLE'")!=0)throw new IOException("Recovery requires an empty destination database");}}
    private static boolean hasInstallationTable()throws Exception {try(var db=database();var statement=db.createStatement();var rows=statement.executeQuery("select to_regclass('instalacion')")){rows.next();return rows.getString(1)!=null;}}
    private static void requireEmptyTree(Path path)throws Exception {if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))try(var files=Files.list(safe(path))){if(files.findAny().isPresent())throw new IOException("Recovery requires empty destination files");}}
    private static void clearEmptyStaging(Path path)throws Exception {if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Inspect/remove failed recovery staging before retrying an uncommitted restore");Files.createDirectories(path);}
    private void requireStopped()throws Exception {try(var socket=new Socket()){socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),Integer.parseInt(System.getenv().getOrDefault("TPV_BACKEND_PORT","8080"))),400);throw new IOException("Stop backend before recovery");}catch(ConnectException stopped){}}
    private InstallationIdentity destination()throws Exception {if(!Files.isRegularFile(directory.resolve("destination-identity/installation-private.dpapi"),LinkOption.NOFOLLOW_LINKS))throw new IOException("Destination identity missing; run request first");return new InstallationIdentityStore(directory.resolve("destination-identity"),MACHINE).loadOrCreate();}
    private ObjectNode journal()throws Exception {Path path=directory.resolve("disaster-recovery.json");if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return JSON.createObjectNode().put("state","NONE");if(Files.size(path)>16*1024*1024)throw new IOException("Recovery journal too large");return (ObjectNode)JSON.readTree(Files.readAllBytes(path));}
    private void writeJournal(ObjectNode value)throws Exception {write(directory.resolve("disaster-recovery.json"),value);}
    private static void write(Path path,JsonNode value)throws Exception {RecoverySecretHandoff.writeFile(path,JSON.writeValueAsBytes(value));}
    private static ObjectNode publicStatus(ObjectNode state) {var result=JSON.createObjectNode();for(String key:List.of("state","recoveryId","installationId","backupId","backupSha256","destinationKeyFingerprint","blockingReasons","centralUnavailable","validationCode"))if(state.has(key))result.set(key,state.get(key));return result;}
    private static JsonNode manifest(Path archive)throws Exception {try(var zip=new java.util.zip.ZipFile(archive.toFile())){var entry=zip.getEntry("manifest.json");if(entry==null)throw new IOException("Recovery manifest missing");try(var input=zip.getInputStream(entry)){byte[] bytes=input.readNBytes(16*1024*1024+1);if(bytes.length>16*1024*1024)throw new IOException("Recovery manifest too large");return JSON.readTree(bytes);}}}
    static Path safe(Path path)throws IOException {Path value=path.toAbsolutePath().normalize();for(Path current=value;current!=null;current=current.getParent())if(Files.exists(current,LinkOption.NOFOLLOW_LINKS)){var a=Files.readAttributes(current,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(a.isSymbolicLink()||a.isOther())throw new IOException("Recovery paths cannot contain links/reparse points");}return value;}
    private static String hash(Path path)throws Exception {var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
    private static String hash(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static Connection database()throws SQLException {return DriverManager.getConnection(env("TPV_DB_URL"),env("TPV_DB_USERNAME"),env("TPV_DB_PASSWORD"));}
    private static String env(String name){return RecoveryLocalSecrets.env(name);}
    private static String required(Map<String,String> args,String key){var value=args.get(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing --"+key);return value;}
    private static Map<String,String> parse(String[] args){var result=new HashMap<String,String>();if(args.length%2!=0)throw new IllegalArgumentException("Named argument pairs required");for(int i=0;i<args.length;i+=2)if(!args[i].startsWith("--")||result.put(args[i].substring(2),args[i+1])!=null)throw new IllegalArgumentException("Invalid argument");return result;}
}
