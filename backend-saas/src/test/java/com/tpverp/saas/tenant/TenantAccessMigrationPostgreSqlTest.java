package com.tpverp.saas.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.flyway.default-schema=tenant_store_access_test",
        "spring.datasource.hikari.schema=tenant_store_access_test",
        "spring.jpa.properties.hibernate.default_schema=tenant_store_access_test"
})
@ActiveProfiles("test")
class TenantAccessMigrationPostgreSqlTest {
    @Autowired DataSource dataSource;

    @Test
    void existingUsersRetainOnlyTheirPreviousCompanyAndStoresAtMigrationTime() throws Exception {
        String schema = "tenant_access_migration_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .dataSource(dataSource).locations("classpath:db/migration")
                .defaultSchema(schema).schemas(schema).target("60").load().migrate();
        try (var connection = dataSource.getConnection()) {
            connection.setSchema(schema);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            UUID company = UUID.randomUUID(), otherCompany = UUID.randomUUID();
            UUID firstStore = UUID.randomUUID(), secondStore = UUID.randomUUID(), otherStore = UUID.randomUUID();
            UUID manager = UUID.randomUUID(), viewer = UUID.randomUUID();
            for (UUID id : new UUID[]{company, otherCompany}) {
                jdbc.update("insert into saas_company(id,name,tax_id,taxpayer_type,tax_regime,created_at) values (?, 'Migration test', ?, 'SOCIEDAD','IVA', now())",
                        id, id.toString().substring(0, 20));
            }
            addStore(jdbc, company, firstStore, "001");
            addStore(jdbc, company, secondStore, "002");
            addStore(jdbc, otherCompany, otherStore, "001");
            for (UUID userId : new UUID[]{manager, viewer}) {
                jdbc.update("insert into saas_tenant_user(id,company_id,username,password_hash,role_name,active,created_at) values (?,?,?,?,?,true,now())",
                        userId, company, "migration-" + userId, "synthetic-unused-hash", userId.equals(manager) ? "MANAGER" : "VIEWER");
            }
            Flyway.configure().configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                    .dataSource(dataSource).locations("classpath:db/migration")
                    .defaultSchema(schema).schemas(schema).target("61").load().migrate();
            assertThat(jdbc.queryForObject("select count(*) from saas_tenant_company_access", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForList("select store_id from saas_tenant_store_access where user_id = ?", UUID.class, manager))
                    .containsExactlyInAnyOrder(firstStore, secondStore).doesNotContain(otherStore);
            assertThat(jdbc.queryForObject("select 'WRITE_MASTERS' = any(company_privileges) from saas_tenant_company_access where user_id = ?",
                    Boolean.class, manager)).isTrue();
            assertThat(jdbc.queryForObject("select 'WRITE_MASTERS' = any(company_privileges) from saas_tenant_company_access where user_id = ?",
                    Boolean.class, viewer)).isFalse();
            UUID future = UUID.randomUUID();
            addStore(jdbc, company, future, "003");
            assertThat(jdbc.queryForObject("select count(*) from saas_tenant_store_access where store_id = ?", Integer.class, future)).isZero();
            assertThat(jdbc.queryForObject("select password_hash from saas_tenant_user where id = ?", String.class, manager))
                    .isEqualTo("synthetic-unused-hash");
        }
    }

    private void addStore(JdbcTemplate jdbc, UUID company, UUID store, String code) {
        jdbc.update("insert into saas_store(id,company_id,code,name,created_at) values (?,?,?,'Migration store',now())", store, company, code);
    }
}
