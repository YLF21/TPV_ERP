package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV24ContractTest {

    private static final String MIGRATION =
            "db/migration/V24__sincronizacion_hibrida.sql";

    @Test
    void creaOutboxLocalEInboxCentralParaSincronizacionIdempotente() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("create table sync_outbox")
                .contains("event_id uuid not null")
                .contains("empresa_id uuid not null references empresa(id)")
                .contains("tienda_id uuid references tienda(id)")
                .contains("terminal_id uuid references terminal(id)")
                .contains("tipo_entidad varchar(64) not null")
                .contains("entidad_id uuid not null")
                .contains("operacion varchar(32) not null")
                .contains("payload jsonb not null")
                .contains("estado varchar(16) not null default 'pendiente'")
                .contains("intentos integer not null default 0")
                .contains("constraint ux_sync_outbox_event unique (event_id)")
                .contains("create table sync_inbox")
                .contains("procesado boolean not null default false")
                .contains("constraint ux_sync_inbox_event unique (event_id)");
    }

    @Test
    void indexaEventosPorTenantEstadoYFecha() throws IOException {
        assertThat(migrationSql())
                .contains("ix_sync_outbox_empresa_estado_fecha")
                .contains("ix_sync_outbox_tienda_estado_fecha")
                .contains("ix_sync_inbox_empresa_recibido")
                .contains("ix_sync_inbox_procesado");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
