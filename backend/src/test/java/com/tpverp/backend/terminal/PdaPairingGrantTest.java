package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PdaPairingGrantTest {
    @Test
    void consumesAnUnexpiredCodeOnlyOnce() {
        var terminal = new Terminal(store(), "PDA 1", TerminalType.PDA, "hash");
        var issuedAt = Instant.parse("2026-08-29T10:00:00Z");
        var grant = new PdaPairingGrant(terminal, "code-hash", issuedAt, issuedAt.plusSeconds(600));
        assertThat(grant.consume(issuedAt.plusSeconds(60))).isSameAs(terminal);
        assertThatThrownBy(() -> grant.consume(issuedAt.plusSeconds(61)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.terminal.pda_pairing_used");
    }

    @Test
    void rejectsAnExpiredCode() {
        var issuedAt = Instant.parse("2026-08-29T10:00:00Z");
        var grant = new PdaPairingGrant(
                new Terminal(store(), "PDA 1", TerminalType.PDA, "hash"),
                "code-hash", issuedAt, issuedAt.plusSeconds(600));
        assertThatThrownBy(() -> grant.consume(issuedAt.plusSeconds(600)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.terminal.pda_pairing_expired");
    }

    private static Store store() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Store(
                new Company("B00000000", "Company", address),
                "001", "Store", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }
}
