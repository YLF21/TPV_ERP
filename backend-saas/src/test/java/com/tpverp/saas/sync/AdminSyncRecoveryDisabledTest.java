package com.tpverp.saas.sync;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AdminSyncRecoveryDisabledTest {
    @Test
    void maintenanceEndpointIsAbsentByDefault() {
        new ApplicationContextRunner().withUserConfiguration(AdminSyncRecoveryController.class)
                .run(context -> assertThat(context).doesNotHaveBean(AdminSyncRecoveryController.class));
    }
}
