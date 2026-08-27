package com.tpverp.saas.fiscal;

import static com.tpverp.saas.SaasTestData.fiscalAddress;
import static com.tpverp.saas.SaasTestData.validCif;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.CreateCompanyRequest;
import com.tpverp.saas.admin.CreateCompanyResponse;
import com.tpverp.saas.license.CommercialProfile;
import com.tpverp.saas.license.LicenseSaasLinkRequest;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import com.tpverp.saas.sync.SaasSyncEvent;
import com.tpverp.saas.sync.SaasSyncEventRepository;
import com.tpverp.saas.sync.SyncOperation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FiscalStatusProjectionConcurrencyTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource dataSource;
    @Autowired SaasInstallationRepository installations;
    @Autowired SaasSyncEventRepository events;
    @Autowired SaasFiscalStatusRepository statuses;
    @Autowired FiscalStatusSyncProjector projector;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void serializaProyeccionesDeUnaInstalacionYConservaLaVersionMasNueva() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).contains("PostgreSQL");
        }

        CreateCompanyResponse company = createCompany();
        UUID sourceInstallationId = UUID.randomUUID();
        link(company, sourceInstallationId);
        SaasInstallation installation = installations.findByInstallationId(sourceInstallationId).orElseThrow();
        UUID entityId = UUID.randomUUID();
        Instant baseReportedAt = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.SECONDS);
        UUID initialEventId = UUID.randomUUID();
        UUID olderEventId = UUID.randomUUID();
        UUID newerEventId = UUID.randomUUID();

        saveEvent(initialEventId, installation.getId(), entityId, "1".repeat(64));
        project(initialEventId, payload(company, sourceInstallationId, "PRE_SIF", 1, baseReportedAt));
        saveEvent(olderEventId, installation.getId(), entityId, "2".repeat(64));
        saveEvent(newerEventId, installation.getId(), entityId, "3".repeat(64));

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch installationLocked = new CountDownLatch(1);
        CountDownLatch releaseInstallation = new CountDownLatch(1);
        Future<?> blocker = executor.submit(() -> transactions.executeWithoutResult(status -> {
            SaasInstallation locked = entityManager.find(
                    SaasInstallation.class,
                    installation.getId(),
                    LockModeType.PESSIMISTIC_WRITE);
            if (locked == null) {
                throw new IllegalStateException("La instalacion de prueba ya no existe");
            }
            installationLocked.countDown();
            await(releaseInstallation);
        }));
        assertThat(installationLocked.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch projectionsStarted = new CountDownLatch(2);
        Future<?> older = executor.submit(() -> {
            projectionsStarted.countDown();
            project(olderEventId, payload(
                    company, sourceInstallationId, "NO_VERIFACTU", 2, baseReportedAt.plusSeconds(10)));
        });
        Future<?> newer = executor.submit(() -> {
            projectionsStarted.countDown();
            project(newerEventId, payload(
                    company, sourceInstallationId, "VERIFACTU", 3, baseReportedAt.plusSeconds(20)));
        });
        assertThat(projectionsStarted.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> older.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> newer.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
        } finally {
            releaseInstallation.countDown();
        }

        blocker.get(5, TimeUnit.SECONDS);
        older.get(5, TimeUnit.SECONDS);
        newer.get(5, TimeUnit.SECONDS);

        SaasFiscalStatus current = statuses.findByInstallation_Id(installation.getId()).orElseThrow();
        assertThat(current.getModeVersion()).isEqualTo(3);
        assertThat(current.getEffectiveMode()).isEqualTo("VERIFACTU");
        assertThat(current.getReportedAt()).isEqualTo(baseReportedAt.plusSeconds(20));
    }

    private void saveEvent(UUID eventId, UUID installationDatabaseId, UUID entityId, String hash) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            SaasInstallation installation = installations.findById(installationDatabaseId).orElseThrow();
            events.saveAndFlush(new SaasSyncEvent(
                    eventId,
                    installation.getCompany(),
                    installation.getStore(),
                    installation,
                    FiscalStatusSyncProjector.ENTITY_TYPE,
                    entityId,
                    SyncOperation.ACTUALIZAR,
                    "{}",
                    hash,
                    1,
                    Instant.now()));
        });
    }

    private void project(UUID eventId, Map<String, Object> payload) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            SaasSyncEvent event = events.findById(eventId).orElseThrow();
            projector.project(event, payload, Instant.now());
        });
    }

    private Map<String, Object> payload(
            CreateCompanyResponse company,
            UUID installationId,
            String mode,
            long version,
            Instant reportedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("installationId", installationId.toString());
        payload.put("companyId", company.companyId().toString());
        payload.put("storeId", company.storeId().toString());
        payload.put("effectiveMode", mode);
        payload.put("activationState", "ACTIVE");
        payload.put("modeVersion", version);
        payload.put("modeSince", reportedAt.toString());
        payload.put("runtimeClass", "SANDBOX");
        payload.put("endpointEnvironment", "TEST");
        payload.put("transportMode", "SIMULATED");
        payload.put("reportedAt", reportedAt.toString());
        return payload;
    }

    private CreateCompanyResponse createCompany() throws Exception {
        var result = mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateCompanyRequest(
                                "Empresa concurrencia fiscal",
                                validCif("B92929292"),
                                TaxpayerType.SOCIEDAD,
                                TaxRegime.IGIC,
                                CommercialProfile.MAYORISTA,
                                fiscalAddress(),
                                "001",
                                "Tienda concurrencia",
                                fiscalAddress(),
                                "Atlantic/Canary",
                                Instant.parse("2099-07-01T00:00:00Z"),
                                2,
                                1))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), CreateCompanyResponse.class);
    }

    private void link(CreateCompanyResponse company, UUID installationId) throws Exception {
        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token",
                                "recovery-token-0123456789abcdef0123456789abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasLinkRequest(
                                company.pairingCode(),
                                installationId,
                                "INST-CONC",
                                "public-key",
                                company.storeId(),
                                "001",
                                "DEMO-00000000",
                                "Empresa concurrencia",
                                null,
                                null,
                                "Atlantic/Canary"))))
                .andExpect(status().isOk());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("No se libero el bloqueo de la prueba");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Prueba interrumpida", exception);
        }
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
