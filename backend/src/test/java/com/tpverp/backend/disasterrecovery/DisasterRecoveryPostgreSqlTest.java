package com.tpverp.backend.disasterrecovery;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisasterRecoveryPostgreSqlTest {
    @Test void restoredQuarantineBlocksBusinessWritesUntilActivationAndNeverChangesFiscalOrCommercialCounters()throws Exception {
        String url=System.getenv("TPV_BACKUP_TEST_DB_URL");assumeTrue(url!=null&&!url.isBlank(),"Disposable PostgreSQL required");
        String schema="recovery_guard_"+UUID.randomUUID().toString().replace("-","");
        try(var db=DriverManager.getConnection(url,System.getenv().getOrDefault("TPV_BACKUP_TEST_DB_USER","postgres"),System.getenv().getOrDefault("TPV_BACKUP_TEST_DB_PASSWORD",""));var statement=db.createStatement()) {
            statement.execute("create schema "+schema);statement.execute("set search_path to "+schema);
            try {
                statement.execute("create table instalacion(id uuid primary key); create table contador_documento(ultimo_numero bigint not null); create table cadena_fiscal(ultima_secuencia bigint not null,ultima_huella text not null); create table sync_outbox(event_id uuid primary key,estado text not null)");
                UUID installation=UUID.randomUUID(),recovery=UUID.randomUUID();statement.execute("insert into instalacion values ('"+installation+"');insert into contador_documento values(87);insert into cadena_fiscal values(42,'immutable-hash');insert into sync_outbox values('"+UUID.randomUUID()+"','PENDIENTE')");
                try(var input=getClass().getResourceAsStream("/db/migration/V278__supported_disaster_recovery.sql")){statement.execute(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
                statement.execute("insert into disaster_recovery(id,installation_id,backup_id,backup_sha256,state) values ('"+recovery+"','"+installation+"','"+UUID.randomUUID()+"','"+"a".repeat(64)+"','QUARANTINED')");
                assertThatThrownBy(()->statement.executeUpdate("update contador_documento set ultimo_numero=88")).isInstanceOf(SQLException.class).hasMessageContaining("QUARANTINED");
                assertThatThrownBy(()->statement.executeUpdate("update sync_outbox set estado='ENVIADO'")).isInstanceOf(SQLException.class).hasMessageContaining("QUARANTINED");
                db.setAutoCommit(false);OfflineDisasterRecoveryCli.maintenance(db,recovery);statement.executeUpdate("update disaster_recovery set state='ACTIVE' where id='"+recovery+"'");db.commit();db.setAutoCommit(true);
                try(var rows=statement.executeQuery("select ultimo_numero,ultima_secuencia,ultima_huella from contador_documento cross join cadena_fiscal")){rows.next();assertThat(rows.getLong(1)).isEqualTo(87);assertThat(rows.getLong(2)).isEqualTo(42);assertThat(rows.getString(3)).isEqualTo("immutable-hash");}
                try(var rows=statement.executeQuery("select estado from sync_outbox")){rows.next();assertThat(rows.getString(1)).isEqualTo("PENDIENTE");}
            }finally{db.setAutoCommit(true);statement.execute("drop schema "+schema+" cascade");}
        }
    }
}
