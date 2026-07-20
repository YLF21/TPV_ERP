package com.tpverp.bridge.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileAdapterRuntimeTest {
    @TempDir Path temporary;

    @Test
    void persistsBoundedDriverStateAcrossRuntimeInstances() throws Exception {
        var first = new FileAdapterRuntime(temporary);
        first.writeState("globalpayments", "terminal-1:operation-1", "APPROVED".getBytes(StandardCharsets.UTF_8));

        var second = new FileAdapterRuntime(temporary);
        assertThat(second.readState("globalpayments", "terminal-1:operation-1").orElseThrow())
                .asString(StandardCharsets.UTF_8).isEqualTo("APPROVED");
        second.deleteState("globalpayments", "terminal-1:operation-1");
        assertThat(second.readState("globalpayments", "terminal-1:operation-1")).isEmpty();
    }

    @Test
    void rejectsTraversalAndOversizedState() throws Exception {
        var runtime = new FileAdapterRuntime(temporary);
        assertThatThrownBy(() -> runtime.writeState("../escape", "key", new byte[] { 1 }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.writeState("safe", "key", new byte[1024 * 1024 + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void protectsAndWipesSecretsWithWindowsDpapi() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        var runtime = new FileAdapterRuntime(temporary);
        var input = "merchant-secret".getBytes(StandardCharsets.UTF_8);
        runtime.putSecret("windows:terminal-1", input);
        String resolved = runtime.withSecret("windows:terminal-1", value -> new String(value, StandardCharsets.UTF_8));
        assertThat(resolved).isEqualTo("merchant-secret");
        runtime.deleteSecret("windows:terminal-1");
        assertThatThrownBy(() -> runtime.withSecret("windows:terminal-1", value -> null))
                .isInstanceOf(IllegalStateException.class);
    }
}
