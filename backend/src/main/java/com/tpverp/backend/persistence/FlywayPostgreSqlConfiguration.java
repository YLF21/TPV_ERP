package com.tpverp.backend.persistence;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Allows PostgreSQL concurrent-index migrations to run without taking Flyway's
 * transactional advisory lock. The migration sidecar also disables the transaction
 * for the statements themselves; both settings are required by Flyway 12.4.
 */
@Configuration(proxyBeanMethods = false)
public class FlywayPostgreSqlConfiguration {

    @Bean
    FlywayConfigurationCustomizer postgresqlConcurrentIndexCustomizer() {
        return FlywayPostgreSqlConfiguration::disableTransactionalLock;
    }

    /**
     * Applies the PostgreSQL-only Flyway setting needed by migrations that use
     * {@code CREATE INDEX CONCURRENTLY}. Keeping this as a small reusable
     * helper also lets direct PostgreSQL migration tests use the same setting
     * as the Spring Boot runtime.
     */
    public static FluentConfiguration disableTransactionalLock(
            FluentConfiguration configuration) {
        configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class)
                .setTransactionalLock(false);
        return configuration;
    }
}
