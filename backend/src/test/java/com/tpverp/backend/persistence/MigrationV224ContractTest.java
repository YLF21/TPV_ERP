package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrationV224ContractTest {

    @Test
    void fiscalMarkerAndAuditCarryMonotonicReleaseSequence() throws Exception {
        var sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V224__fiscal_release_sequence_order.sql"),
                StandardCharsets.UTF_8);
        assertThat(sql).contains("alter table fiscal_runtime_guard")
                .contains("add column release_sequence bigint not null default 0")
                .contains("add column build_sequence bigint not null default 0")
                .contains("alter table fiscal_runtime_release_audit")
                .contains("check (release_sequence >= 0)")
                .contains("check (build_sequence >= 0)");
    }
}
