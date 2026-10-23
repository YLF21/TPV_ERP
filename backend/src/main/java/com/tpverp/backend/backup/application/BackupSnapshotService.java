package com.tpverp.backend.backup.application;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/** All summary values and pg_dump read exactly the same exported PostgreSQL snapshot. */
@Service
public class BackupSnapshotService {

    private final DataSource dataSource;

    public BackupSnapshotService(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(clock, "clock"); // Snapshot time deliberately comes from PostgreSQL.
    }

    public Capture capture(
            PostgreSqlBackupCommands commands, Path dump, UUID installationId, String applicationVersion) {
        return capture(commands, dump, installationId, applicationVersion, (connection, manifest) -> { });
    }

    @FunctionalInterface
    public interface SnapshotEnricher {
        void enrich(Connection connection, Map<String, Object> manifest) throws Exception;
    }

    public Capture capture(PostgreSqlBackupCommands commands, Path dump, UUID installationId,
            String applicationVersion, SnapshotEnricher enricher) {
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(dump, "dump");
        Objects.requireNonNull(installationId, "installationId");
        Objects.requireNonNull(applicationVersion, "applicationVersion");
        var files = SharedFileSnapshotGuard.snapshot();
        try {
            Map<String, Object> manifest;
            // Never use the caller's transaction: the exported snapshot needs a dedicated connection.
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setReadOnly(true);
                try {
                    String snapshot;
                    String capturedAt;
                    try (var statement = connection.createStatement();
                            var rows = statement.executeQuery(
                                    "select pg_export_snapshot(), statement_timestamp()")) {
                        if (!rows.next()) {
                            throw new SQLException("PostgreSQL did not export a snapshot");
                        }
                        snapshot = rows.getString(1);
                        capturedAt = rows.getTimestamp(2).toInstant().toString();
                    }
                    var values = new LinkedHashMap<String, Object>();
                    values.put("schemaVersion", 1);
                    values.put("capturedAt", capturedAt);
                    values.put("installationId", installationId.toString());
                    values.put("applicationVersion", applicationVersion);
                    values.putAll(snapshotSummary(connection));
                    enricher.enrich(connection, values);
                    // Keep the exporting transaction open until pg_dump has finished importing/reading it.
                    commands.dump(dump, snapshot);
                    manifest = Collections.unmodifiableMap(values);
                } finally {
                    connection.rollback();
                }
            }
            // The SQL snapshot may now close; file writers remain blocked until archive creation ends.
            return new Capture(manifest, files);
        } catch (SQLException exception) {
            files.close();
            throw new IllegalStateException("No se pudo capturar la instantanea de la copia", exception);
        } catch (RuntimeException | Error exception) {
            files.close();
            throw exception;
        } catch (Exception exception) {
            files.close();
            throw new IllegalStateException("No se pudo preparar el contenido de recuperación", exception);
        }
    }

    /** Same queries are used on the exporting snapshot and the unopened restored database. */
    public static Map<String,Object> snapshotSummary(Connection connection) throws SQLException {
        var result=new LinkedHashMap<String,Object>();
        result.put("categories",categories(connection));
        result.put("documentsByType",documentsByType(connection));
        result.put("lastDocuments",lastDocuments(connection));
        result.put("documentCounters",documentCounters(connection));
        result.put("fiscalChainHeads",fiscalChainHeads(connection));
        result.put("tableCounts",tableCounts(connection));
        result.put("tableCountMode","PHYSICAL_ROWS_ONLY");
        return Collections.unmodifiableMap(result);
    }

    public static void verifyRestoredSnapshot(Connection connection,com.fasterxml.jackson.databind.JsonNode manifest) throws SQLException {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        final com.fasterxml.jackson.databind.JsonNode actual;
        try {
            // Round-trip JSON normalizes integral JDBC numeric widths exactly as in the archive.
            actual=mapper.readTree(mapper.writeValueAsBytes(snapshotSummary(connection)));
        } catch(java.io.IOException invalid){throw new SQLException("Cannot serialize restored snapshot summary",invalid);}
        var fields=actual.fields();
        while(fields.hasNext()) {
            var field=fields.next();
            if(!field.getValue().equals(manifest.get(field.getKey())))
                throw new SQLException("Restored snapshot mismatch: "+field.getKey());
        }
    }

    private static Map<String,Object> fiscalChainHeads(Connection connection) throws SQLException {
        return Map.of("records",queryRows(connection,"""
                select id::text as "chainId",empresa_id::text as "companyId",instalacion_id::text as "installationId",
                    ultimo_registro_id::text as "lastRecordId",ultima_secuencia as "lastSequence",ultima_huella as "lastHash",
                    version as "revision" from cadena_fiscal order by id
                """),"events",queryRows(connection,"""
                select id::text as "chainId",empresa_id::text as "companyId",instalacion_id::text as "installationId",
                    ultima_secuencia as "lastSequence",ultima_huella as "lastHash",version as "revision"
                from cadena_eventos_fiscal order by id
                """));
    }

    private static Map<String, Long> categories(Connection connection) throws SQLException {
        var tables = new LinkedHashMap<String, String>();
        tables.put("products", "producto");
        tables.put("customers", "cliente");
        tables.put("members", "miembro");
        tables.put("suppliers", "proveedor");
        tables.put("documents", "documento");
        tables.put("documentLines", "documento_linea");
        tables.put("payments", "documento_pago");
        tables.put("stockEntries", "existencia");
        tables.put("stockMovements", "movimiento_stock");
        tables.put("cashSessions", "sesion_caja");
        tables.put("cashMovements", "movimiento_caja");
        tables.put("users", "usuario");
        tables.put("terminals", "terminal");
        var result = new LinkedHashMap<String, Long>();
        for (var entry : tables.entrySet()) {
            result.put(entry.getKey(), count(connection, "select count(*) from " + quote(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Long> documentsByType(Connection connection) throws SQLException {
        var result = new LinkedHashMap<String, Long>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("select tipo, count(*) from documento group by tipo order by tipo")) {
            while (rows.next()) {
                result.put(rows.getString(1), rows.getLong(2));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<Map<String, Object>> lastDocuments(Connection connection) throws SQLException {
        // The stored number defines the series, including historical store codes. Do not reconstruct it
        // from today's counter or store settings. Tickets reset daily; the other commercial types yearly.
        return queryRows(connection, """
                with numbered as (
                    select d.*, regexp_replace(d.numero, '-[^-]+$', '') as backup_series,
                        case when d.tipo = 'TICKET' then to_char(d.fecha, 'YYYYMMDD')
                             else to_char(d.fecha, 'YYYY') end as backup_period
                    from documento d where d.numero is not null
                ), ranked as (
                    select n.*, row_number() over (
                        partition by tipo, tienda_id, backup_series, backup_period
                        order by case when numero ~ '[0-9]+$'
                                      then substring(numero from '[0-9]+$')::numeric end desc nulls last,
                                 confirmado_en desc nulls last, creado_en desc, id desc) as backup_rank
                    from numbered n
                )
                select d.tipo as "type", d.tienda_id::text as "storeId", t.codigo_tienda as "storeCode",
                    d.backup_series as "series", d.backup_period as "period", d.id::text as "documentId",
                    d.numero as "number", d.fecha::text as "date", d.confirmado_en as "confirmedAt"
                from ranked d join tienda t on t.id = d.tienda_id
                where d.backup_rank = 1
                order by d.tipo, d.tienda_id, d.backup_series, d.backup_period
                """);
    }

    private static List<Map<String, Object>> documentCounters(Connection connection) throws SQLException {
        return queryRows(connection, """
                select tienda_id::text as "storeId", tipo as "type", periodo as "period",
                       ultimo_numero as "lastSequence"
                from contador_documento order by tienda_id, tipo, periodo
                """);
    }

    private static Map<String, Long> tableCounts(Connection connection) throws SQLException {
        var tables = new ArrayList<String[]>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        select n.nspname, c.relname
                        from pg_catalog.pg_class c
                        join pg_catalog.pg_namespace n on n.oid = c.relnamespace
                        where c.relkind in ('r', 'p')
                          and n.nspname <> 'information_schema'
                          and n.nspname !~ '^pg_'
                        order by n.nspname, c.relname
                        """)) {
            while (rows.next()) {
                tables.add(new String[] {rows.getString(1), rows.getString(2)});
            }
        }
        var result = new LinkedHashMap<String, Long>();
        for (String[] table : tables) {
            // ONLY gives each physical row one home, including partition/inheritance children.
            String qualified = quote(table[0]) + "." + quote(table[1]);
            result.put(qualified, count(connection, "select count(*) from only " + qualified));
        }
        return Collections.unmodifiableMap(result);
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new SQLException("Missing exact table count");
            }
            return rows.getLong(1);
        }
    }

    private static List<Map<String, Object>> queryRows(Connection connection, String sql) throws SQLException {
        var result = new ArrayList<Map<String, Object>>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            var metadata = rows.getMetaData();
            while (rows.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    Object value = rows.getObject(column);
                    if (value instanceof java.sql.Timestamp timestamp) {
                        value = timestamp.toInstant().toString();
                    }
                    row.put(metadata.getColumnLabel(column), value);
                }
                result.add(Collections.unmodifiableMap(row));
            }
        }
        return List.copyOf(result);
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    public static final class Capture implements AutoCloseable {
        private final Map<String, Object> manifest;
        private final SharedFileSnapshotGuard.Scope files;

        private Capture(Map<String, Object> manifest, SharedFileSnapshotGuard.Scope files) {
            this.manifest = manifest;
            this.files = files;
        }

        public Map<String, Object> manifest() {
            return manifest;
        }

        @Override
        public void close() {
            files.close();
        }
    }
}
