package com.tpverp.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.verifactu.ConfiguredVerifactuTransport;
import com.tpverp.backend.verifactu.ManagedCertificateKeyStoreFactory;
import com.tpverp.backend.verifactu.VerifactuCertificateSecretStore;
import com.tpverp.backend.verifactu.VerifactuTransport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
	private static final Path CERTIFICATE_DIRECTORY = Path.of(
			"target", "test-verifactu-certificates", UUID.randomUUID().toString());

	@Autowired
	private VerifactuTransport transport;
	@Autowired
	private ManagedCertificateKeyStoreFactory keyStores;
	@Autowired
	private VerifactuCertificateSecretStore secrets;

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
		if (Files.exists(KEY_DIRECTORY)) {
			deleteDirectory(KEY_DIRECTORY);
		}
		deleteDirectory(CERTIFICATE_DIRECTORY);
	}

	@Test
	void contextLoads() {
		assertThat(transport).isInstanceOf(ConfiguredVerifactuTransport.class);
		assertThat(keyStores).isNotNull();
		assertThat(secrets).isNotNull();
	}
	// Comprueba que el contexto usa transporte y custodia de certificados gestionados.

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
