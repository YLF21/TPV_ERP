package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PaymentTerminalV58PostgreSqlTest {
    @Test
    void migratesAndProtectsReconciliationEventsAsAppendOnly() throws Exception {
        var url=System.getenv().getOrDefault("TPV_ERP_TEST_DB_URL","jdbc:postgresql://localhost:5432/tpv_erp_test");
        var user=System.getenv().getOrDefault("TPV_ERP_TEST_DB_USER","postgres");
        var password=System.getenv().getOrDefault("TPV_ERP_TEST_DB_PASSWORD","admin");
        assumeTrue(canConnect(url,user,password));var schema="tpv_v58_"+UUID.randomUUID().toString().replace("-","");
        try {Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).createSchemas(true).load().migrate();
            try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
                var company=UUID.randomUUID();var store=UUID.randomUUID();var terminal=UUID.randomUUID();var batch=UUID.randomUUID();var event=UUID.randomUUID();
                var address="{\"linea1\":\"a\",\"ciudad\":\"c\",\"codigoPostal\":\"1\",\"provincia\":\"p\",\"pais\":\"ES\"}";
                statement.executeUpdate("insert into "+schema+".empresa(id,tax_id,razon_social,domicilio_fiscal) values ('"+company+"','B1','C','"+address+"')");
                statement.executeUpdate("insert into "+schema+".tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda) values ('"+store+"','"+company+"','S','"+address+"','h','UTC','EUR','es','001')");
                statement.executeUpdate("insert into "+schema+".terminal(id,tienda_id,nombre,tipo,credential_hash) values ('"+terminal+"','"+store+"','T','TERMINAL_VENTA','h')");
                statement.executeUpdate("insert into "+schema+".payment_terminal_reconciliation_batch(id,terminal_id,store_id,company_id,provider,business_date,status,erp_total,provider_total,discrepancy,normalized_code,created_at,updated_at) values ('"+batch+"','"+terminal+"','"+store+"','"+company+"','REDSYS_TPV_PC',current_date,'APPROVED',10,9,-1,'RECONCILED',now(),now())");
                statement.executeUpdate("insert into "+schema+".payment_terminal_reconciliation_event(id,reconciliation_id,status,normalized_code,created_at) values ('"+event+"','"+batch+"','APPROVED','RECONCILED',now())");
                try(var result=statement.executeQuery("select discrepancy from "+schema+".payment_terminal_reconciliation_batch where id='"+batch+"'")){assertThat(result.next()).isTrue();assertThat(result.getBigDecimal(1)).isEqualByComparingTo("-1.00");}
                assertThatThrownBy(()->statement.executeUpdate("delete from "+schema+".payment_terminal_reconciliation_event where id='"+event+"'"))
                        .isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("55000"));
            }} finally {try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){s.execute("drop schema if exists "+schema+" cascade");}}
    }
    private static boolean canConnect(String url,String user,String password){try(var ignored=DriverManager.getConnection(url,user,password)){return true;}catch(Exception e){return false;}}
}
