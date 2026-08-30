package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcFiscalOperationalStatusRepositoryContractTest {

    @Test
    void springCreatesTheRepositoryWithItsRuntimeDependencies() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class,
                    () -> new JdbcTemplate(mock(DataSource.class)));
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(JdbcFiscalOperationalStatusRepository.class);
            context.refresh();

            assertThat(context.getBean(JdbcFiscalOperationalStatusRepository.class)).isNotNull();
        }
    }

    @Test
    void consultaGlobalEnlazaElInstanteComoTimestampJdbc() {
        var jdbc = mock(JdbcTemplate.class);
        var now = Instant.parse("2026-08-28T19:00:00Z");
        var arguments = ArgumentCaptor.forClass(Object[].class);

        new JdbcFiscalOperationalStatusRepository(jdbc,
                Clock.fixed(now, ZoneOffset.UTC)).findGlobal();

        verify(jdbc).queryForObject(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<FiscalOperationalStatusSnapshot>>any(),
                arguments.capture());
        assertThat(arguments.getValue()).containsExactly(Timestamp.from(now));
    }

    @Test
    void migrationCreaElIndiceDeAlcanceQueUsaLaProyeccion() throws Exception {
        var migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V220__fiscal_operational_observability.sql"),
                StandardCharsets.UTF_8);
        assertThat(migration).contains("ix_registro_fiscal_operational_scope")
                .contains("empresa_id, instalacion_id, id");
    }

    @Test
    void proyeccionSoloSeleccionaAgregadosYEstadosPermitidos() throws Exception {
        var source = Files.readString(Path.of(
                "src/main/java/com/tpverp/backend/verifactu/JdbcFiscalOperationalStatusRepository.java"),
                StandardCharsets.UTF_8);
        assertThat(source).contains("count(*) filter")
                .contains("ACEPTADO_CON_ERRORES")
                .doesNotContain("select snapshot")
                .doesNotContain("findAll");
    }

    @Test
    void consultaDelUltimoAceptadoUsaOrdenDescendenteYIndiceParcial() throws Exception {
        var source = Files.readString(Path.of(
                "src/main/java/com/tpverp/backend/verifactu/JdbcFiscalOperationalStatusRepository.java"),
                StandardCharsets.UTF_8);
        var migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V223__fiscal_accepted_attempt_read_index.sql"),
                StandardCharsets.UTF_8);
        var config = Files.readString(Path.of(
                "src/main/resources/db/migration/V223__fiscal_accepted_attempt_read_index.sql.conf"),
                StandardCharsets.UTF_8);

        assertThat(source).contains("order by attempt.intentado_en desc")
                .contains("limit 1")
                .contains("join registro_fiscal attempted_record")
                .contains("attempt.estado in ('ACEPTADO', 'ACEPTADO_CON_ERRORES')");
        assertThat(migration).contains("create index concurrently")
                .contains("ix_intento_envio_fiscal_accepted_fecha_record")
                .contains("on intento_envio_fiscal(intentado_en desc, registro_id)")
                .contains("where estado in ('ACEPTADO', 'ACEPTADO_CON_ERRORES')");
        assertThat(config).contains("executeInTransaction=false");
    }

    @Test
    void configuracionExponeUmbralDeObsolescenciaSeparadoDelIntervalo() throws Exception {
        var application = Files.readString(Path.of(
                "src/main/resources/application.yml"), StandardCharsets.UTF_8);

        assertThat(application).contains("stale-after-ms:")
                .contains("TPV_VERIFACTU_OBSERVABILITY_STALE_AFTER_MS:180000")
                .contains("interval-ms: ${TPV_VERIFACTU_OBSERVABILITY_INTERVAL_MS:60000}");
    }
}
