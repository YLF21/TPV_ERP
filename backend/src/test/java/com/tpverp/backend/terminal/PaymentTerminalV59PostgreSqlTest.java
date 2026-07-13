package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PaymentTerminalV59PostgreSqlTest {
    @Test void upgradesV58AndEnforcesOneActiveVersionPerOpaqueReference() throws Exception {
        var url=System.getenv().getOrDefault("TPV_ERP_TEST_DB_URL","jdbc:postgresql://localhost:5432/tpv_erp_test");
        var user=System.getenv().getOrDefault("TPV_ERP_TEST_DB_USER","tpv_erp_test");
        var password=System.getenv().getOrDefault("TPV_ERP_TEST_DB_PASSWORD","admin");
        assumeTrue(canConnect(url,user,password)); var schema="tpv_v59_"+UUID.randomUUID().toString().replace("-","");
        try {
            var base=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).createSchemas(true).target("58").load();base.migrate();
            var company=UUID.randomUUID();var store=UUID.randomUUID();var terminal=UUID.randomUUID();var legacyConfiguration=UUID.randomUUID();var address="{\"linea1\":\"a\",\"ciudad\":\"c\",\"codigoPostal\":\"1\",\"provincia\":\"p\",\"pais\":\"ES\"}";
            try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
                s.executeUpdate("insert into "+schema+".empresa(id,tax_id,razon_social,domicilio_fiscal) values ('"+company+"','B1','C','"+address+"')");
                s.executeUpdate("insert into "+schema+".tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda) values ('"+store+"','"+company+"','S','"+address+"','h','UTC','EUR','es','001')");
                s.executeUpdate("insert into "+schema+".terminal(id,tienda_id,nombre,tipo,credential_hash) values ('"+terminal+"','"+store+"','T','TERMINAL_VENTA','h')");
                s.executeUpdate("insert into "+schema+".configuracion_pago_terminal(id,terminal_id,card_mode,provider,enabled,test_mode,provider_parameters,secret_reference) values ('"+legacyConfiguration+"','"+terminal+"','INTEGRATED','REDSYS_TPV_PC',true,true,'{}','legacy:raw-reference')");
            }
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().migrate();
            try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
                var reference="pts_0123456789abcdef0123456789abcdef";
                try(var result=s.executeQuery("select secret_reference,secret_reference_version,enabled,last_connection_status from "+schema+".configuracion_pago_terminal where id='"+legacyConfiguration+"'")){assertThat(result.next()).isTrue();assertThat(result.getString(1)).isNull();assertThat(result.getObject(2)).isNull();assertThat(result.getBoolean(3)).isFalse();assertThat(result.getString(4)).isEqualTo("ERROR");}
                var prefix="insert into "+schema+".payment_terminal_secret_reference(id,company_id,store_id,terminal_id,opaque_reference,provider,version,protected_value,active,created_at) values (";
                s.executeUpdate(prefix+"'"+UUID.randomUUID()+"','"+company+"','"+store+"','"+terminal+"','"+reference+"','REDSYS_TPV_PC',1,decode('0102','hex'),true,now())");
                assertThatThrownBy(()->s.executeUpdate(prefix+"'"+UUID.randomUUID()+"','"+company+"','"+store+"','"+terminal+"','"+reference+"','REDSYS_TPV_PC',2,decode('0304','hex'),true,now())"))
                        .isInstanceOf(SQLException.class);
                s.executeUpdate("update "+schema+".payment_terminal_secret_reference set active=false,retired_at=now() where opaque_reference='"+reference+"'");
                s.executeUpdate(prefix+"'"+UUID.randomUUID()+"','"+company+"','"+store+"','"+terminal+"','"+reference+"','REDSYS_TPV_PC',2,decode('0304','hex'),true,now())");
                s.executeUpdate("update "+schema+".payment_terminal_secret_reference set active=false,retired_at=now() where opaque_reference='"+reference+"'");
                var executor=java.util.concurrent.Executors.newFixedThreadPool(2);var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<Boolean> rotate=()->{ready.countDown();start.await();try(var cx=DriverManager.getConnection(url,user,password);var sx=cx.createStatement()){sx.executeUpdate(prefix+"'"+UUID.randomUUID()+"','"+company+"','"+store+"','"+terminal+"','"+reference+"','REDSYS_TPV_PC',"+(3+java.util.concurrent.ThreadLocalRandom.current().nextInt(1000))+",decode('0506','hex'),true,now())");return true;}catch(SQLException duplicate){return false;}};
                var first=executor.submit(rotate);var second=executor.submit(rotate);ready.await();start.countDown();var successes=(first.get()?1:0)+(second.get()?1:0);executor.shutdownNow();assertThat(successes).isEqualTo(1);
            }
        } finally {try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){s.execute("drop schema if exists "+schema+" cascade");}}
    }
    private static boolean canConnect(String url,String user,String password){try(var ignored=DriverManager.getConnection(url,user,password)){return true;}catch(Exception e){return false;}}
}
