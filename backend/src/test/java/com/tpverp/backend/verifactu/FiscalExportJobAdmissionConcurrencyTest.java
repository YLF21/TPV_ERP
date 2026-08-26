package com.tpverp.backend.verifactu;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class FiscalExportJobAdmissionConcurrencyTest {

    @Test
    void serializaLaDecisionDelLimiteAntesDelCount() {
        var company = new Company("B12345674", "Empresa DEV", Map.of(
                "linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                "provincia", "Madrid", "pais", "ES"));
        var store = new Store(company, "001", "Tienda", Map.of(
                "linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                "provincia", "Madrid", "pais", "ES"),
                "address-hash", "Europe/Madrid", "EUR", "es-ES");
        var installation = new Installation("INST-DEV-1", "public-key",
                java.time.Instant.parse("2026-08-24T08:00:00Z"));
        var organization = mock(CurrentOrganization.class);
        var installations = mock(InstallationRepository.class);
        var licenses = mock(LicenseRepository.class);
        var jobs = mock(FiscalExportJobRepository.class);
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(installations.findAll()).thenReturn(List.of(installation));
        when(jdbc.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jobs.save(any(FiscalExportJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var service = new FiscalExportJobService(organization, installations, licenses, jobs, jdbc,
                mock(FiscalExportJobEvidenceService.class));
        service.create(new FiscalExportJobRequest(FiscalExportKind.BILLING, null, null,
                List.of(UUID.randomUUID()), null, null, null, null, null, null, null,
                FiscalExportJobScope.CURRENT), "cashier");

        verify(jdbc).query(startsWith("select pg_advisory_xact_lock"),
                any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
    }
}
