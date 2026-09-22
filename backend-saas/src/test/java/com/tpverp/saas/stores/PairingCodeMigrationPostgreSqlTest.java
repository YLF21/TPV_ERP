package com.tpverp.saas.stores;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PairingCodeMigrationPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void migrationKeepsUsableCandidateAndAllConsumptionHistoryAndEnforcesUniqueness() throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var sql = connection.createStatement()) {
            sql.execute("""
                    create table saas_store(id uuid primary key, active boolean not null);
                    create table saas_license(id uuid primary key,status text not null,valid_until timestamptz);
                    create table saas_pairing_code(id uuid primary key,store_id uuid,license_id uuid,code text unique,
                        consumed_at timestamptz,expires_at timestamptz,created_at timestamptz,
                        consumed_installation_id uuid,link_recovery_token_hash text);
                    insert into saas_store values ('00000000-0000-0000-0000-000000000001',true);
                    insert into saas_license values
                        ('00000000-0000-0000-0000-000000000002','VALIDA','2099-01-01'),
                        ('00000000-0000-0000-0000-000000000003','BLOQUEADA_MANUAL','2099-01-01');
                    insert into saas_pairing_code values
                        ('00000000-0000-0000-0000-000000000010','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','USABLE',null,'2099-01-01','2020-01-01',null,null),
                        ('00000000-0000-0000-0000-000000000011','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000003','BLOCKED',null,'2099-01-01','2021-01-01',null,null),
                        ('00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','EXPIRED',null,'2020-02-01','2020-01-01',null,null),
                        ('00000000-0000-0000-0000-000000000013','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','CONSUMED','2020-01-02','2020-02-01','2020-01-01','00000000-0000-0000-0000-000000000014','recovery-context');
                    create table original_pairing_history as select * from saas_pairing_code;
                    """);
            try (var resource = getClass().getClassLoader().getResourceAsStream("db/migration/V69__pairing_code_revocation_and_store_uniqueness.sql")) {
                assertThat(resource).isNotNull();
                sql.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var rows = sql.executeQuery("select code from saas_pairing_code where revoked_at is null and consumed_at is null")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("USABLE"); assertThat(rows.next()).isFalse();
            }
            try (var rows = sql.executeQuery("select count(*) from saas_pairing_code where revocation_reason='LEGACY_REPLACED'")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(2);
            }
            try (var rows = sql.executeQuery("""
                    select count(*) from saas_pairing_code p join original_pairing_history o using(id)
                    where p.code=o.code and p.expires_at=o.expires_at and p.created_at=o.created_at
                      and p.consumed_at is not distinct from o.consumed_at
                      and p.consumed_installation_id is not distinct from o.consumed_installation_id
                      and p.link_recovery_token_hash is not distinct from o.link_recovery_token_hash
                    """)) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(4); }
            String insert = """
                    insert into saas_pairing_code(id,store_id,license_id,code,expires_at,created_at)
                    select gen_random_uuid(),store_id,license_id,'NEW',now()+interval '30 minutes',now()
                    from saas_pairing_code where code='USABLE'
                    """;
            assertThatThrownBy(() -> sql.execute(insert)).isInstanceOf(SQLException.class).hasMessageContaining("uk_saas_pairing_open_store");
            sql.execute("update saas_pairing_code set revoked_at=now(),revocation_reason='REPLACED' where code='USABLE'");
            sql.execute(insert);
            assertThatThrownBy(() -> sql.execute("update saas_pairing_code set code='USABLE' where code='NEW'"))
                    .isInstanceOf(SQLException.class);
            try (var rows = sql.executeQuery("select revoked_at from saas_pairing_code where code='CONSUMED'")) {
                rows.next(); assertThat(rows.getObject(1)).isNull();
            }
        }
    }
}
