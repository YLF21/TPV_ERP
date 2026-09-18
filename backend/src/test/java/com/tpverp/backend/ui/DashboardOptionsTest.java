package com.tpverp.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DashboardOptionsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rejectsMissingAndUnsupportedChoicesAtTheJsonBoundary() {
        assertThatThrownBy(() -> json.readValue("{}", DashboardOptions.class))
                .hasMessageContaining("defaultPeriod");
        assertThatThrownBy(() -> new DashboardOptions("CUSTOM", "LINE", "BAR", "COMPACT", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DashboardOptions("TODAY", "PIE", "BAR", "COMPACT", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DashboardOptions("TODAY", "LINE", "LINE", "COMPACT", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DashboardOptions("TODAY", "LINE", "BAR", "SMALL", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DashboardOptions("TODAY", "LINE", "BAR", "COMPACT", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsTheLegacyWidgetsOnlyRequestCompatible() {
        var legacy = json.readValue("{\"widgets\":[]}", DashboardPreferenceService.SavePreferenceRequest.class);
        assertThat(legacy.widgets()).isEmpty();
        assertThat(legacy.options()).isNull();
        var options = DashboardOptions.defaults();
        assertThat(json.readValue(json.writeValueAsString(options), DashboardOptions.class)).isEqualTo(options);
    }
}
