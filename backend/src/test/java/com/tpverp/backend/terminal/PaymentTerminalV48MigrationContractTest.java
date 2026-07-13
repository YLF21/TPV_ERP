package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaymentTerminalV48MigrationContractTest {
    @Test
    void migrationAddsVersionedOpaqueProtectedSecretReferencesWithoutPlaintextColumns() throws Exception {
        var sql = Files.readString(Path.of("src/main/resources/db/migration/V48__payment_terminal_secret_references.sql"));
        var normalized = sql.toLowerCase();
        assertThat(normalized).contains("payment_terminal_secret_reference", "opaque_reference", "protected_value", "version", "active","company_id uuid not null references empresa(id)","store_id uuid not null references tienda(id)","terminal_id uuid not null references terminal(id)");
        assertThat(normalized).doesNotContain("plaintext", "plain_value", "password varchar", "token varchar");
        assertThat(normalized).contains("unique", "check");
        assertThat(normalized).contains("foreign key (store_id, company_id) references tienda(id, empresa_id)","foreign key (terminal_id, store_id) references terminal(id, tienda_id)");
    }
}
