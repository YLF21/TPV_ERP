package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FiscalRequiredSubmissionServiceTest {
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final InstallationRepository installations = mock(InstallationRepository.class);
    private final FiscalRequiredSubmissionRepository submissions =
            mock(FiscalRequiredSubmissionRepository.class);
    private final VerifactuConfigurationRepository configurations =
            mock(VerifactuConfigurationRepository.class);
    private final FiscalExportService exports = mock(FiscalExportService.class);
    private final Company company = new Company("B12345678", "Empresa DEV",
            Map.of("linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                    "provincia", "Madrid", "pais", "ES"));
    private final Installation installation = new Installation("INST-DEV-1", "public-key",
            Instant.parse("2026-08-24T08:00:00Z"));
    private FiscalRequiredSubmissionService service;

    @BeforeEach
    void setUp() {
        when(organization.currentCompany()).thenReturn(company);
        when(installations.findAll()).thenReturn(List.of(installation));
        service = new FiscalRequiredSubmissionService(organization, installations, submissions,
                configurations, exports);
    }

    @Test
    void noPermiteRegistrarRequerimientosFueraDeNoVerifactu() {
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register("REQ-001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO VERI*FACTU");
    }

    @Test
    void rechazaReferenciaQueNoCabeEnElContratoAeat() {
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(
                new VerifactuConfiguration(company.getId(), FiscalMode.NO_VERIFACTU)));

        assertThatThrownBy(() -> service.register("R".repeat(19)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("18 caracteres");
    }

    @Test
    void exportaPeriodoYEnlazaElRequerimientoDeFormaAtomica() {
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(
                new VerifactuConfiguration(company.getId(), FiscalMode.NO_VERIFACTU)));
        var requirement = new FiscalRequiredSubmission(company.getId(), installation.getId(),
                "REQ-001", Instant.parse("2026-08-24T09:00:00Z"));
        when(submissions.findByIdAndCompanyIdAndInstallationId(requirement.getId(),
                company.getId(), installation.getId())).thenReturn(Optional.of(requirement));
        when(submissions.save(any(FiscalRequiredSubmission.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var export = new FiscalExportView(UUID.randomUUID(), FiscalExportKind.BILLING,
                Instant.parse("2026-08-24T10:00:00Z"),
                OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 8, 24, 23, 59, 59, 0, ZoneOffset.UTC),
                1, UUID.randomUUID(), List.of("<RegistroAlta/>") );
        var start = OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        var end = OffsetDateTime.of(2026, 8, 24, 23, 59, 59, 0, ZoneOffset.UTC);
        when(exports.export(FiscalExportKind.BILLING, start, end, "REQ-001"))
                .thenReturn(export);

        var result = service.export(requirement.getId(), FiscalExportKind.BILLING, start, end);

        assertThat(result.requirement().status()).isEqualTo("EXPORTADO");
        assertThat(result.requirement().exportId()).isEqualTo(export.exportId());
        assertThat(result.export()).isSameAs(export);
    }

    @Test
    void exigePeriodoCompletoParaNoExportarHistoricoIndefinido() {
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(
                new VerifactuConfiguration(company.getId(), FiscalMode.NO_VERIFACTU)));

        assertThatThrownBy(() -> service.export(UUID.randomUUID(), FiscalExportKind.EVENTS,
                null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("periodo completo");
    }
}
