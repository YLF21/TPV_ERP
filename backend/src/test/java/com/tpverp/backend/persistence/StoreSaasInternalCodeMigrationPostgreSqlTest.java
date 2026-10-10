package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
class StoreSaasInternalCodeMigrationPostgreSqlTest {
    @Test
    void addsNullablePresentationCodeWithoutChangingExistingLocalOrFiscalIdentity() throws Exception {
        var schema = "store_code_" + UUID.randomUUID().toString().replace("-", "");
        var id = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(System.getenv("TPV_ERP_TEST_DB_URL"),
                System.getenv("TPV_ERP_TEST_DB_USER"), System.getenv("TPV_ERP_TEST_DB_PASSWORD"));
                var statement = connection.createStatement()) {
            try {
                statement.execute("create schema " + schema);
                statement.execute("set search_path to " + schema);
                statement.execute("create table tienda(id uuid primary key, codigo_tienda varchar(3) not null)");
                statement.execute("insert into tienda values ('" + id + "','001')");
                statement.execute(Files.readString(Path.of("src/main/resources/db/migration/V271__store_saas_internal_code.sql")));
                try (var result = statement.executeQuery("select id,codigo_tienda,saas_internal_code from tienda")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject("id", UUID.class)).isEqualTo(id);
                    assertThat(result.getString("codigo_tienda")).isEqualTo("001");
                    assertThat(result.getString("saas_internal_code")).isNull();
                }
                statement.execute("update tienda set saas_internal_code='0100001'");
                try (var result = statement.executeQuery("select saas_internal_code from tienda")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("0100001");
                }
                for (String invalid : new String[] {"001", "0000001", "5300001", "3500000", "35abcde"}) {
                    assertThatThrownBy(() -> statement.execute("update tienda set saas_internal_code='" + invalid + "'"))
                            .isInstanceOf(java.sql.SQLException.class);
                }
            } finally {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }
}
