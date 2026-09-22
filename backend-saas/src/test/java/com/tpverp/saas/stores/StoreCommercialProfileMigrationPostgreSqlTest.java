package com.tpverp.saas.stores;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class StoreCommercialProfileMigrationPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void preservesHistoricalProfilesAndRequiresExplicitChoiceWithoutLegacyCompanySetting() throws Exception {
        UUID wholesale = UUID.randomUUID(), retail = UUID.randomUUID(), newCompany = UUID.randomUUID();
        UUID wholesaleStore = UUID.randomUUID(), retailStore = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var sql = connection.createStatement()) {
            sql.execute("create table saas_company(id uuid primary key, commercial_profile varchar(16))");
            sql.execute("create table saas_store(id uuid primary key, company_id uuid not null references saas_company(id))");
            sql.execute("insert into saas_company values('" + wholesale + "','MAYORISTA'),('" + retail + "','MINORISTA'),('" + newCompany + "',null)");
            sql.execute("insert into saas_store values('" + wholesaleStore + "','" + wholesale + "'),('" + retailStore + "','" + retail + "')");
            try (var resource = getClass().getClassLoader().getResourceAsStream("db/migration/V68__store_commercial_profile.sql")) {
                assertThat(resource).isNotNull();
                sql.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var rows = sql.executeQuery("select count(*) from saas_store s join saas_company c on c.id=s.company_id where s.commercial_profile=c.commercial_profile")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(2);
            }
            UUID legacyInsert = UUID.randomUUID();
            sql.execute("insert into saas_store(id,company_id) values('" + legacyInsert + "','" + retail + "')");
            try (var rows = sql.executeQuery("select commercial_profile from saas_store where id='" + legacyInsert + "'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("MINORISTA");
            }
            assertThatThrownBy(() -> sql.execute("insert into saas_store(id,company_id) values('" + UUID.randomUUID() + "','" + newCompany + "')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("commercial_profile");
            sql.execute("insert into saas_store values('" + UUID.randomUUID() + "','" + newCompany + "','MINORISTA')");
            sql.execute("update saas_store set commercial_profile='MINORISTA' where id='" + wholesaleStore + "'");
            try (var rows = sql.executeQuery("select commercial_profile from saas_company where id='" + wholesale + "'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("MAYORISTA");
            }
            assertThatThrownBy(() -> sql.execute("update saas_store set commercial_profile='OTHER' where id='" + wholesaleStore + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_saas_store_commercial_profile");
            assertThatThrownBy(() -> sql.execute("update saas_store set commercial_profile=null where id='" + wholesaleStore + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("commercial_profile");
        }
    }
}
