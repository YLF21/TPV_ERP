package com.tpverp.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tpverp.backend.security.domain.UserAccount;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardPreferenceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Test
    void keepsAnImmutableNormalizedLayoutAndUpdatesItsTimestamp() {
        var preference = new DashboardPreference(
                mock(UserAccount.class),
                List.of(new DashboardWidgetLayout(" sales.today ", 4, 1)),
                NOW);

        assertThat(preference.getWidgets()).containsExactly(
                new DashboardWidgetLayout("sales.today", 4, 1));
        assertThatThrownBy(() -> preference.getWidgets().add(
                new DashboardWidgetLayout("promotions.active", 4, 2)))
                .isInstanceOf(UnsupportedOperationException.class);

        var later = NOW.plusSeconds(30);
        preference.update(List.of(new DashboardWidgetLayout("sales.today", 8, 2)), later);

        assertThat(preference.getCreatedAt()).isEqualTo(NOW);
        assertThat(preference.getUpdatedAt()).isEqualTo(later);
        assertThat(preference.getWidgets()).containsExactly(
                new DashboardWidgetLayout("sales.today", 8, 2));
    }

    @Test
    void rejectsDuplicatedKeysAndUnsupportedDimensions() {
        var user = mock(UserAccount.class);

        assertThatThrownBy(() -> new DashboardPreference(user, List.of(
                new DashboardWidgetLayout("sales.today", 4, 1),
                new DashboardWidgetLayout("sales.today", 6, 2)), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicada");
        assertThatThrownBy(() -> new DashboardPreference(user, List.of(
                new DashboardWidgetLayout("sales.today", 5, 1)), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("width");
        assertThatThrownBy(() -> new DashboardPreference(user, List.of(
                new DashboardWidgetLayout("sales.today", 4, 4)), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("height");
    }
}
