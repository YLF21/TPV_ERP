package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TerminalInterfaceConfigurationMigrationTest {

    private static final String MIGRATION =
            "db/migration/V97__configuracion_interfaz_terminal.sql";

    @Test
    void definesOneTypedSaleInterfaceModePerTerminal() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql).contains(
                    "create table configuracion_interfaz_terminal",
                    "sale_mode varchar(16) not null default 'keyboard'",
                    "unique (terminal_id)",
                    "check (sale_mode in ('keyboard', 'touch'))",
                    "foreign key (terminal_id) references terminal (id)");
        }
    }
}
