package com.tpverp.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tpverp.backend.security.domain.UserAccount;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableLayoutPreferenceTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");

    @Test
    void normalizesColumnsAndDefaultsVisibilityToTrue() {
        var source = new ArrayList<>(List.of(
                new TableLayoutColumn(" name ", null, null),
                new TableLayoutColumn("price", 120, false)));

        var preference = new TableLayoutPreference(
                mock(UserAccount.class), "venta", "stock.current", source, NOW);
        source.clear();

        assertThat(preference.getColumns()).containsExactly(
                new TableLayoutColumn("name", null, true),
                new TableLayoutColumn("price", 120, false));
        assertThatThrownBy(() -> preference.getColumns().add(
                new TableLayoutColumn("other", null, true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void updatesColumnsAndTimestampWithoutChangingCreationTime() {
        var later = NOW.plusSeconds(60);
        var preference = new TableLayoutPreference(
                mock(UserAccount.class),
                "gestion",
                "products.list",
                List.of(new TableLayoutColumn("name", 220, true)),
                NOW);

        preference.update(List.of(new TableLayoutColumn("code", 90, false)), later);

        assertThat(preference.getCreatedAt()).isEqualTo(NOW);
        assertThat(preference.getUpdatedAt()).isEqualTo(later);
        assertThat(preference.getColumns())
                .containsExactly(new TableLayoutColumn("code", 90, false));
    }

    @Test
    void rejectsUnsupportedAppsAndInvalidTableKeys() {
        var user = mock(UserAccount.class);
        var columns = List.of(new TableLayoutColumn("name", null, true));

        assertThatThrownBy(() -> new TableLayoutPreference(
                user, "terminal", "stock.current", columns, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app");
        assertThatThrownBy(() -> new TableLayoutPreference(
                user, "venta", "stock/current", columns, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tableKey");
        assertThatThrownBy(() -> new TableLayoutPreference(
                user, "venta", "x".repeat(129), columns, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
    }

    @Test
    void rejectsInvalidColumnsAndMoreThan128Items() {
        assertThatThrownBy(() -> new TableLayoutColumn("name", 55, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("56");
        assertThatThrownBy(() -> new TableLayoutColumn("name", 801, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("800");
        assertThatThrownBy(() -> new TableLayoutColumn(" ", null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
        assertThatThrownBy(() -> new TableLayoutPreference(
                mock(UserAccount.class),
                "venta",
                "stock.current",
                Collections.nCopies(129, new TableLayoutColumn("name", null, true)),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
    }
}
