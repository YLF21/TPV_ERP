package com.tpverp.saas.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.saas.sync.MemberReturnBalanceRecoveryCommand;
import com.tpverp.saas.sync.MemberReturnBalanceRecoveryProjector;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies on PostgreSQL that old direct receipt writers cannot race a new
 * alias writer into owning the same operation id in both tables.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class MemberReturnBalanceOperationOwnerPostgreSqlIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SaasMemberBalanceAccountRepository accounts;
    @Autowired MemberReturnBalanceRecoveryProjector recoveryProjector;

    @Test
    void directReceiptAndAliasCannotBothClaimOneOperationConcurrently() throws Exception {
        UUID operationId = UUID.randomUUID();
        UUID canonicalOperationId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        insertCanonicalReceipt(canonicalOperationId, companyId, storeId, memberId,
                sourceDocumentId, returnDocumentId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> direct = executor.submit(() -> runConcurrent(
                    ready, start, () -> insertReceipt(operationId, companyId, storeId,
                            memberId, sourceDocumentId)));
            Future<Boolean> alias = executor.submit(() -> runConcurrent(
                    ready, start, () -> insertAlias(operationId, canonicalOperationId)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(java.util.List.of(direct.get(10, TimeUnit.SECONDS),
                    alias.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }

        Integer directCount = jdbc.queryForObject(
                "select count(*) from saas_member_balance_retention_receipt where operation_id = ?",
                Integer.class, operationId);
        Integer aliasCount = jdbc.queryForObject(
                "select count(*) from saas_member_balance_retention_receipt_alias where operation_id = ?",
                Integer.class, operationId);
        Integer ownerCount = jdbc.queryForObject(
                "select count(*) from saas_member_balance_retention_operation_owner where operation_id = ?",
                Integer.class, operationId);
        assertThat(directCount + aliasCount).isEqualTo(1);
        assertThat(ownerCount).isEqualTo(1);
    }

    @Test
    void recoveryProjectorAndFinalizeLockProjectionBeforeSameMemberAccount() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID projectorOperationId = UUID.randomUUID();
        UUID finalizeOperationId = UUID.randomUUID();
        insertAccount(companyId, memberId);

        MemberReturnBalanceRecoveryCommand projectorCommand =
                new MemberReturnBalanceRecoveryCommand(
                        projectorOperationId, companyId, storeId, memberId, null, null,
                        sourceDocumentId, returnDocumentId, money("0.22"), "a".repeat(64),
                        java.util.List.of(new MemberReturnBalanceRecoveryCommand.Claim(
                                lotId, movementId, sourceDocumentId, money("0.22"), money("0.22"))));
        MemberReturnBalanceRecoveryCommand finalizeCommand =
                new MemberReturnBalanceRecoveryCommand(
                        finalizeOperationId, companyId, storeId, memberId, UUID.randomUUID(), "sale-b",
                        sourceDocumentId, returnDocumentId, money("0.22"), "b".repeat(64),
                        java.util.List.of(new MemberReturnBalanceRecoveryCommand.Claim(
                                lotId, movementId, sourceDocumentId, money("0.22"), money("0.22"))));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> projector = executor.submit(() -> runLockProtocol(
                    ready, start, projectorCommand));
            Future<?> finalize = executor.submit(() -> runLockProtocol(
                    ready, start, finalizeCommand));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            projector.get(10, TimeUnit.SECONDS);
            finalize.get(10, TimeUnit.SECONDS);
        }
    }

    private void runLockProtocol(
            CountDownLatch ready,
            CountDownLatch start,
            MemberReturnBalanceRecoveryCommand command) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ready.countDown();
            try {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            // This is the common protocol used by both the recovery projector
            // and finalizePreparedWallet: OPERATION plus all retention locks,
            // then the pessimistic member account row.
            recoveryProjector.lockForRecovery(command);
            assertThat(accounts.findForUpdate(command.companyId(), command.memberId()))
                    .isPresent();
        });
    }

    private void insertAccount(UUID companyId, UUID memberId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into saas_company(id, name, tax_id, taxpayer_type, tax_regime, created_at)
                values (?, ?, ?, 'NIF', 'IVA', ?)
                """, companyId, "Lock test", "L" + companyId.toString().replace("-", "").substring(0, 20), now);
        jdbc.update("""
                insert into saas_member_balance_account(
                    id, company_id, member_id, balance, points, updated_at, version)
                values (?, ?, ?, ?, ?, ?, 0)
                """, UUID.randomUUID(), companyId, memberId, money("10.00"), money("0.00"), now);
    }

    private boolean runConcurrent(
            CountDownLatch ready, CountDownLatch start, Runnable operation) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> operation.run());
            return true;
        } catch (RuntimeException expectedCollision) {
            return false;
        }
    }

    private void insertCanonicalReceipt(
            UUID operationId, UUID companyId, UUID storeId, UUID memberId,
            UUID sourceDocumentId, UUID returnDocumentId) {
        Timestamp now = Timestamp.from(Instant.now());
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                jdbc.update("""
                        insert into saas_member_balance_retention_receipt(
                            operation_id, company_id, store_id, member_id,
                            source_document_id, return_document_id, attributed_amount,
                            fingerprint, status, recovered_known, pending_missing,
                            spent_shortfall, created_at, updated_at, version)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 'COMMITTED', ?, ?, ?, ?, ?, 0)
                        """,
                        operationId, companyId, storeId, memberId, sourceDocumentId,
                        returnDocumentId, money("0.22"), "a".repeat(64), money("0.22"),
                        money("0"), money("0"), now, now));
    }

    private void insertReceipt(UUID operationId, UUID companyId, UUID storeId,
            UUID memberId, UUID sourceDocumentId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into saas_member_balance_retention_receipt(
                    operation_id, company_id, store_id, member_id,
                    source_document_id, return_document_id, attributed_amount,
                    fingerprint, status, recovered_known, pending_missing,
                    spent_shortfall, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, null, ?, ?, 'COMMITTED', ?, ?, ?, ?, ?, 0)
                """,
                operationId, companyId, storeId, memberId, sourceDocumentId,
                money("0.22"), "b".repeat(64), money("0.22"), money("0"),
                money("0"), now, now);
    }

    private void insertAlias(UUID operationId, UUID canonicalOperationId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into saas_member_balance_retention_receipt_alias(
                    operation_id, receipt_operation_id, created_at, version)
                values (?, ?, ?, 0)
                """, operationId, canonicalOperationId, now);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
