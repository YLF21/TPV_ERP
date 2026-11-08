package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "TPV_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_TEST_DB_PASSWORD", matches = ".+")
class MigrationV288PostgreSqlTest {

    @Test
    void widensExistingDiscountsWithoutLosingValuesOrRangeConstraint() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("TPV_TEST_DB_URL"),
                System.getenv("TPV_TEST_DB_USERNAME"), System.getenv("TPV_TEST_DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                // A connection-local table keeps this migration regression isolated from application data.
                statement.execute("""
                        create temporary table producto (
                            id integer primary key,
                            descuento_compra_porcentaje numeric(5,2),
                            constraint ck_producto_descuento_compra_porcentaje
                                check (descuento_compra_porcentaje is null
                                    or descuento_compra_porcentaje between 0 and 100)
                        ) on commit drop
                        """);
                statement.execute("set local search_path to pg_temp");
                statement.execute("insert into producto values (1, null), (2, 0), (3, 100), (4, 24.87)");
                try (var input = getClass().getClassLoader().getResourceAsStream(
                        "db/migration/V288__producto_descuento_compra_cinco_decimales.sql")) {
                    assertThat(input).isNotNull();
                    statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
                try (var rows = statement.executeQuery("select descuento_compra_porcentaje from producto order by id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBigDecimal(1)).isNull();
                    for (String expected : new String[] {"0", "100", "24.87"}) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getBigDecimal(1)).isEqualByComparingTo(expected);
                    }
                }
                statement.execute("update producto set descuento_compra_porcentaje = 24.87531 where id = 4");
                try (var rows = statement.executeQuery("select descuento_compra_porcentaje from producto where id = 4")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBigDecimal(1)).isEqualTo(new BigDecimal("24.87531"));
                }
                for (String invalid : new String[] {"-0.00001", "100.00001"}) {
                    var savepoint = connection.setSavepoint();
                    assertThatThrownBy(() -> statement.execute(
                            "update producto set descuento_compra_porcentaje = " + invalid + " where id = 4"))
                            .isInstanceOf(SQLException.class)
                            .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
                    connection.rollback(savepoint);
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
