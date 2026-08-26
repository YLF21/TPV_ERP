package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FiscalHistoryReadServiceTest {

    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final InstallationRepository installations = mock(InstallationRepository.class);
    private final FiscalExportRepository exports = mock(FiscalExportRepository.class);
    private final FiscalRequiredSubmissionRepository submissions =
            mock(FiscalRequiredSubmissionRepository.class);
    private final Company company = new Company("B12345674", "Empresa DEV", Map.of(
            "linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
            "provincia", "Madrid", "pais", "ES"));
    private final Store store = mock(Store.class);
    private final Installation installation = new Installation(
            "INST-READ", "public-key", Instant.parse("2026-08-24T08:00:00Z"));
    private FiscalHistoryReadService service;

    @BeforeEach
    void setUp() {
        service = new FiscalHistoryReadService(
                organization, installations, null, exports, submissions);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(store.getEmpresa()).thenReturn(company);
        when(installations.findAll()).thenReturn(List.of(installation));
    }

    @Test
    void devuelveSoloMetadatosDeExportacionesEnOrdenYConAlcanceFiscal() {
        var older = new FiscalExport(company.getId(), installation.getId(), FiscalExportKind.BILLING,
                UUID.randomUUID(), 2, "A".repeat(64),
                Instant.parse("2026-08-25T08:00:00Z"));
        var newer = new FiscalExport(company.getId(), installation.getId(), FiscalExportKind.EVENTS,
                null, 1, "B".repeat(64), Instant.parse("2026-08-26T08:00:00Z"));
        when(exports.findLegacyHistoryPage(
                org.mockito.ArgumentMatchers.eq(company.getId()),
                org.mockito.ArgumentMatchers.eq(installation.getId()),
                org.mockito.ArgumentMatchers.eq(100)))
                .thenReturn(List.of(FiscalExportHistoryView.from(newer),
                        FiscalExportHistoryView.from(older)));

        var result = service.exports(null);

        assertThat(result).extracting(FiscalExportHistoryView::exportId)
                .containsExactly(newer.getId(), older.getId());
        assertThat(result.getFirst().contentHash()).isEqualTo("B".repeat(64));
        assertThat(result.getFirst()).hasNoNullFieldsOrPropertiesExcept(
                "periodStart", "periodEnd", "eventId");
        org.mockito.Mockito.verify(exports)
                .findLegacyHistoryPage(company.getId(), installation.getId(), 100);
    }

    @Test
    void devuelveRequerimientosSinMezclarOtraEmpresaNiInstalacion() {
        var submission = new FiscalRequiredSubmission(
                company.getId(), installation.getId(), "REQ-2026-001",
                Instant.parse("2026-08-26T09:00:00Z"));
        when(submissions.findLegacyHistoryPage(
                org.mockito.ArgumentMatchers.eq(company.getId()),
                org.mockito.ArgumentMatchers.eq(installation.getId()),
                org.mockito.ArgumentMatchers.eq(25)))
                .thenReturn(List.of(FiscalRequiredSubmissionHistoryView.from(submission)));

        var result = service.requiredSubmissions(25);

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo(submission.getId());
            assertThat(view.reference()).isEqualTo("REQ-2026-001");
            assertThat(view.status()).isEqualTo("PENDIENTE");
            assertThat(view.companyId()).isEqualTo(company.getId());
            assertThat(view.installationId()).isEqualTo(installation.getId());
        });
        org.mockito.Mockito.verify(submissions)
                .findLegacyHistoryPage(company.getId(), installation.getId(), 25);
    }

    @Test
    void limitaLaLecturaAUnMaximoDeCien() {
        assertThatThrownBy(() -> service.exports(101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit debe estar entre 1 y 100");
        assertThatThrownBy(() -> service.requiredSubmissions(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit debe estar entre 1 y 100");
    }
}
