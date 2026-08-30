package com.tpverp.backend.verifactu;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL projection for fiscal operational status. It performs one
 * aggregate query per refresh and never loads FiscalRecord entities or XML.
 */
@Repository
public class JdbcFiscalOperationalStatusRepository implements FiscalOperationalStatusRepository {

    private static final String SELECT = """
            select count(*) filter (where state.estado = 'PENDIENTE') as pendientes,
                   count(*) filter (where state.estado = 'ENVIANDO') as enviando,
                   count(*) filter (where state.estado = 'ENVIADO') as enviados,
                   min(state.actualizado_en) filter (
                       where state.estado in ('PENDIENTE', 'ENVIANDO', 'ENVIADO'))
                       as oldest_pending_at,
                   count(*) filter (
                       where state.estado = 'ENVIANDO' and state.lease_hasta <= ?) as expired_leases,
                   count(*) filter (where state.estado = 'RECHAZADO') as rechazados,
                   (select attempt.intentado_en
                      from intento_envio_fiscal attempt
                      join registro_fiscal attempted_record
                        on attempted_record.id = attempt.registro_id
                     where attempted_record.modo_fiscal = 'VERIFACTU'
                       and attempt.estado in ('ACEPTADO', 'ACEPTADO_CON_ERRORES')
                       %s
                     order by attempt.intentado_en desc
                     limit 1) as last_aeat_success_at
              from estado_envio_fiscal state
              join registro_fiscal record on record.id = state.registro_id
             where record.modo_fiscal = 'VERIFACTU'
               %s
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public JdbcFiscalOperationalStatusRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Compatibility constructor for small repository contract tests. */
    public JdbcFiscalOperationalStatusRepository(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    @Override
    public FiscalOperationalStatusSnapshot findForScope(UUID companyId, UUID installationId) {
        if (companyId == null || installationId == null) {
            throw new IllegalArgumentException("La empresa y la instalacion son obligatorias");
        }
        return query(
                String.format(SELECT,
                        "and attempted_record.empresa_id = ? and attempted_record.instalacion_id = ?",
                        "and record.empresa_id = ? and record.instalacion_id = ?"),
                Timestamp.from(clock.instant()), companyId, installationId, companyId, installationId);
    }

    @Override
    public FiscalOperationalStatusSnapshot findGlobal() {
        return query(String.format(SELECT, "", ""), Timestamp.from(clock.instant()));
    }

    private FiscalOperationalStatusSnapshot query(String sql, Object... args) {
        return jdbc.queryForObject(sql, (resultSet, rowNum) -> map(resultSet), args);
    }

    private FiscalOperationalStatusSnapshot map(ResultSet resultSet) throws SQLException {
        Map<FiscalSubmissionStatus, Long> backlog =
                new EnumMap<>(FiscalSubmissionStatus.class);
        backlog.put(FiscalSubmissionStatus.PENDIENTE, resultSet.getLong("pendientes"));
        backlog.put(FiscalSubmissionStatus.ENVIANDO, resultSet.getLong("enviando"));
        backlog.put(FiscalSubmissionStatus.ENVIADO, resultSet.getLong("enviados"));
        backlog.put(FiscalSubmissionStatus.RECHAZADO, resultSet.getLong("rechazados"));
        return new FiscalOperationalStatusSnapshot(
                backlog,
                instant(resultSet, "oldest_pending_at"),
                instant(resultSet, "last_aeat_success_at"),
                resultSet.getLong("expired_leases"));
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
