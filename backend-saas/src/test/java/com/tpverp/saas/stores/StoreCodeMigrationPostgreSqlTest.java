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
class StoreCodeMigrationPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void additiveMigrationBackfillsOnlyKnownSpanishAddressesAndUnambiguousLicenseStores() throws Exception {
        UUID company = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID(), missing = UUID.randomUUID();
        UUID foreign = UUID.randomUUID(), reserved = UUID.randomUUID(), singleLicense = UUID.randomUUID(), sharedLicense = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var sql = connection.createStatement()) {
            sql.execute("""
                    create table saas_store(id uuid primary key,company_id uuid not null,code varchar(3) not null,
                        store_address jsonb,created_at timestamptz not null,unique(company_id,code));
                    create table saas_license(id uuid primary key,company_id uuid not null,valid_until timestamptz);
                    create table saas_pairing_code(license_id uuid,store_id uuid);
                    create table saas_installation(license_id uuid,store_id uuid,active boolean,last_validated_at timestamptz);
                    create table saas_sync_event(installation_id uuid,received_at timestamptz);
                    """);
            try (var insert = connection.prepareStatement("insert into saas_store values(?,?,?,?::jsonb,now())")) {
                Object[][] fixtures = {
                    {first, "001", "{\"pais\":\"ES\",\"codigoPostal\":\"01001\"}"},
                    {second, "002", "{\"pais\":\"ES\",\"codigoPostal\":\"01002\"}"},
                    {missing, "003", null},
                    {foreign, "004", "{\"pais\":\"FR\",\"codigoPostal\":\"01001\"}"},
                    {reserved, "005", "{\"pais\":\"ES\",\"codigoPostal\":\"90001\"}"}
                };
                for (var row : fixtures) {
                    insert.setObject(1, row[0]); insert.setObject(2, company); insert.setString(3, (String) row[1]); insert.setString(4, (String) row[2]); insert.executeUpdate();
                }
            }
            for (UUID id : new UUID[] {singleLicense, sharedLicense}) sql.execute("insert into saas_license values('" + id + "','" + company + "',now())");
            sql.execute("insert into saas_pairing_code values('" + singleLicense + "','" + first + "'),('" + sharedLicense + "','" + first + "'),('" + sharedLicense + "','" + second + "')");
            try (var resource = getClass().getClassLoader().getResourceAsStream("db/migration/V62__store_internal_code_and_lifecycle.sql")) {
                assertThat(resource).isNotNull();
                sql.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var rows = sql.executeQuery("select code,internal_code,active from saas_store order by code")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString("code")).isEqualTo("001"); assertThat(rows.getString("internal_code")).matches("01[0-9]{5}"); assertThat(rows.getBoolean("active")).isTrue();
                assertThat(rows.next()).isTrue(); assertThat(rows.getString("internal_code")).matches("01[0-9]{5}");
                for (int index = 0; index < 3; index++) { assertThat(rows.next()).isTrue(); assertThat(rows.getString("internal_code")).isNull(); }
            }
            try (var rows = sql.executeQuery("select count(distinct internal_code) from saas_store")) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); }
            try (var rows = sql.executeQuery("select store_id from saas_license where id='" + singleLicense + "'")) { rows.next(); assertThat(rows.getObject(1, UUID.class)).isEqualTo(first); }
            try (var rows = sql.executeQuery("select store_id from saas_license where id='" + sharedLicense + "'")) { rows.next(); assertThat(rows.getObject(1)).isNull(); }
            sql.execute("update saas_store set store_address=store_address where internal_code is not null");
            try (var rows = sql.executeQuery("select last_number from saas_store_code_counter where postal_prefix='01'")) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); }
            assertThatThrownBy(() -> sql.execute("update saas_store set internal_code='0109999' where id='" + first + "'"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("permanente");
            sql.execute("update saas_store_code_counter set last_number=99999 where postal_prefix='01'");
            assertThatThrownBy(() -> sql.execute("insert into saas_store(id,company_id,code,store_address,created_at) values('" + UUID.randomUUID() + "','" + company + "','006','{\"pais\":\"ES\",\"codigoPostal\":\"01003\"}',now())"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("agotada");
        }
    }
}
