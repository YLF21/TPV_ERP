package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LicenseSaasProductionGuardTest {

    @Test
    void acceptsOnlyARealHttpsRoot() {
        assertThat(LicenseSaasProductionGuard.requireProductionEndpoint(
                "https://licenses.tpv-erp.es"))
                .hasScheme("https")
                .hasHost("licenses.tpv-erp.es");
    }

    @Test
    void rejectsMissingInsecureAndPlaceholderEndpoints() {
        for (String value : new String[] {
                "", "http://licenses.tpv-erp.es", "https://localhost:8080",
                "https://licenses.example", "https://licenses.example.invalid",
                "https://licenses.tpv-erp.es/api"
        }) {
            assertThatThrownBy(() -> LicenseSaasProductionGuard.requireProductionEndpoint(value))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void productionGuardRequiresSecureSyncOnTheSameOrigin() {
        new LicenseSaasProductionGuard(
                "https://licenses.tpv-erp.es", "https://licenses.tpv-erp.es").validate();

        assertThatThrownBy(() -> new LicenseSaasProductionGuard(
                "https://licenses.tpv-erp.es", "http://licenses.tpv-erp.es").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TPV_SYNC_CENTRAL_URL");
        assertThatThrownBy(() -> new LicenseSaasProductionGuard(
                "https://licenses.tpv-erp.es", "https://sync.tpv-erp.es").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mismo origen");
    }
}
