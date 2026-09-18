package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Calls the real service outside a test transaction, as the HTTP controller does. */
@DataJpaTest(showSql = false)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, VerifactuAdminService.class,
        VerifactuActivationService.class, VerifactuSignaturePolicy.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class VerifactuAdminStatusPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "verifactu_admin_status_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");

    static { execute("create schema " + SCHEMA); }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StoreRepository stores;
    @Autowired private InstallationRepository installations;
    @Autowired private VerifactuConfigurationRepository configurations;
    @Autowired private VerifactuAdminService service;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private Clock clock;
    @MockitoBean private VerifactuSubmissionPropertiesFactory properties;
    @MockitoBean private VerifactuPkcs12KeyStoreLoader keyStores;
    @MockitoBean private VerifactuCertificateValidator certificates;
    @MockitoBean private FiscalSubmissionQueueService queue;
    @MockitoBean private VerifactuSubmissionWorker worker;
    @MockitoBean private VerifactuClockMonitor clockMonitor;
    @MockitoBean private FiscalSubmissionAttemptService attempts;
    private UUID companyId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL
                + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropSchema() { execute("drop schema if exists " + SCHEMA + " cascade"); }

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var address = "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}";
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values(?,'B00000001','Test company',cast(? as jsonb))",
                companyId, address);
        jdbc.update("""
                insert into tienda(id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale)
                values(?,?,'001','Test store',cast(? as jsonb),?,'Atlantic/Canary','EUR','es-ES')
                """, storeId, companyId, address, "hash-" + storeId);
        if (installations.count() == 0) {
            installations.saveAndFlush(new Installation("STATUS-TEST", "TEST PUBLIC KEY", NOW));
        }
        var store = stores.findWithCompanyById(storeId).orElseThrow();
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(store.getEmpresa());
        when(clock.instant()).thenReturn(NOW);
        when(properties.current()).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(jdbc.queryForObject("show transaction_read_only", String.class)).isEqualTo("on");
            return new VerifactuSubmissionProperties(VerifactuEndpointMode.TEST, "TPV ERP", "STATUS-TEST");
        });
        // The Spring ready event invokes this mock before the first test; inspect only status calls.
        clearInvocations(clockMonitor);
    }

    @Test
    void statusWithoutConfigurationUsesItsOwnReadOnlyTransactionAndDoesNotCreateFiscalState() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(configurations.findByCompanyId(companyId)).isEmpty();
        var before = jdbc.queryForList("select * from configuracion_verifactu order by id");

        var first = service.status();
        var repeated = service.status();

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(first).isEqualTo(repeated);
        assertThat(first.activationMode()).isEqualTo("UNAVAILABLE");
        assertThat(first.verifactuActive()).isFalse();
        assertThat(first.certificateConfigured()).isFalse();
        assertThat(first.warning()).isEqualTo("CERTIFICATE_NOT_CONFIGURED");
        assertThat(configurations.findByCompanyId(companyId)).isEmpty();
        assertThat(jdbc.queryForList("select * from configuracion_verifactu order by id")).isEqualTo(before);
        verifyNoInteractions(keyStores, certificates, queue, worker, clockMonitor, attempts);
    }

    @Test
    void statusReadsPersistedActivationWithoutChangingItsModeVersionOrTimestamps() {
        var configuration = new VerifactuConfiguration(companyId);
        configuration.activateVoluntarily(NOW.minusSeconds(60));
        configuration.markFirstSubmission(NOW.minusSeconds(30), null);
        configurations.saveAndFlush(configuration);
        var before = jdbc.queryForList("select * from configuracion_verifactu order by id");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        var status = service.status();

        assertThat(status.verifactuActive()).isTrue();
        assertThat(status.activationMode()).isEqualTo("VOLUNTARY");
        assertThat(status.effectiveActivationAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(status.firstSubmissionAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(jdbc.queryForList("select * from configuracion_verifactu order by id")).isEqualTo(before);
        verifyNoInteractions(keyStores, certificates, queue, worker, clockMonitor, attempts);
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
