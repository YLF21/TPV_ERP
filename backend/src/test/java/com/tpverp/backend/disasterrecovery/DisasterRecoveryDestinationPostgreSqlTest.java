package com.tpverp.backend.disasterrecovery;

import com.tpverp.backend.servertransfer.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.tpverp.backend.backup.application.*;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import com.tpverp.backend.shared.crypto.WindowsMachineDpapiSecretProtector;
import com.tpverp.backend.verifactu.TransferSecretDirectory;
import com.tpverp.backend.verifactu.TransferTlsFixtureAccess;
import com.tpverp.backend.verifactu.VerifactuCertificateSecretStore;
import com.tpverp.backend.verifactu.VerifactuCertificateValidator;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.*;
import java.security.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;


/** Real PostgreSQL/pg_restore, HTTPS, Machine DPAPI and CurrentUser DPAPI; authority is simulated.
 * The fixture-only runner supplies its synthetic secret in process memory, never via argv/environment.
 * Runtime fiscal/audit collaborators are independently tested; the actual startup finalizer coordinates them here.
 */
class DisasterRecoveryDestinationPostgreSqlTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final char[] TLS_PASSWORD="disposable-tls-fixture".toCharArray();
    private static final String SECRET="SYNTHETIC-RECOVERY-SECRET-123456789";
    @TempDir Path temporary;

    @Test void receiverResumesAfterCommitAndActivationAndBootstrapRecoverLostAcknowledgements()throws Exception {
        var database=Database.resolve();
        try(var source=database.create();var destination=database.create();var central=new Central(temporary.resolve("tls"))) {
            var target=new Destination(destination,temporary.resolve("destination"),central);
            var request=target.success("request");var artifact=createArtifact(source,request,central);
            Path catalogue=target.backups.resolve("daily/000002.tpvb");Files.createDirectories(catalogue.getParent());
            Files.writeString(catalogue,"unrelated conflict");
            assertThat(target.run("receive").exitCode).isNotZero();
            assertThat(Files.readString(catalogue)).isEqualTo("unrelated conflict");
            try(var db=destination.open()){assertThat(text(db,"select state from disaster_recovery")).isEqualTo("QUARANTINED");}
            Files.delete(catalogue);
            // A failure after DB commit and before local completion must resume, not pg_restore twice.
            assertThat(target.success("receive").path("state").asText()).isEqualTo("SUPPORT_REVIEW");
            assertThat(Files.readString(target.images.resolve("fixture/image.txt"))).isEqualTo("synthetic image");
            assertThat(Files.readString(target.templates.resolve("fixture/template.jrxml"))).isEqualTo("<jasperReport/>");
            assertThat(target.run("activate").exitCode).isNotZero();
            assertThat(central.activations.get()).isZero();
            try(var db=destination.open()) {
                assertThat(text(db,"select result||':'||phase from ejecucion_backup where sequence_number=2")).isEqualTo("EXITO:COMPLETED");
                assertThat(text(db,"select metadata->>'restoreSourceUnavailable' from ejecucion_backup where sequence_number=1")).isEqualTo("true");
                assertThat(text(db,"select state from cloud_backup_delivery")).isEqualTo("SENT");
                assertThatThrownBy(()->execute(db,"update terminal set nombre='must stay quarantined'")).isInstanceOf(SQLException.class);
            }
            central.status.put("state","ACTIVATION_APPROVED");
            assertThat(target.run("activate").exitCode).isNotZero(); // SaaS committed, transport lost its response.
            assertThat(central.activations.get()).isEqualTo(1);
            assertThat(target.success("activate").path("state").asText()).isEqualTo("ACTIVATED");
            String terminalHash;
            try(var db=destination.open()){
                terminalHash=text(db,"select credential_hash from terminal");
                assertThat(text(db,"select state from disaster_recovery")).isEqualTo("ACTIVE");
                assertThat(text(db,"select backup_sequence from configuracion_backup")).isEqualTo("9");
            }
            assertThat(target.success("activate").path("state").asText()).isEqualTo("ACTIVATED");
            try(var db=destination.open()){assertThat(text(db,"select credential_hash from terminal")).isEqualTo(terminalHash);}
            assertThat(central.activations.get()).isEqualTo(1);
            assertThat(target.success("bootstrap").path("state").asText()).isEqualTo("BOOTSTRAP_VERIFIED");
            assertThat(Files.exists(target.keys.resolve(DisasterRecoveryJournal.FREEZE))).isTrue();
            assertThat(target.run("finalize").exitCode).isNotZero(); // DB finalization committed, central ACK was lost.
            try(var db=destination.open()){assertThat(text(db,"select state from disaster_recovery")).isEqualTo("COMPLETE");}
            assertThat(Files.exists(target.keys.resolve(DisasterRecoveryJournal.FREEZE))).isTrue();
            assertThat(target.success("bootstrap").path("state").asText()).isEqualTo("BOOTSTRAP_VERIFIED");
            assertThat(target.success("finalize").path("state").asText()).isEqualTo("COMPLETE");
            assertThat(Files.exists(target.keys.resolve(DisasterRecoveryJournal.FREEZE))).isFalse();
            assertThat(Files.exists(target.keys.resolve(RecoverySecretHandoff.FILE))).isFalse();
            assertThat(central.error.get()).isNull();
            assertThat(central.activations.get()).isEqualTo(1);
            try(var db=source.open()){assertThat(text(db,"select result from ejecucion_backup where sequence_number=2")).isEqualTo("EN_CURSO");}
        }
    }

    public static final class CliFixture {
        public static void main(String[] args)throws Exception {
            String action=args[1];
            if(!Set.of("bootstrap","finalize").contains(action)){
                OfflineDisasterRecoveryCli.execute(args,()->SECRET.toCharArray());return;
            }
            Path keys=Path.of(System.getenv("TPV_KEY_DIRECTORY"));
            if("bootstrap".equals(action)) {
                var environment=new org.springframework.mock.env.MockEnvironment()
                    .withProperty("tpv.installation.key-directory",keys.toString())
                    .withProperty("tpv.license.saas-url",System.getenv("TPV_LICENSE_SAAS_URL"));
                new DisasterRecoveryEarlyGuard().postProcessEnvironment(environment,new org.springframework.boot.SpringApplication(Object.class));
                byte[] token=com.tpverp.backend.shared.crypto.SecretProtectorFactory.portableOrWindowsDpapi("")
                    .unprotect(Files.readAllBytes(keys.resolve("saas-installation-token.dpapi")));
                assertThat(new String(token,StandardCharsets.UTF_8)).isEqualTo("synthetic-new-central-token");
                Arrays.fill(token,(byte)0);
                var backupKeys=new BackupKeyStore(keys,com.tpverp.backend.shared.crypto.SecretProtectorFactory.portableOrWindowsDpapi(""));
                assertThat(backupKeys.exportWrappedRecoveryKey()).isEqualTo(Files.readAllBytes(keys.resolve(BackupKeyStore.RECOVERY_FILE)));
                System.out.println("{\"state\":\"BOOTSTRAP_VERIFIED\"}");return;
            }
            var dataSource=new DriverManagerDataSource(System.getenv("TPV_DB_URL"),System.getenv("TPV_DB_USERNAME"),System.getenv("TPV_DB_PASSWORD"));
            var runtime=org.mockito.Mockito.mock(com.tpverp.backend.verifactu.FiscalRuntimeGuardInitializer.class);
            var fiscal=org.mockito.Mockito.mock(com.tpverp.backend.backup.BackupRestoreFinalizationCommitService.class);
            var finalizer=new DisasterRecoveryStartupFinalizer(keys,new org.springframework.jdbc.core.JdbcTemplate(dataSource),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource),runtime,fiscal,System.getenv("TPV_LICENSE_SAAS_URL"),
                org.mockito.Mockito.mock(DisasterRecoveryLicenseReconciliation.class));
            finalizer.run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));
            org.mockito.Mockito.verify(runtime).run(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(fiscal).commit(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
            System.out.println("{\"state\":\"COMPLETE\"}");
        }
    }
    private Artifact createArtifact(Database source,JsonNode request,Central central)throws Exception {
        FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure()).dataSource(source.url,source.user,source.password).target("278").load().migrate();
        UUID installation=UUID.randomUUID(),configuration=UUID.randomUUID(),recovery=UUID.randomUUID(),execution=UUID.randomUUID(),store=UUID.randomUUID();
        Path sourceKeys=Files.createDirectories(temporary.resolve("source-keys"));
        var protector=new WindowsMachineDpapiSecretProtector();var identity=new InstallationIdentityStore(sourceKeys,protector).loadOrCreate();
        var keys=new BackupKeyStore(sourceKeys,protector);keys.initializeV2(SECRET.toCharArray(),temporary.resolve("source-backups"));
        byte[] brk=keys.loadForScheduledBackup();
        try(var db=source.open()) {
            execute(db,"insert into instalacion(id,referencia,public_key,creada_en,demo_hasta) values ('"+installation+"','RECOVERY-TEST','"+Base64.getEncoder().encodeToString(identity.publicKey().getEncoded())+"',timestamptz '2026-01-01 00:00:00+00',timestamptz '2026-01-31 00:00:00+00')");
            seedFiscalCertificate(db,central.fiscal);
            execute(db,"insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda) select '"+store+"',id,'Test',domicilio_fiscal,'fixture-address','Atlantic/Canary','EUR','es-ES','001' from empresa");
            execute(db,"insert into terminal(id,tienda_id,nombre,tipo,credential_hash,workstation_code) values ('"+UUID.randomUUID()+"','"+store+"','Server','SERVIDOR','original-hash','001')");
            execute(db,"insert into configuracion_backup(id,instalacion_id,destino) values ('"+configuration+"','"+installation+"','{\"path\":\"synthetic-source\"}')");
            execute(db,"insert into ejecucion_backup(id,configuracion_id,iniciada_en,finalizada_en,result,phase,metadata) values ('"+UUID.randomUUID()+"','"+configuration+"',now()-interval '1 day',now()-interval '1 day','EXITO','COMPLETED','{}')");
            execute(db,"insert into ejecucion_backup(id,configuracion_id,iniciada_en,result,phase,origin,worker_token,heartbeat_at,lease_until) values ('"+execution+"','"+configuration+"',now(),'EN_CURSO','ARCHIVING','MANUAL','"+UUID.randomUUID()+"',now(),now()+interval '1 hour')");
        }
        Properties release=new Properties();try(var input=getClass().getResourceAsStream("/META-INF/tpv-erp-release.properties")){release.load(input);}
        Path dump=temporary.resolve("database.backup"),archive=temporary.resolve("source.zip"),backup=temporary.resolve("source.tpvb");
        Path images=Files.createDirectories(temporary.resolve("source-images/fixture")),templates=Files.createDirectories(temporary.resolve("source-templates/fixture"));
        Files.writeString(images.resolve("image.txt"),"synthetic image");Files.writeString(templates.resolve("template.jrxml"),"<jasperReport/>");
        var material=new LinkedHashMap<String,byte[]>();material.put("identity.public",identity.publicKey().getEncoded());material.put("identity.private",identity.privateKey().getEncoded());
        material.put("fiscal/"+central.fiscal.company+"/"+central.fiscal.id+"/private-key.dpapi",central.fiscal.privateKey);
        var commands=new PostgreSqlBackupCommands(source.url,source.user,source.password,pg("pg_dump"),pg("pg_restore"));
        try(var capture=new BackupSnapshotService(new DriverManagerDataSource(source.url,source.user,source.password),Clock.systemUTC()).capture(commands,dump,installation,"test")) {
            var manifest=new LinkedHashMap<String,Object>(capture.manifest());manifest.put("executionId",execution.toString());manifest.put("sequenceNumber",2L);
            manifest.put("origin","MANUAL");manifest.put("releaseId",release.getProperty("release.id"));manifest.put("databaseSchemaVersion",release.getProperty("schema.version"));manifest.put("portableSecretsVersion",1);manifest.put("portableSecretsComplete",true);
            new BackupArchiveService().create(dump,images.getParent(),templates.getParent(),archive,manifest,RecoverySecretsCodec.seal(material,brk,installation,execution));
        }
        new BackupFileCrypto(1024*1024).encrypt(archive,backup,brk);central.artifact=Files.readAllBytes(backup);central.wrapped=Files.readAllBytes(sourceKeys.resolve(BackupKeyStore.RECOVERY_FILE));
        var descriptor=Map.<String,Object>of("backupId",execution,"installationId",installation,"sequenceNumber",2,"capturedAt",java.time.Instant.now().toString(),
            "sizeBytes",central.artifact.length,"sha256",hash(backup),"recoveryKeySha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(central.wrapped)));
        var status=new LinkedHashMap<String,Object>();status.put("recoveryId",recovery.toString());status.put("installationId",installation.toString());status.put("backupId",execution.toString());
        status.put("state","DOWNLOAD_AUTHORIZED");status.put("destinationKeyFingerprint",request.path("destinationKeyFingerprint").asText());status.put("backupSha256",hash(backup));status.put("sequenceFloor",9);status.put("backupDescriptor",descriptor);status.put("blockingReasons",List.of());
        central.authorize(status,TransferEnvelope.publicKey(request.path("destinationPublicKey").asText()));
        return new Artifact(backup,recovery,execution,identity.publicKey().getEncoded(),brk);
    }
    private static void protect(Path directory)throws Exception {
        var view=Files.getFileAttributeView(directory,AclFileAttributeView.class);var owner=Files.getOwner(directory);
        view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT).build()));
    }
    private final class Destination {
        final Database database;
        final Central central;
        final Path directory, keys, images, templates, backups;
        final int stoppedPort;
        Destination(Database database, Path base, Central central) throws Exception {
            this.database = database; this.central = central;
            directory = Files.createDirectories(base.resolve("maintenance"));
            keys = Files.createDirectories(base.resolve("keys"));
            // Replace inherited ACL entries: no unrelated user can read machine-DPAPI handoff material.
            var view = Files.getFileAttributeView(keys, AclFileAttributeView.class);
            var owner = Files.getOwner(keys);
            view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));
            RecoverySecretHandoff.requireProtectedDirectory(keys);
            protect(directory);
            images = base.resolve("images"); templates = base.resolve("templates"); backups = base.resolve("backups");
            try (var socket = new ServerSocket(0)) { stoppedPort = socket.getLocalPort(); }
        }
        JsonNode success(String action, Path... backup) throws Exception {
            var result = run(action, backup);
            assertThat(result.exitCode).as("CLI %s output: %s", action, result.output).isZero();
            String last = result.output.strip().lines().reduce((left, right) -> right).orElseThrow();
            return JSON.readTree(last);
        }
        Result run(String action, Path... backup) throws Exception {
            var command = new ArrayList<>(List.of(javaTool("java"), "-Djavax.net.ssl.trustStore=" + central.store,
                    "-Djavax.net.ssl.trustStorePassword=" + new String(TLS_PASSWORD), "-Djavax.net.ssl.trustStoreType=PKCS12",
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    CliFixture.class.getName(), "--action", action, "--directory", directory.toString()));
            if ("receive".equals(action)) command.addAll(List.of("--recovery-id", central.status.get("recoveryId").toString()));
            // Windows command lines are bounded; Maven's full dependency classpath can exceed that limit.
            Path arguments = directory.resolve("arguments-" + UUID.randomUUID() + ".txt");
            var encoded = new StringBuilder();
            for (String argument : command.subList(1, command.size())) {
                encoded.append('"').append(argument.replace("\\", "\\\\").replace("\"", "\\\""))
                        .append('"').append('\n');
            }
            Files.writeString(arguments, encoded);
            var process = new ProcessBuilder(command.getFirst(), "@" + arguments);
            var env = process.environment();
            env.put("TPV_KEY_DIRECTORY", keys.toString()); env.put("TPV_DB_URL", database.url);
            env.put("TPV_DB_USERNAME", database.user); env.put("TPV_DB_PASSWORD", database.password);
            env.put("TPV_BACKEND_PORT", Integer.toString(stoppedPort)); env.put("TPV_TRANSFER_PUBLIC_HOST", "localhost");
            env.put("TPV_TRANSFER_TLS_REPROVISIONED", "true"); env.put("TPV_TRANSFER_TLS_DIRECTORY", central.directory.toString());
            env.put("TPV_BACKEND_SERVICE_ACCOUNT", serviceAccount());
            env.put("TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT", serviceAccount());
            env.put("TPV_LICENSE_SAAS_URL", "https://localhost:" + central.server.getAddress().getPort());
            env.put("TPV_PRODUCT_IMAGE_DIRECTORY", images.toString()); env.put("TPV_DOCUMENT_TEMPLATE_DIRECTORY", templates.toString());
            env.put("TPV_VERIFACTU_SECRET_DIRECTORY", keys.resolve("fiscal").toString()); env.put("TPV_BACKUP_DIRECTORY", backups.toString());
            env.put("TPV_PG_RESTORE_COMMAND", pg("pg_restore"));
            // Tests never inherit ambient JVM injection, application configuration or another PG endpoint.
            env.remove("JAVA_TOOL_OPTIONS"); env.remove("JDK_JAVA_OPTIONS"); env.remove("_JAVA_OPTIONS");
            env.remove("PGSERVICE"); env.remove("PGSERVICEFILE"); env.remove("PGOPTIONS");
            return process(process, directory.resolve(action + "-" + UUID.randomUUID() + ".log"));
        }
    }

    private static final class Central implements AutoCloseable {
        final Path directory, store;
        final HttpsServer server;
        final Fiscal fiscal;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile Map<String,Object> status;
        volatile PublicKey key;
        volatile byte[] artifact,wrapped;
        volatile String credential;
        final AtomicInteger activations=new AtomicInteger(),acknowledgements=new AtomicInteger();
        volatile boolean loseActivationAck=true,loseBootstrapAck=true;
        Central(Path directory) throws Exception {
            this.directory = directory;
            var secureDirectory = new TransferSecretDirectory(directory, serviceAccount());
            store = directory.resolve("fixture.p12");
            var generated = process(new ProcessBuilder(javaTool("keytool"), "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
                    "-keysize", "3072", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2",
                    "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", new String(TLS_PASSWORD), "-noprompt"), directory.resolve("keytool.log"));
            assertThat(generated.exitCode).as(generated.output).isZero();
            var fiscalGenerated = process(new ProcessBuilder(javaTool("keytool"), "-genkeypair", "-alias", "fiscal", "-keyalg", "RSA",
                    "-keysize", "3072", "-dname", "CN=Synthetic Fiscal Test", "-ext", "KU=digitalSignature", "-validity", "2",
                    "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", new String(TLS_PASSWORD), "-noprompt"), directory.resolve("keytool-fiscal.log"));
            assertThat(fiscalGenerated.exitCode).as(fiscalGenerated.output).isZero();
            var keyStore = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(store)) { keyStore.load(input, TLS_PASSWORD); }
            secureDirectory.write("certificate.pem", pem("CERTIFICATE", keyStore.getCertificate("fixture").getEncoded()).getBytes(StandardCharsets.US_ASCII));
            secureDirectory.write("private-key.pem", pem("PRIVATE KEY", keyStore.getKey("fixture", TLS_PASSWORD).getEncoded()).getBytes(StandardCharsets.US_ASCII));
            var certificate = (java.security.cert.X509Certificate) keyStore.getCertificate("fiscal");
            fiscal = new Fiscal(UUID.randomUUID(), UUID.randomUUID(), java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertPath(List.of(certificate)).getEncoded("PKCS7"), keyStore.getKey("fiscal", TLS_PASSWORD).getEncoded(),
                    HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())),
                    certificate.getNotBefore().toInstant(), certificate.getNotAfter().toInstant());
            // Expose only the localhost TLS key to the HTTPS key manager, never the fiscal fixture alias.
            keyStore.deleteEntry("fiscal");
            // Close the service ACL after provisioning so this test does not need an elevated token.
            TransferTlsFixtureAccess.protect(directory, serviceAccount());
            TransferSecretDirectory.tls(directory, serviceAccount()).verifyFile("private-key.pem");
            var keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManager.init(keyStore, TLS_PASSWORD);
            var context = SSLContext.getInstance("TLS"); context.init(keyManager.getKeyManagers(), null, null);
            server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(context));
            server.createContext("/api/v1/backup-recoveries/", exchange -> {
                int code=200;byte[] response;
                try {
                    String action=exchange.getRequestURI().getPath().substring(exchange.getRequestURI().getPath().lastIndexOf('/')+1);
                    var body=JSON.readTree(exchange.getRequestBody());
                    String proofContext="received".equals(action)?HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.path("report").toString().getBytes(StandardCharsets.UTF_8))):"";
                    var signature=Signature.getInstance("SHA256withRSA");signature.initVerify(key);
                    signature.update(("TPV-RECOVERY-V1\n"+status.get("recoveryId")+"\n"+action+"\n"+("inspect".equals(action)?"":status.get("backupSha256"))+"\n"+proofContext).getBytes(StandardCharsets.UTF_8));
                    assertThat(signature.verify(Base64.getDecoder().decode(body.path("destinationProof").asText()))).isTrue();
                    requests.incrementAndGet();
                    if("artifact".equals(action))response=artifact;
                    else if("wrapped-key".equals(action))response=wrapped;
                    else if("evidence".equals(action))response=JSON.writeValueAsBytes(Map.of("checkpoint","fixture-checkpoint","eventCount",0,"events",List.of()));
                    else if("received".equals(action)) {
                        assertThat(body.path("report").path("localSafetyReady").asBoolean()).isTrue();
                        assertThat(body.path("report").path("centralMatches").asBoolean()).isTrue();
                        status.put("state","SUPPORT_REVIEW");response=JSON.writeValueAsBytes(status);
                    } else if("activate".equals(action)) {
                        if(!Set.of("ACTIVATION_APPROVED","ACTIVATED").contains(status.get("state"))) {
                            code=409;response="support approval required".getBytes(StandardCharsets.UTF_8);
                        } else {
                            if(!"ACTIVATED".equals(status.get("state"))){activations.incrementAndGet();status.put("state","ACTIVATED");}
                            if(loseActivationAck){loseActivationAck=false;exchange.close();return;}
                            response=JSON.writeValueAsBytes(Map.of("status",status,"encryptedCredential",credential));
                        }
                    } else if("acknowledge".equals(action)) {
                        acknowledgements.incrementAndGet();
                        if(loseBootstrapAck){loseBootstrapAck=false;exchange.close();return;}
                        response=JSON.writeValueAsBytes(status);
                    } else response=JSON.writeValueAsBytes(status);
                }catch(Throwable failure){error.compareAndSet(null,failure);code=400;response="invalid test proof".getBytes(StandardCharsets.UTF_8);}
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(code,response.length);
                try(var output=exchange.getResponseBody()){output.write(response);}exchange.close();
            });
            server.start();
        }
        void authorize(Map<String,Object> status, PublicKey key)throws Exception {
            this.key=key;this.status=new LinkedHashMap<>(status);
            this.credential=Base64.getEncoder().encodeToString(TransferEnvelope.rsa(javax.crypto.Cipher.ENCRYPT_MODE,key).doFinal("synthetic-new-central-token".getBytes(StandardCharsets.UTF_8)));
        }
        @Override public void close() {
            server.stop(0);
            TransferTlsFixtureAccess.restoreForCleanup(directory, serviceAccount());
        }
    }

    private record Database(String url, String user, String password, String adminUrl, String name) implements AutoCloseable {
        static Database resolve() {
            assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Real machine DPAPI requires Windows");
            String url = System.getenv("TPV_BACKUP_TEST_DB_URL");
            assumeTrue(url != null && !url.isBlank(), "Explicit disposable TPV_BACKUP_TEST_DB_URL required");
            assumeTrue(System.getenv("TPV_BACKUP_TEST_PG_BIN") != null, "Explicit PostgreSQL test binaries required");
            // A trust-only ephemeral cluster can use this nonempty synthetic value; the CLI rejects empty configuration.
            String password = System.getenv().getOrDefault("TPV_BACKUP_TEST_DB_PASSWORD", "disposable-fixture-password");
            if (password.isBlank()) password = "disposable-fixture-password";
            return new Database(url, System.getenv().getOrDefault("TPV_BACKUP_TEST_DB_USER", "postgres"), password, url, null);
        }
        Database create() throws Exception {
            String name = "transfer_cli_" + UUID.randomUUID().toString().replace("-", "");
            try (var db = open()) { execute(db, "create database " + name); }
            var uri = URI.create(url.substring("jdbc:".length()));
            String child = "jdbc:" + new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), "/" + name, null, null);
            return new Database(child, user, password, url, name);
        }
        Connection open() throws SQLException { return DriverManager.getConnection(url, user, password); }
        @Override public void close() throws SQLException {
            if (name != null) try (var db = DriverManager.getConnection(adminUrl, user, password)) {
                execute(db, "drop database " + name + " with (force)");
            }
        }
    }

    private static Result process(ProcessBuilder builder, Path log) throws Exception {
        var child = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(120, TimeUnit.SECONDS)) {
            child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("Timed out subprocess: " + Files.readString(log));
        }
        return new Result(child.exitValue(), Files.readString(log));
    }
    private static void seedFiscalCertificate(Connection db, Fiscal fiscal) throws SQLException {
        UUID role = UUID.randomUUID(), user = UUID.randomUUID();
        execute(db, "insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values ('" + fiscal.company
                + "','B12345678','Synthetic fiscal fixture','{\"linea1\":\"Test\",\"ciudad\":\"Test\",\"codigoPostal\":\"35001\",\"provincia\":\"Test\",\"pais\":\"ES\"}')");
        execute(db, "insert into rol(id,nombre,protegido) values ('" + role + "','ADMIN',true)");
        execute(db, "insert into usuario(id,nombre,user_name,password_hash,rol_id,protegido) values ('" + user
                + "','ADMIN','Synthetic admin','fixture-unused-hash','" + role + "',true)");
        try (var query = db.prepareStatement("""
                insert into certificado_verifactu(id,empresa_id,status,subject,issuer,serial_number,tax_id,
                    valid_from,valid_until,fingerprint,public_chain,secret_path,imported_at,imported_by)
                values (?,?,'ACTIVO','Synthetic fiscal test','Synthetic fiscal test','1','B12345678',?,?,?,?,?,now(),?)
                """)) {
            query.setObject(1, fiscal.id); query.setObject(2, fiscal.company);
            query.setTimestamp(3, java.sql.Timestamp.from(fiscal.validFrom)); query.setTimestamp(4, java.sql.Timestamp.from(fiscal.validUntil));
            query.setString(5, fiscal.fingerprint); query.setBytes(6, fiscal.chain);
            query.setString(7, fiscal.company + "/" + fiscal.id + "/private-key.dpapi"); query.setObject(8, user);
            query.executeUpdate();
        }
    }
    private static String serviceAccount() { return com.sun.jna.platform.win32.Advapi32Util.getUserName(); }
    private static String pg(String command) { return Path.of(System.getenv("TPV_BACKUP_TEST_PG_BIN"), command + ".exe").toString(); }
    private static String javaTool(String command) { return Path.of(System.getProperty("java.home"), "bin", command + ".exe").toString(); }
    private static String pem(String kind, byte[] bytes) { return "-----BEGIN " + kind + "-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(bytes) + "\n-----END " + kind + "-----\n"; }
    private static String hash(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private static String text(Connection db, String query) throws SQLException { try (var stmt = db.createStatement(); var rows = stmt.executeQuery(query)) { assertThat(rows.next()).isTrue(); return rows.getString(1); } }
    private static void execute(Connection db, String sql) throws SQLException { try (var statement = db.createStatement()) { statement.execute(sql); } }
    private record Artifact(Path backup, UUID recovery, UUID execution, byte[] identityPublic, byte[] brk) {}
    private record Fiscal(UUID company, UUID id, byte[] chain, byte[] privateKey, String fingerprint,
                          java.time.Instant validFrom, java.time.Instant validUntil) {}
    private record Result(int exitCode, String output) {}
}
