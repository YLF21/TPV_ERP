package com.tpverp.saas.backup;

import static com.tpverp.saas.SaasTestData.validCif;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasCompanyRepository;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.SaasLicense;
import com.tpverp.saas.license.SaasLicenseRepository;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.license.SaasStoreRepository;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import com.tpverp.saas.license.TokenHasher;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "tpv.saas.backup-vault.directory=target/test-cloud-vault"
})
@ActiveProfiles("test")
class CloudBackupVaultPostgreSqlTest {
    private static final String SCHEMA = "cloud_vault_" + UUID.randomUUID().toString().replace("-", "");
    @org.springframework.test.context.DynamicPropertySource
    static void databaseSchema(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @Autowired CloudBackupVaultService vault;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokens;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test void acknowledgementRejectsMissingAndCorruptStoredObject() throws Exception {
        Site site = site();
        byte[] object = "encrypted-backup-object".getBytes(StandardCharsets.UTF_8);
        byte[] wrapped = wrappedV2Fixture();
        var backup = descriptor(site, 1, object, wrapped);
        vault.begin(backup, wrapped, site.token());
        vault.upload(site.id(), backup.backupId(), site.token(), new ByteArrayInputStream(object));
        var file = java.nio.file.Path.of("target/test-cloud-vault", site.id().toString(), backup.backupId() + ".tpvb");
        java.nio.file.Files.write(file, new byte[object.length]);
        assertThatThrownBy(() -> vault.status(site.id(), backup.backupId(), site.token()))
                .hasMessageContaining("BACKUP_VAULT_OBJECT_CORRUPT");
        java.nio.file.Files.delete(file);
        assertThatThrownBy(() -> vault.status(site.id(), backup.backupId(), site.token()))
                .hasMessageContaining("BACKUP_VAULT_OBJECT_MISSING");
    }

    @Test void allPinnedCopiesBlockPromotionUntilOneIsReleased() throws Exception {
        Site site = site();
        byte[] object = "encrypted-backup-object".getBytes(StandardCharsets.UTF_8);
        byte[] wrapped = wrappedV2Fixture();
        var copies = new java.util.ArrayList<CloudBackupDescriptor>();
        UUID recovery = UUID.randomUUID();
        for (int i = 1; i <= 3; i++) {
            var backup = descriptor(site, i, object, wrapped);
            copies.add(backup);
            vault.begin(backup, wrapped, site.token());
            vault.upload(site.id(), backup.backupId(), site.token(), new ByteArrayInputStream(object));
            vault.pin(site.id(), backup.backupId(), recovery);
        }
        var replacement = descriptor(site, 4, object, wrapped);
        vault.begin(replacement, wrapped, site.token());
        assertThatThrownBy(() -> vault.upload(site.id(), replacement.backupId(), site.token(), new ByteArrayInputStream(object)))
                .hasMessageContaining("BACKUP_RETENTION_PINNED");
        assertThat(vault.status(site.id(), replacement.backupId(), site.token()).state()).isEqualTo("PENDING");
        assertThat(vault.available(site.id(), site.token())).containsExactlyInAnyOrderElementsOf(
                copies.stream().map(CloudBackupDescriptor::backupId).toList());
        vault.unpin(site.id(), copies.get(0).backupId(), UUID.randomUUID());
        assertThatThrownBy(() -> vault.upload(site.id(), replacement.backupId(), site.token(), new ByteArrayInputStream(object)))
                .hasMessageContaining("BACKUP_RETENTION_PINNED");
        vault.unpin(site.id(), copies.get(0).backupId(), recovery);
        vault.upload(site.id(), replacement.backupId(), site.token(), new ByteArrayInputStream(object));
        assertThat(vault.available(site.id(), site.token())).containsExactlyInAnyOrder(
                copies.get(1).backupId(), copies.get(2).backupId(), replacement.backupId());
    }

    @Test void rolledBackPromotionKeepsThreeOriginalObjectsAndCanBeRetried() throws Exception {
        Site site = site();
        byte[] object = "encrypted-backup-object".getBytes(StandardCharsets.UTF_8);
        byte[] wrapped = wrappedV2Fixture();
        var copies = new java.util.ArrayList<CloudBackupDescriptor>();
        for (int i = 1; i <= 3; i++) {
            var backup = descriptor(site, i, object, wrapped);
            copies.add(backup);
            vault.begin(backup, wrapped, site.token());
            vault.upload(site.id(), backup.backupId(), site.token(), new ByteArrayInputStream(object));
        }
        var replacement = descriptor(site, 4, object, wrapped);
        vault.begin(replacement, wrapped, site.token());
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(transaction -> {
            try {
                vault.upload(site.id(), replacement.backupId(), site.token(), new ByteArrayInputStream(object));
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            transaction.setRollbackOnly();
            return null;
        });
        vault.purgeEvicted();
        assertThat(vault.status(site.id(), replacement.backupId(), site.token()).state()).isEqualTo("PENDING");
        assertThat(vault.available(site.id(), site.token())).containsExactlyInAnyOrderElementsOf(
                copies.stream().map(CloudBackupDescriptor::backupId).toList());
        for (var backup : copies) {
            try (var stream = vault.openVerified(site.id(), backup.backupId())) {
                assertThat(stream.readAllBytes()).isEqualTo(object);
            }
        }
        assertThat(vault.upload(site.id(), replacement.backupId(), site.token(), new ByteArrayInputStream(object)).state())
                .isEqualTo("AVAILABLE");
        assertThat(vault.available(site.id(), site.token())).containsExactlyInAnyOrder(
                copies.get(1).backupId(), copies.get(2).backupId(), replacement.backupId());
    }

    @Test void concurrentUploadsKeepExactlyTheNewestThreeVerifiedCopies() throws Exception {
        Site site = site();
        byte[] object = "encrypted-backup-object".getBytes(StandardCharsets.UTF_8);
        byte[] wrapped = wrappedV2Fixture();
        var copies = new java.util.ArrayList<CloudBackupDescriptor>();
        for (int i = 1; i <= 7; i++) {
            var backup = descriptor(site, i, object, wrapped);
            copies.add(backup);
            vault.begin(backup, wrapped, site.token());
            if (i <= 3) vault.upload(site.id(), backup.backupId(), site.token(), new ByteArrayInputStream(object));
        }
        var ready = new java.util.concurrent.CountDownLatch(4);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (var backup : copies.subList(3, 7)) futures.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                vault.upload(site.id(), backup.backupId(), site.token(), new ByteArrayInputStream(object));
                assertThat(vault.available(site.id(), site.token())).hasSize(3);
                return null;
            }));
            assertThat(ready.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally { start.countDown(); executor.shutdownNow(); }
        assertThat(vault.available(site.id(), site.token())).containsExactlyInAnyOrderElementsOf(
                copies.subList(4, 7).stream().map(CloudBackupDescriptor::backupId).toList());
        for (var backup : copies.subList(4, 7)) {
            try (var stream = vault.openVerified(site.id(), backup.backupId())) {
                assertThat(stream.readAllBytes()).isEqualTo(object);
            }
        }
    }

    @Test void tenantIsolationHashValidationIdempotencyAndRetention() throws Exception {
        Site first = site();
        Site second = site();
        byte[] object = "encrypted-backup-object".getBytes(StandardCharsets.UTF_8);
        byte[] wrapped = wrappedV2Fixture();
        CloudBackupDescriptor one = descriptor(first, 1, object, wrapped);
        assertThatThrownBy(() -> vault.begin(one, wrapped, second.token())).hasMessageContaining("401");
        assertThatThrownBy(() -> vault.begin(one, "wrong".getBytes(StandardCharsets.UTF_8), first.token()))
                .hasMessageContaining("BACKUP_RECOVERY_KEY_HASH_MISMATCH");
        assertThat(vault.begin(one, wrapped, first.token()).state()).isEqualTo("PENDING");
        assertThat(vault.begin(one, wrapped, first.token()).state()).isEqualTo("PENDING");
        assertThatThrownBy(() -> vault.upload(first.id(), one.backupId(), first.token(),
                new ByteArrayInputStream(java.util.Arrays.copyOf(object, 5))))
                .hasMessageContaining("BACKUP_HASH_MISMATCH");
        assertThat(vault.status(first.id(), one.backupId(), first.token()).state()).isEqualTo("PENDING");
        assertThatThrownBy(() -> vault.upload(first.id(), one.backupId(), first.token(),
                new ByteArrayInputStream("wrong-payload".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("BACKUP_HASH_MISMATCH");
        assertThat(vault.status(first.id(), one.backupId(), first.token()).state()).isEqualTo("PENDING");
        assertThat(vault.upload(first.id(), one.backupId(), first.token(), new ByteArrayInputStream(object)).state())
                .isEqualTo("AVAILABLE");
        assertThat(vault.upload(first.id(), one.backupId(), first.token(), new ByteArrayInputStream(object)).state())
                .isEqualTo("AVAILABLE");
        assertThat(vault.openVerified(first.id(), one.backupId()).readAllBytes()).isEqualTo(object);
        assertThatThrownBy(() -> vault.status(first.id(), one.backupId(), second.token()))
                .hasMessageContaining("401");
        assertThat(vault.available(second.id(), second.token())).isEmpty();

        CloudBackupDescriptor two = descriptor(first, 2, object, wrapped);
        CloudBackupDescriptor three = descriptor(first, 3, object, wrapped);
        CloudBackupDescriptor four = descriptor(first, 4, object, wrapped);
        for (var next : new CloudBackupDescriptor[] {two, three}) {
            vault.begin(next, wrapped, first.token());
            vault.upload(first.id(), next.backupId(), first.token(), new ByteArrayInputStream(object));
        }
        vault.begin(four, wrapped, first.token());
        assertThatThrownBy(() -> vault.upload(first.id(), four.backupId(), first.token(),
                new ByteArrayInputStream(new byte[object.length]))).hasMessageContaining("BACKUP_HASH_MISMATCH");
        assertThat(vault.available(first.id(), first.token())).containsExactlyInAnyOrder(one.backupId(), two.backupId(), three.backupId());
        vault.pin(first.id(), one.backupId(), UUID.randomUUID());
        vault.upload(first.id(), four.backupId(), first.token(), new ByteArrayInputStream(object));
        assertThat(vault.available(first.id(), first.token())).containsExactlyInAnyOrder(one.backupId(), three.backupId(), four.backupId());
        assertThat(vault.status(first.id(), two.backupId(), first.token()).state()).isEqualTo("EVICTED");
        assertThat(vault.maxSequenceNumber(first.id())).isEqualTo(4);
        CloudBackupDescriptor pending = descriptor(first, 5, object, wrapped);
        vault.begin(pending, wrapped, first.token());
        assertThat(vault.maxSequenceNumber(first.id())).as("Recovery must not reuse an ID issued before an interrupted upload").isEqualTo(5);
        assertThat(vault.available(first.id(), first.token())).hasSize(3);

        String rotated = tokens.newToken();
        jdbc.update("update saas_installation set token_hash=? where installation_id=?", tokens.hash(rotated), first.id());
        assertThatThrownBy(() -> vault.upload(first.id(), four.backupId(), first.token(),
                new ByteArrayInputStream(object))).hasMessageContaining("401");
        assertThat(vault.upload(first.id(), four.backupId(), rotated, new ByteArrayInputStream(object)).state())
                .isEqualTo("AVAILABLE");
    }

    private Site site() {
        var company = companies.saveAndFlush(new SaasCompany(UUID.randomUUID(), "Vault test",
                validCif("B" + java.util.concurrent.ThreadLocalRandom.current().nextInt(1000000, 9999999) + "0"),
                TaxpayerType.SOCIEDAD, TaxRegime.IVA, Instant.now()));
        var store = stores.saveAndFlush(new SaasStore(UUID.randomUUID(), company, "001", "Store", "Atlantic/Canary", Instant.now()));
        var license = licenses.saveAndFlush(new SaasLicense(UUID.randomUUID(), company,
                "VAULT-" + UUID.randomUUID(), Instant.now().plusSeconds(86400), 1, 1, Instant.now()));
        String token = tokens.newToken();
        var installation = installations.saveAndFlush(new SaasInstallation(UUID.randomUUID(), company, store,
                license, UUID.randomUUID(), "VAULT-TEST", null, tokens.hash(token), Instant.now()));
        return new Site(installation.getInstallationId(), token);
    }
    private static CloudBackupDescriptor descriptor(Site site, long sequence, byte[] data, byte[] wrapped) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        String keyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wrapped));
        return new CloudBackupDescriptor(UUID.randomUUID(), site.id(), sequence, Instant.now().plusSeconds(sequence),
                "test", 1, 0, data.length, hash, keyHash, 2);
    }
    private static byte[] wrappedV2Fixture() {
        byte[] value = new byte[91];
        var buffer = java.nio.ByteBuffer.wrap(value);
        buffer.put(new byte[] {'T', 'P', 'V', 'R'}).put((byte) 2).putInt(600_000)
                .put((byte) 16).put((byte) 12).putInt(48);
        byte[] random = new byte[76];
        new java.security.SecureRandom().nextBytes(random);
        buffer.put(random);
        return value;
    }
    private record Site(UUID id, String token) { }
}
