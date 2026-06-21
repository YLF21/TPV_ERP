package com.tpverp.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.AfterAll;
import java.sql.DriverManager;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@ActiveProfiles("test")
@SpringBootTest
class TpvErpBackendApplicationTests {

	private static final String SCHEMA =
			"tpv_erp_context_" + UUID.randomUUID().toString().replace("-", "");
	private static final Path KEY_DIRECTORY = Path.of(
			"target", "test-installation-keys", UUID.randomUUID().toString());

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> databaseUrl()
				+ (databaseUrl().contains("?") ? "&" : "?")
				+ "currentSchema=" + SCHEMA);
		registry.add("spring.datasource.username", () -> environment("TPV_TEST_DB_USERNAME", "tpv_erp_test"));
		registry.add("spring.datasource.password", () -> environment("TPV_TEST_DB_PASSWORD", ""));
		registry.add("spring.flyway.schemas", () -> SCHEMA);
		registry.add("spring.flyway.default-schema", () -> SCHEMA);
		registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
		registry.add("tpv.installation.key-directory", KEY_DIRECTORY::toString);
	}

	@AfterAll
	static void dropSchema() throws Exception {
		try (var connection = DriverManager.getConnection(
				databaseUrl(),
				environment("TPV_TEST_DB_USERNAME", "tpv_erp_test"),
				environment("TPV_TEST_DB_PASSWORD", ""));
				var statement = connection.createStatement()) {
			statement.execute("drop schema if exists " + SCHEMA + " cascade");
		}
		if (Files.exists(KEY_DIRECTORY)) {
			try (var paths = Files.walk(KEY_DIRECTORY)) {
				paths.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (Exception exception) {
						throw new IllegalStateException(exception);
					}
				});
			}
		}
	}

	@Test
	void contextLoads() {
	}

	private static String databaseUrl() {
		return environment("TPV_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
	}

	private static String environment(String name, String fallback) {
		var value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value;
	}

}
