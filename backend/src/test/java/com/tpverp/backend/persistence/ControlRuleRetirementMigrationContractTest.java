package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.control.ControlRuleRepository;
import com.tpverp.backend.control.ControlRuleVersionRepository;
import com.tpverp.backend.control.ControlAlertType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class ControlRuleRetirementMigrationContractTest {
    @Test
    void addsRetirementWithoutMutatingHistoryOrRemovingIntegrityGuards() throws Exception {
        var sql = Files.readString(Path.of("src/main/resources/db/migration/V273__control_rule_retirement.sql"));
        assertThat(sql).contains("alter table control_regla\n", "alter table control_regla_version\n",
                "add column retirada boolean not null default false",
                "check (not retirada or not activa)");
        assertThat(sql.toLowerCase(java.util.Locale.ROOT)).doesNotContain(
                "delete from", "update control_", "drop ", "disable trigger");
    }

    @Test
    void excludesRetiredRulesFromCurrentDetectionButPreservesEffectiveHistoricalVersions() throws Exception {
        var currentQuery = ControlRuleRepository.class.getMethod(
                "findAllByStoreIdAndTypeAndActiveTrue", UUID.class, ControlAlertType.class)
                .getAnnotation(Query.class).value();
        assertThat(currentQuery).contains("rule.active = true", "rule.retired = false", "rule.storeId = :storeId");
        var historicalQuery = ControlRuleVersionRepository.class.getMethod("findEffectiveForDeletionSequence",
                UUID.class, UUID.class, UUID.class, UUID.class, Instant.class, List.class)
                .getAnnotation(Query.class).value();
        assertThat(historicalQuery).contains("version.cambiado_en <= operation.eliminado_en",
                "order by version.numero_version desc").doesNotContain("retirada", "retired", "rule.activa");
    }
}
