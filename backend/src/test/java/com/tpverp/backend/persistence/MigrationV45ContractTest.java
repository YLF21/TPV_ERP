package com.tpverp.backend.persistence;
import static org.assertj.core.api.Assertions.*;import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.*;import java.util.UUID;import org.flywaydb.core.Flyway;import org.junit.jupiter.api.Test;
class MigrationV45ContractTest {
 @Test void appliesV45AndAcceptsOnlyTicketSnapshotOnRealPostgres() throws Exception {
  var url=System.getenv().getOrDefault("TPV_ERP_TEST_DB_URL","jdbc:postgresql://localhost:5432/tpv_erp_test");var user=System.getenv().getOrDefault("TPV_ERP_TEST_DB_USER","postgres");var password=System.getenv().getOrDefault("TPV_ERP_TEST_DB_PASSWORD","admin");assumeTrue(canConnect(url,user,password),"PostgreSQL de pruebas no disponible");var schema="tpv_v45_"+UUID.randomUUID().toString().replace("-","");
  try {Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).createSchemas(true).load().migrate();try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
   UUID company=UUID.randomUUID();UUID store=UUID.randomUUID();UUID terminal=UUID.randomUUID();var address="{\"linea1\":\"a\",\"ciudad\":\"c\",\"codigoPostal\":\"1\",\"provincia\":\"p\",\"pais\":\"ES\"}";
   s.executeUpdate("insert into "+schema+".empresa(id,tax_id,razon_social,domicilio_fiscal) values ('"+company+"','B1','C','"+address+"')");
   s.executeUpdate("insert into "+schema+".tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda) values ('"+store+"','"+company+"','S','"+address+"','h','UTC','EUR','es','001')");
   s.executeUpdate("insert into "+schema+".terminal(id,tienda_id,nombre,tipo,credential_hash) values ('"+terminal+"','"+store+"','T','TERMINAL_VENTA','h')");
   s.executeUpdate(insert(schema,terminal,"{\"schemaVersion\":1,\"ticket\":{}}"));
   assertThatThrownBy(()->s.executeUpdate(insert(schema,terminal,"{\"schemaVersion\":1,\"command\":{}}"))).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23514"));
  }} finally {try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){s.execute("drop schema if exists "+schema+" cascade");}}
 }
 private static String insert(String schema,UUID terminal,String json){return "insert into "+schema+".pos_card_checkout(id,terminal_id,request_hash,document_snapshot,total,status,gateway_owner,gateway_lease_until,creado_en,actualizado_en) values ('"+UUID.randomUUID()+"','"+terminal+"','"+"a".repeat(64)+"','"+json+"',1,'PENDING','"+UUID.randomUUID()+"',now()+interval '30 sec',now(),now())";}
 private static boolean canConnect(String u,String n,String p){try(var ignored=DriverManager.getConnection(u,n,p)){return true;}catch(Exception e){return false;}}
}
