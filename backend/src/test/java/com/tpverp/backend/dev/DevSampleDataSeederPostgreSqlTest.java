package com.tpverp.backend.dev;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.document.CommercialDocumentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles({"test", "dev"})
@SpringBootTest
class DevSampleDataSeederPostgreSqlTest {

    private static final String SCHEMA =
            "tpv_erp_dev_seed_" + UUID.randomUUID().toString().replace("-", "");
    private static final Path KEY_DIRECTORY = Path.of(
            "target", "test-installation-keys", UUID.randomUUID().toString());
    private static final Path CERTIFICATE_DIRECTORY = Path.of(
            "target", "test-verifactu-certificates", UUID.randomUUID().toString());

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> databaseUrl()
                + (databaseUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> environment("TPV_TEST_DB_USERNAME", "tpv_erp_test"));
        registry.add("spring.datasource.password", () -> environment("TPV_TEST_DB_PASSWORD", "admin"));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("tpv.installation.key-directory", KEY_DIRECTORY::toString);
        registry.add("tpv.verifactu.secret-directory", CERTIFICATE_DIRECTORY::toString);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var connection = DriverManager.getConnection(
                databaseUrl(),
                environment("TPV_TEST_DB_USERNAME", "tpv_erp_test"),
                environment("TPV_TEST_DB_PASSWORD", "admin"));
             var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
        deleteDirectory(KEY_DIRECTORY);
        deleteDirectory(CERTIFICATE_DIRECTORY);
    }

    @Test
    void seedsFrontendDataForEveryDocumentType() {
        var documentTypes = jdbc.queryForList(
                "select tipo from documento where numero is not null", String.class);

        assertThat(documentTypes)
                .containsAll(DevSampleDataSeeder.documentTypes().stream()
                        .map(CommercialDocumentType::name)
                        .toList());
        assertThat(count("producto")).isGreaterThanOrEqualTo(2);
        assertThat(count("cliente")).isGreaterThanOrEqualTo(1);
        assertThat(count("proveedor")).isGreaterThanOrEqualTo(1);
        assertThat(count("salida_almacen")).isGreaterThanOrEqualTo(1);
        assertThat(count("terminal")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void seedsAboutOneThousandFrontendDocumentsWithDifferentDates() {
        assertThat(count("documento")).isGreaterThanOrEqualTo(1_000);
        assertThat(jdbc.queryForObject("select count(distinct fecha) from documento", Integer.class))
                .isGreaterThanOrEqualTo(90);
    }

    private Integer count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String databaseUrl() {
        return environment("TPV_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
    }

    private static String environment(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
