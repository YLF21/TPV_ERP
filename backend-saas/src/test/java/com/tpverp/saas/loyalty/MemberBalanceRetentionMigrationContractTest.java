package com.tpverp.saas.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MemberBalanceRetentionMigrationContractTest {

    @Test
    void v38RequiresClaimSourceAndReceiptMetricConservation() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V38__member_balance_return_retention_claims.sql"))
                .toLowerCase();

        assertThat(sql).contains("source_document_id uuid not null");
        assertThat(sql).contains(
                "recovered_known + pending_missing + spent_shortfall = attributed_amount");
        assertThat(sql).contains("unique (reservation_id, lot_id)");
        assertThat(sql).contains("unique (receipt_id, lot_id)");
        assertThat(sql).contains(
                "check ((reservation_id is not null) <> (receipt_id is not null))");
    }

    @Test
    void v39AllowsZeroOnlyForOwnershipReservationsAndPreservesPrepareConstraint() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V39__allow_zero_owner_reservations.sql"))
                .toLowerCase();

        assertThat(sql).contains("drop constraint ck_saas_member_balance_reservation_typed_amounts");
        assertThat(sql).contains("add constraint ck_saas_member_balance_reservation_typed_amounts");
        assertThat(sql).contains("status in ('active', 'released', 'expired')");
        assertThat(sql).contains(
                "reserved_loyalty_amount + reserved_return_credit_amount > 0");
        assertThat(sql).doesNotContain("drop constraint ck_saas_member_balance_reservation_typed_prepare");
    }

    @Test
    void v40PreflightsReturnDocumentCollisionsAndAddsPartialUniqueIndex() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V40__member_return_recovery_document_dedup.sql"))
                .toLowerCase();

        assertThat(sql).contains("group by company_id, return_document_id");
        assertThat(sql).contains("having count(*) > 1");
        assertThat(sql).contains("raise exception");
        assertThat(sql).contains(
                "uk_saas_member_balance_retention_receipt_return_document");
        assertThat(sql).contains(
                "on saas_member_balance_retention_receipt(company_id, return_document_id)");
        assertThat(sql).contains("where return_document_id is not null");
        assertThat(sql).doesNotContain("delete from saas_member_balance_retention_receipt");
        assertThat(sql).doesNotContain("update saas_member_balance_retention_receipt");
    }

    @Test
    void v41StoresCrossOperationReplayAsAliasAndPreflightsDirectIdCollision() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V41__member_return_recovery_operation_aliases.sql"))
                .toLowerCase();

        assertThat(sql).contains("create table if not exists saas_member_balance_retention_receipt_alias");
        assertThat(sql).contains("operation_id uuid primary key");
        assertThat(sql).contains("references saas_member_balance_retention_receipt(operation_id)");
        assertThat(sql).contains("operation_id existe simultaneamente como receipt y alias");
        assertThat(sql).doesNotContain("delete from saas_member_balance_retention_receipt");
    }

    @Test
    void v42AddsDurableOperationOwnerGuardAndInsertTriggers() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V42__member_return_recovery_operation_guards.sql"))
                .toLowerCase();

        assertThat(sql).contains("saas_member_balance_retention_operation_owner");
        assertThat(sql).contains("lock table saas_member_balance_retention_receipt");
        assertThat(sql).contains("in share row exclusive mode");
        assertThat(sql).contains("primary key (operation_id, owner_kind)");
        assertThat(sql).contains("unique index if not exists uk_saas_member_balance_retention_operation_owner_operation");
        assertThat(sql).contains("on conflict (operation_id) do nothing");
        assertThat(sql).contains("for update");
        assertThat(sql).contains("trg_saas_member_balance_retention_receipt_operation_owner");
        assertThat(sql).contains("trg_saas_member_balance_retention_alias_operation_owner");
        assertThat(sql).contains("raise exception");
        assertThat(sql).doesNotContain("delete from saas_member_balance_retention_receipt");
    }
}
