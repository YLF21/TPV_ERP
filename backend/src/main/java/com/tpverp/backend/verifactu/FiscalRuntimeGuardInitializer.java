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
        var records = count("registro_fiscal");
        var transitions = count("transicion_modo_fiscal");
        var artifacts = count("artefacto_registro_fiscal");
        return records == 0 && transitions == 0 && artifacts == 0;
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
