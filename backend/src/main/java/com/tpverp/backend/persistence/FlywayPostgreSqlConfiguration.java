package com.tpverp.backend.persistence;

import java.sql.SQLException;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
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

    private static final Callback PG_TRGM_SEARCH_PATH_CALLBACK = new PgTrgmSearchPathCallback();

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
        configuration.callbacks(PG_TRGM_SEARCH_PATH_CALLBACK);
        return configuration;
    }

    /**
     * PostgreSQL installs an extension in one schema per database. Test and
     * multi-schema databases can therefore find {@code pg_trgm} in a schema
     * different from Flyway's default schema. Make its operator classes visible
     * before applying migrations without moving the extension or changing an
     * already released migration checksum.
     */
    private static final class PgTrgmSearchPathCallback implements Callback {

        private static final String INCLUDE_EXTENSION_SCHEMA = """
                select set_config(
                    'search_path',
                    current_setting('search_path') || ',' || quote_ident(namespace.nspname),
                    false
                )
                from pg_extension extension
                join pg_namespace namespace on namespace.oid = extension.extnamespace
                where extension.extname = 'pg_trgm'
                  and namespace.nspname <> all (current_schemas(false))
                """;

        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.BEFORE_EACH_MIGRATE;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            try (var statement = context.getConnection().createStatement()) {
                statement.execute(INCLUDE_EXTENSION_SCHEMA);
            } catch (SQLException exception) {
                throw new FlywayException("Unable to include the pg_trgm schema in PostgreSQL search_path", exception);
            }
        }

        @Override
        public String getCallbackName() {
            return "pg_trgm search path";
        }
    }
}
