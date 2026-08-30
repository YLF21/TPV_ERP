package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrationV230WholesaleModeContractTest {

    @Test
    void addsNonNullWholesaleModeWithHistoricalFalseDefault() throws Exception {
        var path = Path.of("src/main/resources/db/migration/V230__documento_modo_mayorista.sql");
        var sql = Files.readString(path, StandardCharsets.UTF_8).toLowerCase();

        assertThat(sql).contains("alter table documento");
        assertThat(sql).contains("add column wholesale_mode boolean not null default false");
    }
}
