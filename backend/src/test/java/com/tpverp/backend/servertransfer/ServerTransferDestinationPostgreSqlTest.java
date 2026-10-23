package com.tpverp.backend.servertransfer;

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

/**
 * Opt-in Windows integration test. TPV_BACKUP_TEST_DB_URL must name a disposable PostgreSQL cluster
 * whose test user can CREATE DATABASE; TPV_BACKUP_TEST_PG_BIN supplies pg_dump/pg_restore.
 * Every database is newly created and dropped here. TLS trust is scoped to the CLI subprocess.
 * This exercises real machine DPAPI and the destination CLI on one Windows host, with a simulated
 * central authority; it is deliberately not evidence of a two-machine production transfer.
 */
class ServerTransferDestinationPostgreSqlTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final char[] TLS_PASSWORD = "disposable-tls-fixture".toCharArray();
    @TempDir Path temporary;

    @Test
    void realCliRestoresFrozenSnapshotAndFilesAndRepeatedReceiveDoesNotChangeCatalogue() throws Exception {
        var database = Database.resolve();
        try (var source = database.create(); var destination = database.create(); var central = new Central(temporary.resolve("tls"))) {
            var target = new Destination(destination, temporary.resolve("destination"), central);
            JsonNode request = target.success("request");
            assertThat(request.path("state").asText()).isEqualTo("DESTINATION_READY");
            assertThat(Files.isRegularFile(target.directory.resolve("destination-identity/installation-private.dpapi"))).isTrue();
            var artifact = createArtifact(source, request, central, true);

            Path catalogue = target.backups.resolve("daily/000002.tpvb");
            Files.createDirectories(catalogue.getParent());
            byte[] existing = "unrelated existing catalogue bytes".getBytes(StandardCharsets.UTF_8);
            Files.write(catalogue, existing);
            var interrupted = target.run("receive", artifact.backup);
            assertThat(interrupted.exitCode).isNotZero();
            assertThat(Files.readAllBytes(catalogue)).containsExactly(existing);
            assertThat(JSON.readTree(Files.readAllBytes(target.directory.resolve("journal.json"))).path("state").asText())
                    .isEqualTo("DATABASE_RESTORED");
            try (var db = destination.open()) {
                assertThat(text(db, "select state from server_transfer")).isEqualTo("FROZEN");
                assertThat(text(db, "select result from ejecucion_backup where id='" + artifact.execution + "'")).isEqualTo("EN_CURSO");
            }
            Files.delete(catalogue); // Remove only this test's deliberate conflict before retrying.
            assertThat(target.success("receive", artifact.backup).path("state").asText()).isEqualTo("RECEIVED");
            assertThat(central.requests.get()).isGreaterThanOrEqualTo(2);
            assertThat(central.error.get()).isNull();
            assertThat(Files.readString(target.images.resolve("fixture/product-image.txt"))).isEqualTo("synthetic product image bytes");
            assertThat(Files.readString(target.templates.resolve("fixture/template.jrxml"))).isEqualTo("<jasperReport name=\"synthetic\"/>");
            assertThat(Files.readString(target.keys.resolve("server-transfer.freeze"))).isEqualTo(artifact.transfer.toString());
            var handoff = TransferSecretHandoff.read(target.keys);
            assertThat(handoff.path("phase").asText()).isEqualTo("RECEIVED");
            assertThat(handoff.path("secrets").path("identity.public").binaryValue()).containsExactly(artifact.identityPublic);
            assertThat(handoff.path("secrets").path("backup.brk").binaryValue()).containsExactly(artifact.brk);
            assertThat(Files.readAllBytes(catalogue)).containsExactly(Files.readAllBytes(artifact.backup));
            String beforeRetry;
            try (var db = destination.open()) {
                assertThat(text(db, "select state from server_transfer")).isEqualTo("RECEIVED");
                assertThat(text(db, "select public_key from instalacion")).isEqualTo(Base64.getEncoder().encodeToString(artifact.identityPublic));
                assertThat(text(db, "select result || ':' || phase from ejecucion_backup where id='" + artifact.execution + "'"))
                        .isEqualTo("EXITO:COMPLETED");
                assertThat(text(db, "select metadata->>'path' from ejecucion_backup where id='" + artifact.execution + "'"))
                        .isEqualTo(catalogue.toAbsolutePath().toString());
                assertThat(text(db, "select metadata->>'marker' from ejecucion_backup where sequence_number=1")).isEqualTo("preserve-history");
                assertThat(text(db, "select destino->>'path' from configuracion_backup")).isEqualTo(target.backups.toString());
                assertThat(text(db, "select backup_sequence from configuracion_backup")).isEqualTo("2");
                beforeRetry = text(db, "select json_agg(e order by sequence_number)::text from ejecucion_backup e");
                assertThatThrownBy(() -> execute(db, "update instalacion set referencia='SHOULD-NOT-WRITE'"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("55000"));
            }
            int inspectedBeforeRetry = central.requests.get();
            assertThat(target.success("receive", artifact.backup).path("state").asText()).isEqualTo("RECEIVED");
            assertThat(central.requests.get()).isEqualTo(inspectedBeforeRetry);
            try (var db = destination.open()) {
                assertThat(text(db, "select json_agg(e order by sequence_number)::text from ejecucion_backup e")).isEqualTo(beforeRetry);
            }
            assertThat(Files.readAllBytes(catalogue)).containsExactly(Files.readAllBytes(artifact.backup));
            String fiscalPath = central.fiscal.company + "/" + central.fiscal.id + "/private-key.dpapi";
            var fiscalStore = VerifactuCertificateSecretStore.forTransfer(target.keys.resolve("fiscal"),
                    new WindowsMachineDpapiSecretProtector(), serviceAccount());
            // The production store verifies exact protected NTFS ACLs on every directory and file.
            byte[] restoredFiscalKey = fiscalStore.read(fiscalPath);
            assertThat(restoredFiscalKey).containsExactly(central.fiscal.privateKey);
            new VerifactuCertificateValidator(Clock.systemUTC()).verifyTransferred(
                    central.fiscal.chain, restoredFiscalKey, central.fiscal.fingerprint, true);
            assertThat(Files.readAllBytes(target.keys.resolve("fiscal").resolve(fiscalPath)))
                    .isNotEqualTo(restoredFiscalKey);
            Arrays.fill(restoredFiscalKey, (byte) 0);

            // A valid DPAPI envelope with the wrong RSA key must not reach central activation.
            var wrongKey = KeyPairGenerator.getInstance("RSA"); wrongKey.initialize(3072);
            fiscalStore.write(central.fiscal.company, central.fiscal.id, wrongKey.generateKeyPair().getPrivate().getEncoded());
            int requestsBeforeActivation = central.requests.get();
            var rejectedActivation = target.run("activate");
            assertThat(rejectedActivation.exitCode).isNotZero();
            assertThat(rejectedActivation.output).contains("Transferred fiscal private key does not match its certificate");
            assertThat(central.requests.get()).isEqualTo(requestsBeforeActivation);
            assertThat(central.error.get()).isNull();
            assertThat(JSON.readTree(Files.readAllBytes(target.directory.resolve("journal.json"))).path("state").asText())
                    .isEqualTo("RECEIVED");
            fiscalStore.write(central.fiscal.company, central.fiscal.id, central.fiscal.privateKey);
            try (var db = source.open()) {
                assertThat(text(db, "select state from server_transfer")).isEqualTo("FROZEN");
                assertThat(text(db, "select result from ejecucion_backup where id='" + artifact.execution + "'")).isEqualTo("EN_CURSO");
            }
        }
    }

    @Test
    void ordinaryBackupWithValidEnvelopeAndMatchingCentralHashesIsRejectedBeforeRestoration() throws Exception {
        var database = Database.resolve();
        try (var source = database.create(); var destination = database.create(); var central = new Central(temporary.resolve("tls"))) {
            var target = new Destination(destination, temporary.resolve("destination"), central);
            var artifact = createArtifact(source, target.success("request"), central, false);
            var rejected = target.run("receive", artifact.backup);
            assertThat(rejected.exitCode).isNotZero();
            assertThat(rejected.output).contains("Transfer field missing: transferId");
            assertThat(central.requests.get()).isEqualTo(1);
            assertThat(central.error.get()).isNull();
            try (var db = destination.open()) {
                assertThat(text(db, "select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'"))
                        .isEqualTo("0");
            }
            assertThat(Files.exists(target.backups.resolve("daily/000002.tpvb"))).isFalse();
            assertThat(Files.exists(target.images.resolve("fixture/product-image.txt"))).isFalse();
            assertThat(Files.exists(target.keys.resolve(TransferSecretHandoff.FILE))).isFalse();
        }
    }

    private Artifact createArtifact(Database source, JsonNode request, Central central, boolean transferBackup) throws Exception {
        Properties release = new Properties();
        try (var input = getClass().getResourceAsStream("/META-INF/tpv-erp-release.properties")) { release.load(input); }
        FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                .dataSource(source.url, source.user, source.password)
                .target(release.getProperty("schema.version").replaceFirst("^V", "")).load().migrate();
        UUID installation = UUID.randomUUID(), configuration = UUID.randomUUID(), transfer = UUID.randomUUID(), execution = UUID.randomUUID();
        Path sourceKeys = Files.createDirectories(temporary.resolve("source-keys"));
        var protector = new WindowsMachineDpapiSecretProtector();
        var identity = new InstallationIdentityStore(sourceKeys, protector).loadOrCreate();
        var keyStore = new BackupKeyStore(sourceKeys, protector);
        keyStore.initializeV2("SYNTHETIC-RECOVERY-SECRET-123456789".toCharArray(), temporary.resolve("source-backups"));
        byte[] brk = keyStore.loadForScheduledBackup();
        String publicKey = Base64.getEncoder().encodeToString(identity.publicKey().getEncoded());
        String destinationPublic = request.path("destinationPublicKey").asText();
        String fingerprint = request.path("destinationKeyFingerprint").asText();
        try (var db = source.open()) {
            execute(db, "insert into instalacion(id,referencia,public_key,creada_en,demo_hasta) values ('" + installation
                    + "','CLI-TRANSFER-TEST','" + publicKey + "',now(),now()+interval '30 days')");
            seedFiscalCertificate(db, central.fiscal);
            execute(db, "insert into configuracion_backup(id,instalacion_id,destino) values ('" + configuration + "','"
                    + installation + "','{\"path\":\"synthetic-source\"}')");
            execute(db, "insert into ejecucion_backup(id,configuracion_id,iniciada_en,finalizada_en,result,phase,metadata) values ('"
                    + UUID.randomUUID() + "','" + configuration + "',now()-interval '1 day',now()-interval '1 day','EXITO','COMPLETED','{\"marker\":\"preserve-history\"}')");
            execute(db, "insert into ejecucion_backup(id,configuracion_id,iniciada_en,result,phase,origin,worker_token,heartbeat_at,lease_until) values ('"
                    + execution + "','" + configuration + "',now(),'EN_CURSO','ARCHIVING','TRANSFER','" + UUID.randomUUID() + "',now(),now()+interval '1 hour')");
            execute(db, "insert into server_transfer(id,installation_id,state,destination_public_key,destination_key_fingerprint) values ('"
                    + transfer + "','" + installation + "','PREPARED','" + destinationPublic + "','" + fingerprint + "')");
            execute(db, "update server_transfer set state='FROZEN'");
        }
        Path dump = temporary.resolve("database.backup"), archive = temporary.resolve("source.zip"), backup = temporary.resolve("source.tpvb");
        Path images = Files.createDirectories(temporary.resolve("source-images/fixture"));
        Path templates = Files.createDirectories(temporary.resolve("source-templates/fixture"));
        Files.writeString(images.resolve("product-image.txt"), "synthetic product image bytes");
        Files.writeString(templates.resolve("template.jrxml"), "<jasperReport name=\"synthetic\"/>");
        var dataSource = new DriverManagerDataSource(source.url, source.user, source.password);
        var commands = new PostgreSqlBackupCommands(source.url, source.user, source.password, pg("pg_dump"), pg("pg_restore"));
        try (var capture = new BackupSnapshotService(dataSource, Clock.systemUTC()).capture(commands, dump, installation, "integration-test")) {
            var manifest = new LinkedHashMap<String,Object>(capture.manifest());
            manifest.put("executionId", execution.toString()); manifest.put("sequenceNumber", 2L);
            manifest.put("origin", transferBackup ? "TRANSFER" : "MANUAL");
            manifest.put("releaseId", release.getProperty("release.id"));
            manifest.put("databaseSchemaVersion", release.getProperty("schema.version"));
            if (transferBackup) manifest.put("serverTransfer", Map.of("transferId", transfer.toString(), "installationId", installation.toString(),
                    "destinationKeyFingerprint", fingerprint));
            new BackupArchiveService().create(dump, images.getParent(), templates.getParent(), archive, manifest);
        }
        new BackupFileCrypto(1024 * 1024).encrypt(archive, backup, brk);
        var secrets = new LinkedHashMap<>(Map.of("identity.public", identity.publicKey().getEncoded(), "identity.private", identity.privateKey().getEncoded(),
                "backup.brk", brk, "backup.recovery", Files.readAllBytes(sourceKeys.resolve(BackupKeyStore.RECOVERY_FILE))));
        secrets.put("fiscal/" + central.fiscal.company + "/" + central.fiscal.id + "/private-key.dpapi", central.fiscal.privateKey);
        Path capsule = backup.resolveSibling(backup.getFileName() + ".capsule");
        Files.write(capsule, TransferEnvelope.seal(TransferEnvelope.publicKey(destinationPublic), JSON.writeValueAsBytes(secrets),
                ServerTransferSecrets.context(transfer, installation, fingerprint)));
        var status = Map.<String,Object>of("transferId", transfer.toString(), "installationId", installation.toString(), "state", "SEALED",
                "destinationKeyFingerprint", fingerprint, "backupSha256", hash(backup), "capsuleSha256", hash(capsule));
        central.authorize(status, TransferEnvelope.publicKey(destinationPublic));
        Files.write(backup.resolveSibling(backup.getFileName() + ".transfer.json"), JSON.writeValueAsBytes(status));
        return new Artifact(backup, transfer, execution, identity.publicKey().getEncoded(), brk);
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
            TransferSecretHandoff.requireProtectedDirectory(keys);
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
                    OfflineServerTransferCli.class.getName(), "--action", action, "--directory", directory.toString()));
            if (backup.length > 0) command.addAll(List.of("--backup", backup[0].toString()));
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
            server.createContext("/api/v1/server-transfers/", exchange -> {
                int code = 200; byte[] response;
                try {
                    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                    assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/v1/server-transfers/" + status.get("transferId") + "/inspect");
                    var body = JSON.readTree(exchange.getRequestBody());
                    assertThat(body.path("installationId").asText()).isEqualTo(status.get("installationId"));
                    var signature = Signature.getInstance("SHA256withRSA"); signature.initVerify(key);
                    signature.update(("TPV-INSPECT-V1\n" + status.get("transferId") + "\n" + status.get("installationId")).getBytes(StandardCharsets.UTF_8));
                    assertThat(signature.verify(Base64.getDecoder().decode(body.path("destinationProof").asText()))).isTrue();
                    requests.incrementAndGet(); response = JSON.writeValueAsBytes(status);
                } catch (Throwable failure) {
                    error.compareAndSet(null, failure); code = 400; response = "invalid test proof".getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
                exchange.close();
            });
            server.start();
        }
        void authorize(Map<String,Object> status, PublicKey key) { this.key = key; this.status = status; }
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
    private record Artifact(Path backup, UUID transfer, UUID execution, byte[] identityPublic, byte[] brk) {}
    private record Fiscal(UUID company, UUID id, byte[] chain, byte[] privateKey, String fingerprint,
                          java.time.Instant validFrom, java.time.Instant validUntil) {}
    private record Result(int exitCode, String output) {}
}
