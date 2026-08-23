package com.tpverp.backend.verifactu;

import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the runtime class in the database and rejects cross-restoration
 * between the fiscal laboratory and a REAL installation.
 */
@Component
public class FiscalRuntimeGuardInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final FiscalRuntimeProperties runtime;

    public FiscalRuntimeGuardInitializer(JdbcTemplate jdbc, FiscalRuntimeProperties runtime) {
        this.jdbc = jdbc;
        this.runtime = runtime;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var expected = runtime.runtimeClass().name();
        var actual = jdbc.queryForObject(
                "select runtime_class from fiscal_runtime_guard where id = 1", String.class);
        if (actual == null) {
            throw new IllegalStateException("El marcador fiscal persistente esta vacio");
        }
        actual = actual.trim().toUpperCase(Locale.ROOT);
        if (expected.equals(actual)) {
            return;
        }
        if (runtime.isSandbox() && "REAL".equals(actual) && isEmptyFiscalState()) {
            jdbc.update(
                    "update fiscal_runtime_guard set runtime_class = ?, version = version + 1 where id = 1",
                    expected);
            return;
        }
        throw new IllegalStateException(
                "La base fiscal esta marcada como " + actual
                        + " y el proceso intenta arrancar como " + expected
                        + "; se rechaza la restauracion cruzada");
    }

    private boolean isEmptyFiscalState() {
        // A runtime marker is only promotable on a genuinely fresh fiscal
        // database. Event chains, exports, snapshots or version identities
        // are evidence too, even when no billing record was persisted.
        return java.util.stream.Stream.of(
                "cadena_fiscal",
                "registro_fiscal",
                "cadena_eventos_fiscal",
                "registro_evento_fiscal",
                "transicion_modo_fiscal",
                "version_sistema_fiscal",
                "artefacto_registro_fiscal",
                "snapshot_impresion_fiscal",
                "alarma_fiscal",
                "exportacion_fiscal",
                "requerimiento_fiscal")
                .allMatch(table -> count(table) == 0);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
