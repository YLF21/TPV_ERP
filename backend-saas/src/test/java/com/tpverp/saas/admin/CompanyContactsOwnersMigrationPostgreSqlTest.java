package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CompanyContactsOwnersMigrationPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void legacyContactsAndCommercialHistoryRemainIntactAndOwnersAreNotInvented() throws Exception {
        UUID existing = UUID.randomUUID(), fresh = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var sql = connection.createStatement()) {
            sql.execute("""
                    create table saas_company(id uuid primary key,name varchar(200),commercial_profile varchar(16) not null default 'MAYORISTA');
                    create table saas_company_operations(company_id uuid primary key,contact_name varchar(160),contact_email varchar(160),notes text,monthly_price varchar(32));
                    """);
            sql.execute("insert into saas_company values('" + existing + "','Historica','MINORISTA')");
            sql.execute("insert into saas_company_operations values('" + existing + "','Contacto historico','old@example.test','Conservar','199.99')");
            try (var resource = getClass().getClassLoader().getResourceAsStream("db/migration/V67__company_contacts_owners.sql")) {
                assertThat(resource).isNotNull();
                sql.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var row = sql.executeQuery("select commercial_profile,owners from saas_company where id='" + existing + "'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("commercial_profile")).isEqualTo("MINORISTA");
                assertThat(row.getString("owners")).isEqualTo("[]");
            }
            try (var row = sql.executeQuery("select * from saas_company_operations where company_id='" + existing + "'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("contact_name")).isEqualTo("Contacto historico");
                assertThat(row.getString("contact_email")).isEqualTo("old@example.test");
                assertThat(row.getString("contact_phone")).isNull();
                assertThat(row.getString("notes")).isEqualTo("Conservar");
                assertThat(row.getString("monthly_price")).isEqualTo("199.99");
            }
            sql.execute("insert into saas_company(id,name) values('" + fresh + "','Sin actividad corporativa')");
            try (var row = sql.executeQuery("select commercial_profile from saas_company where id='" + fresh + "'")) {
                assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isNull();
            }
            assertThatThrownBy(() -> sql.execute("update saas_company set owners='{}'::jsonb where id='" + existing + "'"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("ck_saas_company_owners_array");
        }
    }
}
