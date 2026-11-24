package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
class PromotionEngineVersionMigrationPostgreSqlTest {
    @Test
    void upgrades306PreservingHistoricalMarkersAndAllowsSnapshotOnlyVersionThree() throws Exception {
        var url = System.getenv("TPV_ERP_TEST_DB_URL");
        var user = System.getenv("TPV_ERP_TEST_DB_USER");
        var password = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
        var schema = "quantity_engine_" + UUID.randomUUID().toString().replace("-", "");
        var scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(scopedUrl, user, password));
        try {
            jdbc.execute("create schema " + schema);
            jdbc.execute("""
                    create table documento_linea(id integer primary key, tipo_linea varchar(24) not null,
                        precio_cantidad_snapshot jsonb, promocion_motor_version integer,
                        constraint ck_documento_linea_promocion_motor_version check (
                            promocion_motor_version is null or
                            (tipo_linea in ('PROMOTION','QUANTITY_PRICE') and promocion_motor_version = 2)))
                    """);
            jdbc.execute("""
                    insert into documento_linea values
                        (1,'PROMOTION',null,null), (2,'PROMOTION',null,2),
                        (3,'QUANTITY_PRICE',null,2), (4,'PRODUCT','{}'::jsonb,null),
                        (5,'PRODUCT',null,null)
                    """);
            var flyway = FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                    .dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                    .baselineVersion("306").baselineOnMigrate(true).target("307").load();

            flyway.migrate();
            flyway.validate();

            assertThat(jdbc.queryForList("select promocion_motor_version from documento_linea order by id",
                    Integer.class)).containsExactly(null, 2, 2, null, null);
            jdbc.update("update documento_linea set promocion_motor_version=3 where id in (2,3,4)");
            assertThat(jdbc.queryForObject("select count(*) from documento_linea where promocion_motor_version=3",
                    Integer.class)).isEqualTo(3);
            assertThatThrownBy(() -> jdbc.update("update documento_linea set promocion_motor_version=3 where id=5"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("update documento_linea set promocion_motor_version=2 where id=4"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("update documento_linea set promocion_motor_version=4 where id=2"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    @Test
    void upgrades303WithoutReclassifyingExistingLinesAndConstrainsNewMarkers() throws Exception {
        var url = System.getenv("TPV_ERP_TEST_DB_URL");
        var user = System.getenv("TPV_ERP_TEST_DB_USER");
        var password = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
        var schema = "promotion_engine_" + UUID.randomUUID().toString().replace("-", "");
        var scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(scopedUrl, user, password));
        try {
            jdbc.execute("create schema " + schema);
            // Only the table shape read by this additive migration is needed.
            jdbc.execute("create table documento_linea(id integer primary key, tipo_linea varchar(24) not null)");
            jdbc.execute("insert into documento_linea values (1,'PROMOTION'),(2,'PRODUCT')");
            var flyway = FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                    .dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                    .baselineVersion("303").baselineOnMigrate(true).target("304").load();

            flyway.migrate();
            flyway.validate();

            assertThat(jdbc.queryForObject("select count(*) from documento_linea where promocion_motor_version is null",
                    Integer.class)).isEqualTo(2);
            jdbc.update("update documento_linea set promocion_motor_version=2 where id=1");
            assertThat(jdbc.queryForObject("select promocion_motor_version from documento_linea where id=1",
                    Integer.class)).isEqualTo(2);
            assertThatThrownBy(() -> jdbc.update("update documento_linea set promocion_motor_version=2 where id=2"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("update documento_linea set promocion_motor_version=3 where id=1"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }
}
