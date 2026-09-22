package com.tpverp.saas.stores;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class StoreCommercialMigrationPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void existingTaxAndLicenseTermsArePreservedWithoutInventingStorePrices() throws Exception {
        UUID company = UUID.randomUUID(), single = UUID.randomUUID(), multiple = UUID.randomUUID(), unlicensed = UUID.randomUUID();
        UUID mixed = UUID.randomUUID(), foreignLinked = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var sql = connection.createStatement()) {
            sql.execute("""
                    create table saas_company(id uuid primary key,tax_regime varchar(16) not null);
                    create table saas_company_operations(company_id uuid primary key,monthly_price varchar(32));
                    create table saas_store(id uuid primary key,company_id uuid not null);
                    create table saas_license(id uuid primary key,store_id uuid,valid_until timestamptz,max_windows integer,max_pda integer);
                    create table saas_pairing_code(license_id uuid,store_id uuid);
                    create table saas_installation(license_id uuid,store_id uuid);
                    """);
            sql.execute("insert into saas_company values('" + company + "','IGIC')");
            sql.execute("insert into saas_company_operations values('" + company + "','500.00')");
            for (UUID id : new UUID[] {single, multiple, unlicensed, mixed, foreignLinked}) {
                sql.execute("insert into saas_store values('" + id + "','" + company + "')");
            }
            sql.execute("insert into saas_license values('" + UUID.randomUUID() + "','" + single + "','2040-01-01',7,3)");
            for (int index = 0; index < 2; index++) {
                sql.execute("insert into saas_license values('" + UUID.randomUUID() + "','" + multiple + "','2040-01-01',9,4)");
            }
            sql.execute("insert into saas_license values('" + UUID.randomUUID() + "','" + mixed + "','2040-01-01',5,2)");
            UUID sharedLicense = UUID.randomUUID(), foreignLicense = UUID.randomUUID();
            sql.execute("insert into saas_license values('" + sharedLicense + "',null,'2040-01-01',8,4)");
            sql.execute("insert into saas_pairing_code values('" + sharedLicense + "','" + mixed + "')");
            sql.execute("insert into saas_license values('" + foreignLicense + "','" + foreignLinked + "','2040-01-01',6,2)");
            sql.execute("insert into saas_installation values('" + foreignLicense + "','" + multiple + "')");
            try (var resource = getClass().getClassLoader().getResourceAsStream("db/migration/V65__store_commercial_configuration.sql")) {
                assertThat(resource).isNotNull();
                sql.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var rows = sql.executeQuery("select tax_regime,service_price,billing_period,max_windows,max_pda,valid_until from saas_store where id='" + single + "'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("tax_regime")).isEqualTo("IGIC");
                assertThat(rows.getBigDecimal("service_price")).isNull();
                assertThat(rows.getString("billing_period")).isNull();
                assertThat(rows.getInt("max_windows")).isEqualTo(7);
                assertThat(rows.getInt("max_pda")).isEqualTo(3);
                assertThat(rows.getTimestamp("valid_until").toInstant().toString()).isEqualTo("2040-01-01T00:00:00Z");
            }
            try (var rows = sql.executeQuery("select valid_until,service_price from saas_store where id='" + multiple + "'")) {
                rows.next(); assertThat(rows.getTimestamp(1)).isNull(); assertThat(rows.getBigDecimal(2)).isNull();
            }
            for (UUID id : new UUID[] {mixed, foreignLinked}) {
                try (var rows = sql.executeQuery("select valid_until from saas_store where id='" + id + "'")) {
                    rows.next(); assertThat(rows.getTimestamp(1)).isNull();
                }
            }
            try (var rows = sql.executeQuery("select monthly_price from saas_company_operations where company_id='" + company + "'")) {
                rows.next(); assertThat(rows.getString(1)).isEqualTo("500.00");
            }
            sql.execute("update saas_company set tax_regime=null where id='" + company + "'");
            sql.execute("update saas_store set tax_regime='IVA' where id='" + unlicensed + "'");
            assertThatThrownBy(() -> sql.execute("update saas_store set tax_regime='IVA' where id='" + single + "'"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("no puede cambiar");
            assertThatThrownBy(() -> sql.execute("update saas_store set service_price=20 where id='" + unlicensed + "'"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("ck_saas_store_price_period");
            sql.execute("update saas_store set service_price=120.50,billing_period='ANNUAL' where id='" + unlicensed + "'");
            assertThatThrownBy(() -> sql.execute("update saas_store set max_windows=0 where id='" + unlicensed + "'"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("ck_saas_store_terminal_limits");
        }
    }
}
