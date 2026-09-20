package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class CashDocumentTemplateMigrationPostgreSqlTest {

    @Test
    void enablesEntryReceiptWithoutChangingCustomWithdrawalTemplates() throws Exception {
        var database = DatabaseEnvironment.resolve();
        assumeTrue(database != null, "Configure TPV_TEST_DB_* o TPV_ERP_TEST_DB_* para PostgreSQL");
        var schema = "cash_template_" + UUID.randomUUID().toString().replace("-", "");
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var withdrawalTemplateId = UUID.randomUUID();
        try {
            migrate(database, schema, "249");
            try (var connection = DriverManager.getConnection(database.url(), database.user(), database.password());
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                statement.execute("""
                        insert into empresa(id,tax_id,razon_social,domicilio_fiscal)
                        values ('%s','B1','Test','{"linea1":"x","ciudad":"x","codigoPostal":"1","provincia":"x","pais":"ES"}')
                        """.formatted(companyId));
                statement.execute("""
                        insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda)
                        values ('%s','%s','T','{"linea1":"x","ciudad":"x","codigoPostal":"1","provincia":"x","pais":"ES"}',
                                'h','Atlantic/Canary','EUR','es-ES','001')
                        """.formatted(storeId, companyId));
                statement.execute("""
                        insert into configuracion_origen_plantilla_documento(tienda_id,tipo,formato,origen)
                        values ('%s','RETIRADA_CAJA','TICKET_80','IMPORTED')
                        """.formatted(storeId));
                statement.execute("""
                        insert into plantilla_documento(id,empresa_id,tienda_id,tipo,formato,ambito,codigo,
                            version_plantilla,nombre,estado,schema_version,artifact_reference,sha256,
                            creada_en,validada_en,activada_en)
                        values ('%s','%s','%s','RETIRADA_CAJA','TICKET_80','STORE','RETIRADA_TIENDA',3,
                            'Retirada personalizada','ACTIVE',1,'custom:withdrawal','%s',now(),now(),now())
                        """.formatted(withdrawalTemplateId, companyId, storeId, "a".repeat(64)));
            }

            migrate(database, schema, "250");

            try (var connection = DriverManager.getConnection(database.url(), database.user(), database.password());
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                try (var rows = statement.executeQuery("""
                        select tipo, origen from configuracion_origen_plantilla_documento
                        where tienda_id = '%s' and tipo in ('ENTRADA_CAJA','RETIRADA_CAJA') order by tipo
                        """.formatted(storeId))) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("tipo")).isEqualTo("ENTRADA_CAJA");
                    assertThat(rows.getString("origen")).isEqualTo("INTEGRATED");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("tipo")).isEqualTo("RETIRADA_CAJA");
                    assertThat(rows.getString("origen")).isEqualTo("IMPORTED");
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = statement.executeQuery("""
                        select estado, version_plantilla, artifact_reference, sha256
                        from plantilla_documento where id = '%s'
                        """.formatted(withdrawalTemplateId))) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("estado")).isEqualTo("ACTIVE");
                    assertThat(rows.getInt("version_plantilla")).isEqualTo(3);
                    assertThat(rows.getString("artifact_reference")).isEqualTo("custom:withdrawal");
                    assertThat(rows.getString("sha256")).isEqualTo("a".repeat(64));
                }
                statement.execute("""
                        insert into plantilla_documento(id,empresa_id,tienda_id,tipo,formato,ambito,codigo,
                            version_plantilla,nombre,estado,creada_en)
                        values ('%s','%s','%s','ENTRADA_CAJA','TICKET_80','STORE','ENTRADA_TIENDA',1,
                            'Entrada personalizada','DRAFT',now())
                        """.formatted(UUID.randomUUID(), companyId, storeId));
                assertThatThrownBy(() -> statement.execute("""
                        update plantilla_documento set formato = 'A4' where tipo = 'ENTRADA_CAJA'
                        """))
                        .isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
                assertThatThrownBy(() -> statement.execute("""
                        update configuracion_origen_plantilla_documento set formato = 'A4'
                        where tipo = 'ENTRADA_CAJA'
                        """))
                        .isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
            }
        } finally {
            try (var connection = DriverManager.getConnection(database.url(), database.user(), database.password());
                    var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void migrate(DatabaseEnvironment database, String schema, String target) {
        FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                .dataSource(database.url(), database.user(), database.password())
                .schemas(schema).defaultSchema(schema).createSchemas(true)
                .target(target).load().migrate();
    }

    private record DatabaseEnvironment(String url, String user, String password) {
        private static DatabaseEnvironment resolve() {
            var url = first("TPV_TEST_DB_URL", "TPV_ERP_TEST_DB_URL");
            var user = first("TPV_TEST_DB_USERNAME", "TPV_ERP_TEST_DB_USER");
            var password = first("TPV_TEST_DB_PASSWORD", "TPV_ERP_TEST_DB_PASSWORD");
            return url == null || user == null || password == null
                    ? null : new DatabaseEnvironment(url, user, password);
        }

        private static String first(String primary, String legacy) {
            var value = System.getenv(primary);
            return value == null || value.isBlank() ? System.getenv(legacy) : value;
        }
    }
}
