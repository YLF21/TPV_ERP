package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TestBundlePolicyTest {
    private static final String COUNT = "select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema') and table_type='BASE TABLE'";
    private static final String MARKER = "select runtime_class from fiscal_runtime_guard where id=1";

    static TestBundlePolicy policy() {
        var properties = new Properties();
        properties.setProperty("purpose", "TEST_ONLY"); properties.setProperty("runtime", "SANDBOX");
        properties.setProperty("version", "0.1.0"); properties.setProperty("production.allowed", "false");
        var identity = new FiscalReleaseManifest("tpv-erp-test-only-0.1.0-v278-aaaaaaaaaaaa", "0.1.0",
                FiscalProductCapability.DUAL, "V278", 35, 1, "a".repeat(40), null, null);
        return TestBundlePolicy.from(properties, new FiscalReleaseManifest(identity.releaseId(), "0.1.0",
                FiscalProductCapability.DUAL, "V278", 35, 1, identity.commitHash(), null, identity.computedManifestHash()));
    }

    @Test void rejectsRealEvenWithNoFiscalEvidenceWithoutWriting() throws Exception {
        var connection = database(1, "REAL");
        var manifest = new LinkedHashMap<String, Object>();
        assertThatThrownBy(() -> policy().enrichSnapshot(connection, manifest)).hasMessageContaining("non-SANDBOX");
        assertThat(manifest).isEmpty();
        verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).commit();
        verify(connection, never()).setAutoCommit(anyBoolean());
        verify(connection.createStatement(), never()).executeUpdate(anyString());
    }

    @Test void permitsEmptyBootstrapButNeverAnEmptyRestoredDatabase() throws Exception {
        policy().requireDatabase(database(0, null), true);
        assertThatThrownBy(() -> policy().requireDatabase(database(0, null), false)).hasMessageContaining("non-SANDBOX");
    }

    @Test void capturesSandboxMetadataOnlyAfterCheckingSameSnapshotConnection() throws Exception {
        var values = new LinkedHashMap<String, Object>();
        policy().enrichSnapshot(database(10, "SANDBOX"), values);
        assertThat(values).containsEntry("testBundlePurpose", "TEST_ONLY").containsEntry("fiscalRuntimeClass", "SANDBOX");
        policy().requireBackup(new ObjectMapper().valueToTree(values));
    }

    @Test void rejectsRealAndUnmarkedBackupsBeforeRestore() throws Exception {
        var mapper = new ObjectMapper();
        assertThatThrownBy(() -> policy().requireBackup(mapper.readTree("{}"))).hasMessageContaining("SANDBOX metadata");
        assertThatThrownBy(() -> policy().requireBackup(mapper.readTree("{\"testBundlePurpose\":\"TEST_ONLY\",\"fiscalRuntimeClass\":\"REAL\"}")))
                .hasMessageContaining("SANDBOX metadata");
    }

    @Test void rejectsRealAndProductionOverridesRegardlessOfProfile() {
        var environment = sandbox();
        policy().requireRuntime(environment);
        environment.setProperty("tpv.verifactu.runtime-class", "REAL");
        assertThatThrownBy(() -> policy().requireRuntime(environment)).hasMessageContaining("runtime-class");
        var production = sandbox().withProperty("tpv.verifactu.production-enabled", "true");
        assertThatThrownBy(() -> policy().requireRuntime(production)).hasMessageContaining("production-enabled");
    }

    @Test void rejectsRemoteEndpointsAndHttpSaas() {
        assertThatThrownBy(() -> TestBundlePolicy.requireLocalUrl("jdbc:postgresql://production/db", true)).hasMessageContaining("local PostgreSQL");
        assertThatThrownBy(() -> TestBundlePolicy.requireLocalUrl("http://localhost:8081", false)).hasMessageContaining("HTTPS SaaS");
        TestBundlePolicy.requireLocalUrl("jdbc:postgresql://127.0.0.1:5432/lab_recovery", true);
        TestBundlePolicy.requireLocalUrl("https://localhost:9443", false);
        TestBundlePolicy.requireLocalUrl("https://10.77.23.20:58443", false);
        assertThatThrownBy(() -> TestBundlePolicy.requireLocalUrl("https://8.8.8.8", false)).hasMessageContaining("private HTTPS");
        assertThatThrownBy(() -> TestBundlePolicy.requireLocalUrl("https://172.32.0.1", false)).hasMessageContaining("private HTTPS");
    }

    @Test void rejectsProductionProfileEvenWithSandboxOverrides() {
        var environment = sandbox();
        environment.setActiveProfiles("prod", "lab-bundle");
        assertThatThrownBy(() -> policy().requireRuntime(environment)).hasMessageContaining("without prod");
    }

    @Test void rejectsNonLabAndAmbiguousJdbcTargetsBeforeOpeningAConnection() {
        for (var url : java.util.List.of("jdbc:postgresql://localhost/tpv", "jdbc:postgresql://localhost/",
                "jdbc:postgresql://localhost:5432/lab_ok?user=postgres", "jdbc:postgresql://user:pass@localhost:5432/lab_ok",
                "jdbc:postgresql://localhost:5432,production:5432/lab_ok", "jdbc:postgresql://localhost:5432/lab_ok/extra",
                "jdbc:postgresql://localhost:5432/%6cab_ok", "jdbc:postgresql://localhost:5432/lab_ok#other", "jdbc:postgresql://localhost/lab_without_port")) {
            assertThatThrownBy(() -> policy().requireDatabase(url, "unused", "unused", true))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("local PostgreSQL");
        }
    }

    @Test void rejectsPublicSyncEvenWithPrivateLicenseAndDisabledWorker() {
        var environment = sandbox().withProperty("tpv.sync.central-url", "https://public.example")
                .withProperty("tpv.sync.worker-enabled", "false").withProperty("tpv.sync.remote-repair-enabled", "true");
        assertThatThrownBy(() -> policy().requireRuntime(environment)).hasMessageContaining("private HTTPS");
        environment.setProperty("tpv.sync.central-url", "https://10.77.23.20:58443");
        policy().requireRuntime(environment);
    }

    @Test void rejectsSigningCertificateAndPasswordOverrides() {
        for (var key : java.util.List.of("dev-signing-pkcs12", "dev-signing-password")) {
            var environment = sandbox().withProperty("tpv.verifactu." + key, "not-permitted");
            assertThatThrownBy(() -> policy().requireRuntime(environment)).hasMessageContaining("fiscal signing material");
        }
    }

    @Test void rejectsFlywayPoolAndJpaDestinationsBeforeDatabaseAccess() {
        for (var key : java.util.List.of("spring.flyway.url", "spring.flyway.user", "spring.flyway.password",
                "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.data-source-class-name",
                "spring.datasource.hikari.data-source-properties.serverName", "spring.datasource.jndi-name",
                "spring.jpa.properties.hibernate.connection.url")) {
            var environment = sandbox().withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/lab_recovery")
                    .withProperty(key, "not-permitted");
            assertThatThrownBy(() -> policy().requireStorageConfiguration(environment)).isInstanceOf(IllegalStateException.class);
        }
        policy().requireStorageConfiguration(sandbox().withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/lab_recovery")
                .withProperty("spring.datasource.username", "lab_user").withProperty("spring.datasource.password", "unused")
                .withProperty("spring.flyway.enabled", "true"));
    }

    @Test void rejectsEnvironmentAliasesForAlternateConnections() {
        for (var key : java.util.List.of("SPRING_FLYWAY_URL", "SPRING_DATASOURCE_HIKARI_JDBCURL",
                "SPRING_DATASOURCE_HIKARI_JDBC_URL", "SPRING_DATASOURCE_JNDINAME")) {
            var environment = sandbox().withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/lab_recovery");
            environment.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                    "test-environment", java.util.Map.of(key, "not-permitted")));
            assertThat(catchThrowable(() -> policy().requireStorageConfiguration(environment)))
                    .as(key).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void rejectsSqlInitializationAndDoesNotEchoMalformedUrlSecrets() {
        var environment = sandbox().withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/lab_recovery")
                .withProperty("spring.sql.init.mode", "always");
        assertThatThrownBy(() -> policy().requireStorageConfiguration(environment)).hasMessageContaining("mode=never");
        assertThatThrownBy(() -> TestBundlePolicy.requireLocalUrl("https://user:do not disclose@localhost", false))
                .hasNoCause().hasMessageNotContaining("disclose");
    }

    @Test void removesLibpqDestinationOverridesWithoutStartingAProcess() {
        var process = new ProcessBuilder("not-executed");
        process.environment().put("PGHOSTADDR", "8.8.8.8");
        process.environment().put("PGPORT", "5433");
        process.environment().put("PGSERVICE", "production");
        process.environment().put("PGOPTIONS", "-c search_path=other");
        process.environment().put("PGPASSWORD", "test-secret");
        policy().restrictPostgresProcess(process);
        assertThat(process.environment()).doesNotContainKeys("PGHOSTADDR", "PGPORT", "PGSERVICE", "PGOPTIONS")
                .containsEntry("PGPASSWORD", "test-secret");
    }

    private static MockEnvironment sandbox() {
        var environment = new MockEnvironment().withProperty("tpv.license.saas-url", "https://localhost:9443")
                .withProperty("spring.sql.init.mode", "never")
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX").withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.endpoint-mode", "TEST").withProperty("tpv.verifactu.transport-mode", "SIMULATED")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true").withProperty("tpv.verifactu.production-enabled", "false")
                .withProperty("tpv.verifactu.worker-enabled", "false").withProperty("tpv.verifactu.aeat-test-network-enabled", "false");
        environment.setActiveProfiles("lab-bundle");
        return environment;
    }

    private static Connection database(long count, String runtime) throws Exception {
        var connection = mock(Connection.class); var statement = mock(Statement.class);
        var counts = mock(ResultSet.class); var marker = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(COUNT)).thenReturn(counts); when(counts.next()).thenReturn(true);
        when(counts.getLong(1)).thenReturn(count);
        when(statement.executeQuery(MARKER)).thenReturn(marker);
        when(marker.next()).thenReturn(runtime != null, false); when(marker.getString(1)).thenReturn(runtime);
        return connection;
    }
}
