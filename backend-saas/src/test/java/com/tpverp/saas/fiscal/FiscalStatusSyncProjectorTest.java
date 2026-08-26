package com.tpverp.saas.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasLicense;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import com.tpverp.saas.sync.SaasSyncEvent;
import com.tpverp.saas.sync.SyncOperation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FiscalStatusSyncProjectorTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final Instant MODE_SINCE = Instant.parse("2026-08-01T00:00:00Z");

    private SaasFiscalStatusRepository statuses;
    private EntityManager entityManager;
    private FiscalStatusSyncProjector projector;
    private SaasCompany company;
    private SaasStore store;
    private SaasInstallation installation;
    private UUID entityId;

    @BeforeEach
    void setUp() {
        statuses = mock(SaasFiscalStatusRepository.class);
        entityManager = mock(EntityManager.class);
        projector = new FiscalStatusSyncProjector(
                statuses,
                entityManager,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        company = new SaasCompany(
                UUID.randomUUID(), "Empresa", "B12345678", TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC, NOW.minusSeconds(3600));
        store = new SaasStore(
                UUID.randomUUID(), company, "001", "Tienda 1",
                "Atlantic/Canary", NOW.minusSeconds(3600));
        SaasLicense license = new SaasLicense(
                UUID.randomUUID(), company, "LIC-1", NOW.plusSeconds(86400), 1, 0,
                NOW.minusSeconds(3600));
        installation = new SaasInstallation(
                UUID.randomUUID(), company, store, license, UUID.randomUUID(), "INST-1",
                "public-key", "token-hash", NOW.minusSeconds(3600));
        when(entityManager.find(
                SaasInstallation.class,
                installation.getId(),
                LockModeType.PESSIMISTIC_WRITE)).thenReturn(installation);
        entityId = UUID.randomUUID();
    }

    @Test
    void aceptaInformePosteriorDeLaMismaVersionYModalidad() {
        SaasFiscalStatus current = current("VERIFACTU", 4, NOW.minusSeconds(120), "hash-anterior");
        when(statuses.findByInstallation_Id(installation.getId())).thenReturn(Optional.of(current));

        projector.project(
                event("hash-nuevo"),
                payload("VERIFACTU", 4, NOW.minusSeconds(10)),
                NOW);

        assertThat(current.getReportedAt()).isEqualTo(NOW.minusSeconds(10));
        assertThat(current.getPayloadHash()).isEqualTo("hash-nuevo");
        assertThat(current.getActivationState()).isEqualTo("ACTIVE");
        verify(entityManager).find(
                SaasInstallation.class,
                installation.getId(),
                LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void rechazaCambioDeModalidadSinIncrementarVersion() {
        SaasFiscalStatus current = current("NO_VERIFACTU", 4, NOW.minusSeconds(120), "hash-anterior");
        when(statuses.findByInstallation_Id(installation.getId())).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> projector.project(
                event("hash-nuevo"),
                payload("VERIFACTU", 4, NOW.minusSeconds(10)),
                NOW))
                .isInstanceOf(FiscalStatusSyncProjector.ProjectionException.class)
                .hasMessageContaining("sin incrementar su version");

        assertThat(current.getEffectiveMode()).isEqualTo("NO_VERIFACTU");
        assertThat(current.getReportedAt()).isEqualTo(NOW.minusSeconds(120));
    }

    @Test
    void ignoraInformeAnteriorAunqueCambieElPayload() {
        SaasFiscalStatus current = current("VERIFACTU", 4, NOW.minusSeconds(10), "hash-actual");
        when(statuses.findByInstallation_Id(installation.getId())).thenReturn(Optional.of(current));

        projector.project(
                event("hash-antiguo"),
                payload("VERIFACTU", 4, NOW.minusSeconds(120)),
                NOW);

        assertThat(current.getReportedAt()).isEqualTo(NOW.minusSeconds(10));
        assertThat(current.getPayloadHash()).isEqualTo("hash-actual");
    }

    @Test
    void rechazaContenidoDistintoConMismaVersionYFecha() {
        SaasFiscalStatus current = current("VERIFACTU", 4, NOW.minusSeconds(10), "hash-actual");
        when(statuses.findByInstallation_Id(installation.getId())).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> projector.project(
                event("hash-distinto"),
                payload("VERIFACTU", 4, NOW.minusSeconds(10)),
                NOW))
                .isInstanceOf(FiscalStatusSyncProjector.ProjectionException.class)
                .hasMessageContaining("Misma version y fecha fiscal");
    }

    @Test
    void aceptaFechasDelEsquemaFiscalInicial() {
        when(statuses.findByInstallation_Id(installation.getId())).thenReturn(Optional.empty());
        var legacy = payload("VERIFACTU", 0, NOW);
        legacy.put("modeSince", null);
        legacy.put("reportedAt", BigDecimal.valueOf(NOW.getEpochSecond())
                .add(BigDecimal.valueOf(NOW.getNano(), 9)));
        legacy.put("activationDate", List.of(2027, 1, 1));

        projector.project(event("hash-legacy"), legacy, NOW);

        verify(statuses).save(org.mockito.ArgumentMatchers.argThat(value ->
                value.getReportedAt().equals(NOW)
                        && value.getActivationDate().equals(LocalDate.of(2027, 1, 1))));
    }

    @Test
    void rechazaEntornosFiscalesNoDeclarados() {
        Map<String, Object> invalid = payload("VERIFACTU", 1, NOW);
        invalid.put("runtimeClass", "REAL");
        invalid.put("transportMode", "SIMULATED");

        assertThatThrownBy(() -> projector.project(event("hash"), invalid, NOW))
                .isInstanceOf(FiscalStatusSyncProjector.ProjectionException.class)
                .hasMessageContaining("Combinacion de entorno fiscal");
    }

    private SaasFiscalStatus current(String mode, long version, Instant reportedAt, String hash) {
        return new SaasFiscalStatus(
                UUID.randomUUID(), installation, company, store, installation.getInstallationId(),
                entityId, mode, "ACTIVE", version, MODE_SINCE, LocalDate.parse("2027-01-01"),
                0L, "SANDBOX", "TEST", "SIMULATED", reportedAt,
                reportedAt.plusSeconds(1), hash);
    }

    private SaasSyncEvent event(String hash) {
        return new SaasSyncEvent(
                UUID.randomUUID(), company, store, installation,
                FiscalStatusSyncProjector.ENTITY_TYPE, entityId, SyncOperation.ACTUALIZAR,
                "{}", hash, 1, NOW);
    }

    private Map<String, Object> payload(String mode, long version, Instant reportedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("installationId", installation.getInstallationId().toString());
        payload.put("companyId", company.getId().toString());
        payload.put("storeId", store.getId().toString());
        payload.put("effectiveMode", mode);
        payload.put("activationState", "ACTIVE");
        payload.put("modeVersion", version);
        payload.put("modeSince", MODE_SINCE.toString());
        payload.put("activationDate", "2027-01-01");
        payload.put("policyVersion", 0);
        payload.put("runtimeClass", "SANDBOX");
        payload.put("endpointEnvironment", "TEST");
        payload.put("transportMode", "SIMULATED");
        payload.put("reportedAt", reportedAt.toString());
        return payload;
    }
}
