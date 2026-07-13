package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PaymentTerminalV57PostgreSqlTest {
    @Test
    void upgradesV56DataWithSemanticLegacyConfigurationAndAppendOnlyEvent() throws Exception {
        var url = System.getenv().getOrDefault("TPV_ERP_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
        var user = System.getenv().getOrDefault("TPV_ERP_TEST_DB_USER", "postgres");
        var password = System.getenv().getOrDefault("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        var schema = "tpv_v57_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                    .createSchemas(true).target("56").load().migrate();
            var checkout = UUID.randomUUID();
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                var company = UUID.randomUUID();
                var store = UUID.randomUUID();
                var terminal = UUID.randomUUID();
                var address = "{\"linea1\":\"a\",\"ciudad\":\"c\",\"codigoPostal\":\"1\",\"provincia\":\"p\",\"pais\":\"ES\"}";
                statement.executeUpdate("insert into " + schema + ".empresa(id,tax_id,razon_social,domicilio_fiscal) values ('" + company + "','B1','C','" + address + "')");
                statement.executeUpdate("insert into " + schema + ".tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda) values ('" + store + "','" + company + "','S','" + address + "','h','UTC','EUR','es','001')");
                statement.executeUpdate("insert into " + schema + ".terminal(id,tienda_id,nombre,tipo,credential_hash) values ('" + terminal + "','" + store + "','T','TERMINAL_VENTA','h')");
                statement.executeUpdate("insert into " + schema + ".pos_card_checkout(id,terminal_id,request_hash,document_snapshot,total,status,reference,authorization_code,message,creado_en,actualizado_en,completado_en) values ('"
                        + checkout + "','" + terminal + "','" + "a".repeat(64)
                        + "','{\"schemaVersion\":1,\"ticket\":{}}',12.10,'APPROVED','REF','AUTH','ok',now(),now(),now())");
            }
            Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema).load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("select provider,mode,configuration_hash,configuration_version,external_reference,authorization_code from " + schema + ".payment_terminal_operation where id='" + checkout + "'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("provider")).isEqualTo("REDSYS_TPV_PC");
                    assertThat(result.getString("mode")).isEqualTo("SIMULATED");
                    assertThat(result.getString("configuration_hash")).isNull();
                    assertThat(result.getLong("configuration_version")).isEqualTo(-1);
                    assertThat(result.getString("external_reference")).isEqualTo("REF");
                    assertThat(result.getString("authorization_code")).isEqualTo("AUTH");
                }
                assertThatThrownBy(() -> statement.executeUpdate("update " + schema + ".payment_terminal_event set diagnostic='changed' where operation_id='" + checkout + "'"))
                        .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("55000"));
                assertThatThrownBy(() -> statement.executeUpdate("delete from " + schema + ".payment_terminal_event where operation_id='" + checkout + "'"))
                        .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("55000"));
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static boolean canConnect(String url, String user, String password) {
        try (var ignored = DriverManager.getConnection(url, user, password)) { return true; }
        catch (Exception ignored) { return false; }
    }
}
