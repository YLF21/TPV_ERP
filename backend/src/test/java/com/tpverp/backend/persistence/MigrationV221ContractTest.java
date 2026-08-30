package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrationV221ContractTest {

    @Test
    void releaseObservationHasImmutableAuditAndMarkerHashes() throws Exception {
        var sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V221__fiscal_release_observation_audit.sql"),
                StandardCharsets.UTF_8);
        assertThat(sql).contains("add column artifact_hash")
                .contains("add column commit_hash")
                .contains("create table fiscal_runtime_release_audit")
                .contains("tr_fiscal_runtime_release_audit_inmutable")
                .contains("before update or delete")
                .contains("ix_fiscal_runtime_release_audit_observado");
    }
}
