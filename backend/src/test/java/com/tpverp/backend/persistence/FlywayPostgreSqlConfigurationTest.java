package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Test;

class FlywayPostgreSqlConfigurationTest {

    @Test
    void disablesPostgresqlTransactionalAdvisoryLockForConcurrentIndexMigrations() {
        var configuration = new FluentConfiguration();

        new FlywayPostgreSqlConfiguration().postgresqlConcurrentIndexCustomizer()
                .customize(configuration);

        assertThat(configuration
                .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
                .isTransactionalLock()).isFalse();
    }
}
