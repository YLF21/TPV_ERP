package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class FiscalRuntimeGuardInitializerTest {

    @Test
    void promotesOnlyAnEmptyFreshDatabaseToSandbox() {
        var jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(String.class))).thenReturn("REAL");
        when(jdbc.queryForObject(any(String.class), eq(Long.class))).thenReturn(0L);
        var initializer = new FiscalRuntimeGuardInitializer(jdbc, sandbox());

        initializer.run(new DefaultApplicationArguments());

        verify(jdbc).update(any(String.class), eq("SANDBOX"));
    }

    @Test
    void rejectsSandboxRestorationWithFiscalData() {
        var jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(String.class))).thenReturn("REAL");
        when(jdbc.queryForObject(any(String.class), eq(Long.class))).thenReturn(1L);
        var initializer = new FiscalRuntimeGuardInitializer(jdbc, sandbox());

        assertThatThrownBy(() -> initializer.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restauracion cruzada");
    }

    private static FiscalRuntimeProperties sandbox() {
        return new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED"));
    }
}
