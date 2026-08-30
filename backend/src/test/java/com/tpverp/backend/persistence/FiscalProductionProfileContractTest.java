package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FiscalProductionProfileContractTest {

    @Test
    void productionProfileCannotBeDowngradedByEnvironmentVariables() throws Exception {
        var profile = Files.readString(Path.of(
                "src/main/resources/application-prod.yml"));

        assertThat(profile)
                .contains("address: 127.0.0.1")
                .contains("endpoint-mode: PRODUCTION")
                .contains("endpoint-environment: PRODUCTION")
                .contains("runtime-class: REAL")
                .contains("transport-mode: AEAT")
                .contains("product-capability: VERIFACTU_ONLY")
                .contains("production-enabled: ${TPV_VERIFACTU_PRODUCTION_ENABLED:false}")
                .contains("worker-enabled: true")
                .doesNotContain("endpoint-mode: ${")
                .doesNotContain("runtime-class: ${")
                .doesNotContain("transport-mode: ${");
    }

    @Test
    void declaredIdentityAndDeclarationDigestHaveNoProductionFallbacks() throws Exception {
        var profile = Files.readString(Path.of(
                "src/main/resources/application-prod.yml"));

        assertThat(profile)
                .contains("producer-name: ${TPV_VERIFACTU_PRODUCER_NAME}")
                .contains("producer-tax-id: ${TPV_VERIFACTU_PRODUCER_TAX_ID}")
                .contains("system-name: ${TPV_VERIFACTU_SYSTEM_NAME}")
                .contains("system-id: ${TPV_VERIFACTU_SYSTEM_ID}")
                .contains("declaration-hash: ${TPV_VERIFACTU_DECLARATION_HASH}")
                .doesNotContain("TPV ERP DEV")
                .doesNotContain("B00000000");
    }
}
