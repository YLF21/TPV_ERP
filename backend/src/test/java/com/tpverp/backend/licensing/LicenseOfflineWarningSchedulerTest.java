package com.tpverp.backend.licensing;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LicenseOfflineWarningSchedulerTest {

    private final LicenseRepository licenses = org.mockito.Mockito.mock(LicenseRepository.class);
    private final AuditService audit = org.mockito.Mockito.mock(AuditService.class);
    private final LicenseOfflineWarningScheduler scheduler = new LicenseOfflineWarningScheduler(
            licenses,
            audit,
            Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void avisaSiLaLicenciaEstaCaducadaYNoValidaConSaasHaceUnaSemana() {
        when(licenses.findAll()).thenReturn(List.of(license(
                "2026-08-01T00:00:00Z",
                "2026-08-03T10:00:00Z")));

        scheduler.tick();

        verify(audit).record(
                eq("LICENSE_OFFLINE_EXPIRED_WARNING"),
                eq(AuditResult.FALLO),
                org.mockito.ArgumentMatchers.argThat(details ->
                        "FALTA CONEXION Y LICENCIA CADUCADA".equals(details.get("mensaje"))));
    }

    @Test
    void noAvisaSiLaLicenciaCaducadaValidoConSaasHaceMenosDeUnaSemana() {
        when(licenses.findAll()).thenReturn(List.of(license(
                "2026-08-01T00:00:00Z",
                "2026-08-05T10:00:01Z")));

        scheduler.tick();

        verify(audit, never()).record(
                eq("LICENSE_OFFLINE_EXPIRED_WARNING"),
                eq(AuditResult.FALLO),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noAvisaSiLaLicenciaSigueVigenteAunqueNoValideConSaas() {
        when(licenses.findAll()).thenReturn(List.of(license(
                "2026-12-31T00:00:00Z",
                "2026-08-01T00:00:00Z")));

        scheduler.tick();

        verify(audit, never()).record(
                eq("LICENSE_OFFLINE_EXPIRED_WARNING"),
                eq(AuditResult.FALLO),
                org.mockito.ArgumentMatchers.any());
    }

    private static License license(String validUntil, String lastSaasValidationAt) {
        var company = new Company("B12345678", "Company", Map.of(
                "linea1", "Calle Uno",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES"));
        var store = new Store(
                company,
                "Store",
                Map.of(
                        "linea1", "Calle Uno",
                        "ciudad", "Las Palmas",
                        "codigoPostal", "35001",
                        "provincia", "Las Palmas",
                        "pais", "ES"),
                "hash",
                "Atlantic/Canary",
                "EUR",
                "es-ES");
        var license = new License(
                store,
                new com.tpverp.backend.installation.Installation(
                        "INST-1", "public-key", Instant.parse("2026-06-08T00:00:00Z")),
                "LIC-1",
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse(validUntil),
                1,
                0,
                "B12345678",
                TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC,
                "{}",
                "hash",
                3,
                Instant.parse("2026-06-08T00:00:00Z"),
                Map.of(),
                ImportResult.ACEPTADA,
                null,
                true);
        license.markSaasValidated(Instant.parse(lastSaasValidationAt), license.getValidaHasta());
        return license;
    }
}
