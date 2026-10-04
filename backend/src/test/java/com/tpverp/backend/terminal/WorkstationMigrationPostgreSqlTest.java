package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.*;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
class WorkstationMigrationPostgreSqlTest {
    @Test void upgradesV268PreservingLegacyIdentityNamesCredentialsAndCashHistory() throws Exception {
        var url = System.getenv("TPV_ERP_TEST_DB_URL"); var user = System.getenv("TPV_ERP_TEST_DB_USER");
        var password = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
        var schema = "workstation_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
        try {
            FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure()).dataSource(url, user, password)
                    .schemas(schema).defaultSchema(schema).createSchemas(true).target("268").load().migrate();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(scopedUrl, user, password));
            var company = UUID.randomUUID(); var store = UUID.randomUUID(); var server = UUID.randomUUID();
            var legacy = UUID.randomUUID(); var role = UUID.randomUUID(); var cashier = UUID.randomUUID(); var cash = UUID.randomUUID();
            var address = "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}";
            jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,'B1','Test',cast(? as jsonb))", company, address);
            jdbc.update("""
                    insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda)
                    values (?,?,'Shop',cast(? as jsonb),'hash','UTC','EUR','es','001')
                    """, store, company, address);
            jdbc.update("insert into terminal(id,tienda_id,nombre,tipo,credential_hash) values (?,?,'SERVIDOR','SERVIDOR','server-hash'),(?,?,'Caja antigua','TERMINAL_VENTA','legacy-hash')",
                    server, store, legacy, store);
            jdbc.update("insert into rol(id,tienda_id,nombre) values (?,?,'CAJA')", role, store);
            jdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id) values (?,?,'USER','user','hash',?)", cashier, store, role);
            jdbc.update("""
                    insert into sesion_caja(id,tienda_id,terminal_id,usuario_apertura_id,abierta_en,fondo_inicial,estado,cierre_tardio)
                    values (?,?,?,?,'2026-09-03T10:00:00Z',25,'ABIERTA',false)
                    """, cash, store, legacy, cashier);
            FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure()).dataSource(url, user, password)
                    .schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForObject("select workstation_code from terminal where id=?", String.class, server)).isEqualTo("001");
            assertThat(jdbc.queryForMap("select nombre,credential_hash,workstation_code,current_binding_id from terminal where id=?", legacy))
                    .containsEntry("nombre", "Caja antigua").containsEntry("credential_hash", "legacy-hash")
                    .containsEntry("workstation_code", null).containsEntry("current_binding_id", null);
            assertThat(jdbc.queryForMap("select terminal_id,estado,fondo_inicial from sesion_caja where id=?", cash))
                    .containsEntry("terminal_id", legacy).containsEntry("estado", "ABIERTA")
                    .containsEntry("fondo_inicial", new java.math.BigDecimal("25.00"));
            assertThat(jdbc.queryForObject("select count(*) from terminal_physical_binding", Integer.class)).isZero();
            assertThatThrownBy(() -> jdbc.update("update terminal set workstation_code='001' where id=?", legacy))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            jdbc.update("update terminal set workstation_code='1000' where id=?", legacy);
            assertThat(jdbc.queryForObject("select workstation_code from terminal where id=?", String.class, legacy)).isEqualTo("1000");
        } finally {
            try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }
}
