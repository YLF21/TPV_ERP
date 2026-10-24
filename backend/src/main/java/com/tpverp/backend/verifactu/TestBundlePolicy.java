package com.tpverp.backend.verifactu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipFile;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/** Immutable laboratory restriction. No environment flag can turn it off. */
public final class TestBundlePolicy {
    public static final String RESOURCE = "META-INF/tpv-test-bundle.properties";
    private final boolean testOnly;

    private TestBundlePolicy(boolean testOnly) { this.testOnly = testOnly; }

    public static TestBundlePolicy load() {
        var manifest = FiscalReleaseManifest.load();
        try (var input = TestBundlePolicy.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                if (manifest.releaseId().startsWith("tpv-erp-test-only-"))
                    throw new IllegalStateException("TEST_ONLY marker missing");
                return new TestBundlePolicy(false);
            }
            var values = new Properties();
            values.load(input);
            return from(values, manifest);
        } catch (IOException failure) { throw new IllegalStateException("Cannot read TEST_ONLY policy", failure); }
    }

    static TestBundlePolicy from(Properties values, FiscalReleaseManifest manifest) {
        if (!values.stringPropertyNames().equals(Set.of("purpose", "runtime", "version", "production.allowed"))
                || !"TEST_ONLY".equals(values.getProperty("purpose"))
                || !"SANDBOX".equals(values.getProperty("runtime"))
                || !"0.1.0".equals(values.getProperty("version"))
                || !"false".equals(values.getProperty("production.allowed")))
            throw new IllegalStateException("Invalid TEST_ONLY policy");
        manifest.requireSelfConsistent();
        if (!manifest.releaseId().matches("tpv-erp-test-only-0\\.1\\.0-v278-[a-zA-Z0-9._-]+")
                || !"0.1.0".equals(manifest.systemVersion()) || manifest.capability() != FiscalProductCapability.DUAL
                || !"V278".equals(manifest.schemaVersion()) || manifest.releaseSequence() != 35
                || manifest.buildSequence() != 1 || manifest.commitHash() == null
                || manifest.manifestHash() == null || manifest.declarationHash() != null
                || manifest.commitHash().length() != 40
                || !manifest.releaseId().equals("tpv-erp-test-only-0.1.0-v278-" + manifest.commitHash().substring(0, 12)))
            throw new IllegalStateException("TEST_ONLY release identity mismatch");
        return new TestBundlePolicy(true);
    }

    public boolean testOnly() { return testOnly; }

    public void requireRuntime(Environment environment) {
        if (!testOnly) return;
        var profiles = java.util.Arrays.asList(environment.getActiveProfiles());
        if (!profiles.contains("lab-bundle") || profiles.contains("prod"))
            throw new IllegalStateException("TEST_ONLY requires lab-bundle without prod");
        for (var expected : Map.of(
                "runtime-class", "SANDBOX", "endpoint-environment", "TEST", "endpoint-mode", "TEST",
                "transport-mode", "SIMULATED", "dev-sandbox.enabled", "true", "production-enabled", "false",
                "worker-enabled", "false", "aeat-test-network-enabled", "false").entrySet()) {
            if (!expected.getValue().equals(environment.getProperty("tpv.verifactu." + expected.getKey())))
                throw new IllegalStateException("TEST_ONLY requires " + expected.getKey() + "=" + expected.getValue());
        }
        requireLocalUrl(environment.getRequiredProperty("tpv.license.saas-url"), false);
        var syncUrl = environment.getProperty("tpv.sync.central-url", "");
        if (!syncUrl.isBlank()) requireLocalUrl(syncUrl, false);
        for (var key : Set.of("dev-signing-pkcs12", "dev-signing-password")) {
            if (!environment.getProperty("tpv.verifactu." + key, "").isBlank())
                throw new IllegalStateException("TEST_ONLY forbids fiscal signing material: " + key);
        }
    }

    /** Prevent pool/Flyway/JPA binding from choosing a different database after preflight. */
    public void requireStorageConfiguration(Environment environment) {
        if (!testOnly) return;
        requireLocalUrl(environment.getRequiredProperty("spring.datasource.url"), true);
        if (!"never".equals(environment.getProperty("spring.sql.init.mode")))
            throw new IllegalStateException("TEST_ONLY requires spring.sql.init.mode=never");
        // Sources supplied by launchers may have a nonstandard name. Inspect names
        // as well as Binder so JDBCURL/JDBC_URL and relaxed aliases cannot bypass us.
        if (environment instanceof org.springframework.core.env.ConfigurableEnvironment configurable) {
            for (var source : configurable.getPropertySources()) {
                if (source instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable) {
                    for (var name : enumerable.getPropertyNames()) requireStoragePropertyName(name);
                }
            }
        }
        var binder = Binder.get(environment);
        var dataSource = binder.bind("spring.datasource", Bindable.mapOf(String.class, Object.class)).orElse(Map.of());
        for (var key : dataSource.keySet()) {
            if (!Set.of("url", "username", "password", "driver-class-name").contains(key))
                throw new IllegalStateException("TEST_ONLY forbids alternate datasource configuration: " + key);
        }
        var driver = environment.getProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
        if (!"org.postgresql.Driver".equals(driver)) throw new IllegalStateException("TEST_ONLY requires PostgreSQL driver");
        var flyway = binder.bind("spring.flyway", Bindable.mapOf(String.class, Object.class)).orElse(Map.of());
        if (flyway.keySet().stream().anyMatch(key -> !"enabled".equals(key)))
            throw new IllegalStateException("TEST_ONLY forbids independent Flyway configuration");
        if (!binder.bind("spring.jpa.properties", Bindable.mapOf(String.class, Object.class)).orElse(Map.of()).isEmpty())
            throw new IllegalStateException("TEST_ONLY forbids independent JPA connection properties");
    }

    private static void requireStoragePropertyName(String name) {
        var normalized = name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
        if ((normalized.startsWith("springdatasource") && !Set.of("springdatasourceurl",
                "springdatasourceusername", "springdatasourcepassword", "springdatasourcedriverclassname").contains(normalized))
                || (normalized.startsWith("springflyway") && !normalized.equals("springflywayenabled"))
                || normalized.startsWith("springjpaproperties"))
            throw new IllegalStateException("TEST_ONLY forbids alternate database configuration property");
    }

    public void requireDatabase(String url, String user, String password, boolean allowEmpty) throws SQLException {
        if (!testOnly) return;
        requireLocalUrl(url, true);
        try (var connection = DriverManager.getConnection(url, user, password)) {
            connection.setReadOnly(true);
            requireDatabase(connection, allowEmpty);
        }
    }

    public void requireOfflineDatabase(boolean allowEmpty) throws SQLException {
        if (!testOnly) return;
        requireLocalUrl(System.getenv("TPV_LICENSE_SAAS_URL"), false);
        requireDatabase(System.getenv("TPV_DB_URL"), System.getenv("TPV_DB_USERNAME"),
                System.getenv("TPV_DB_PASSWORD") != null ? System.getenv("TPV_DB_PASSWORD") : System.getenv("PGPASSWORD"), allowEmpty);
    }

    /** libpq must not override the destination already checked through JDBC. */
    public void restrictPostgresProcess(ProcessBuilder process) {
        if (!testOnly) return;
        process.environment().keySet().removeIf(key -> key.toUpperCase(java.util.Locale.ROOT).startsWith("PG")
                && !key.equalsIgnoreCase("PGPASSWORD"));
    }

    /** Read only: a REAL marker is never relabelled, even when fiscal tables are empty. */
    public void requireDatabase(Connection connection, boolean allowEmpty) throws SQLException {
        if (!testOnly) return;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema') and table_type='BASE TABLE'")) {
            if (!rows.next()) throw new SQLException("TEST_ONLY cannot inspect database");
            if (rows.getLong(1) == 0 && allowEmpty) return;
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "select runtime_class from fiscal_runtime_guard where id=1")) {
            if (!rows.next() || !"SANDBOX".equals(rows.getString(1)) || rows.next())
                throw new SQLException("TEST_ONLY rejects non-SANDBOX database");
        }
    }

    public void enrichSnapshot(Connection connection, Map<String, Object> manifest) throws SQLException {
        if (!testOnly) return;
        requireDatabase(connection, false);
        manifest.put("testBundlePurpose", "TEST_ONLY");
        manifest.put("fiscalRuntimeClass", "SANDBOX");
    }

    public void requireBackup(JsonNode manifest) throws IOException {
        if (testOnly && (manifest == null || !"TEST_ONLY".equals(manifest.path("testBundlePurpose").asText())
                || !"SANDBOX".equals(manifest.path("fiscalRuntimeClass").asText())))
            throw new IOException("TEST_ONLY rejects backup without authenticated SANDBOX metadata");
    }

    public void requireBackup(Path archive) throws IOException {
        if (!testOnly) return;
        try (var zip = new ZipFile(archive.toFile())) {
            var entry = zip.getEntry("manifest.json");
            if (entry == null) throw new IOException("TEST_ONLY backup manifest missing");
            try (var input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
                if (bytes.length > 16 * 1024 * 1024) throw new IOException("TEST_ONLY backup manifest too large");
                requireBackup(new ObjectMapper().readTree(bytes));
            }
        }
    }

    static void requireLocalUrl(String value, boolean jdbc) {
        try {
            if (value == null || (jdbc && !value.startsWith("jdbc:postgresql://"))) throw new IllegalArgumentException();
            var uri = URI.create(jdbc ? value.substring(5) : value);
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost());
            if ((!loopback && (jdbc || !privateIpv4(uri.getHost())))
                    || (!jdbc && !"https".equals(uri.getScheme())) || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (jdbc && uri.getPort() == -1)
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))
                    || (jdbc && (uri.getRawPath() == null || !uri.getRawPath().matches("/lab_[A-Za-z0-9_]+"))))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            // URI parser exceptions may echo credentials embedded in malformed input.
            throw new IllegalStateException("TEST_ONLY requires local PostgreSQL and private HTTPS SaaS");
        }
    }

    // Literal RFC1918 only; never resolve a hostname to decide the laboratory boundary.
    private static boolean privateIpv4(String host) {
        if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        var parts = host.split("\\.");
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].length() > 1 && parts[i].startsWith("0")) return false;
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) return false;
        }
        return octets[0] == 10 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }
}
