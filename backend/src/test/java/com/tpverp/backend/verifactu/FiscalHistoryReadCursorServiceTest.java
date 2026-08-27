package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.junit.jupiter.api.Test;

class FiscalHistoryReadCursorServiceTest {
    @Test
    void returnsKeysetPageAndNeverRequestsARepositoryCount() {
        var organization = mock(CurrentOrganization.class);
        var installations = mock(InstallationRepository.class);
        var exports = mock(FiscalExportRepository.class);
        var submissions = mock(FiscalRequiredSubmissionRepository.class);
        var company = new Company("B12345674", "Empresa", Map.of(
                "linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                "provincia", "Madrid", "pais", "ES"));
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        var installation = new Installation("INST", "public-key", Instant.now());
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        when(store.getEmpresa()).thenReturn(company);
        when(installations.findAll()).thenReturn(List.of(installation));
        var older = new FiscalExportHistoryView(UUID.randomUUID(), company.getId(), installation.getId(),
                FiscalExportKind.BILLING, Instant.parse("2026-08-25T10:00:00Z"), null, null,
                1, null, "A".repeat(64));
        when(exports.findHistoryCursorPage(eq(company.getId()), eq(installation.getId()),
                any(), eq(3))).thenReturn(List.of(older));
        var service = new FiscalHistoryReadService(organization, installations, null, exports, submissions);

        var page = service.exportsCursor(2, null);

        assertThat(page.items()).containsExactly(older);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void rejectsCursorFromAnotherScope() {
        var organization = mock(CurrentOrganization.class);
        var installations = mock(InstallationRepository.class);
        var exports = mock(FiscalExportRepository.class);
        var submissions = mock(FiscalRequiredSubmissionRepository.class);
        var company = new Company("B12345674", "Empresa", Map.of(
                "linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001",
                "provincia", "Madrid", "pais", "ES"));
        var store = mock(Store.class);
        var installation = new Installation("INST", "public-key", Instant.now());
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(UUID.randomUUID());
        when(store.getEmpresa()).thenReturn(company);
        when(installations.findAll()).thenReturn(List.of(installation));
        var service = new FiscalHistoryReadService(organization, installations, null, exports, submissions);
        var cursor = FiscalHistoryReadCursorCodec.encode(new FiscalHistoryReadCursor(
                Instant.now(), UUID.randomUUID(), FiscalHistoryReadCursor.Direction.NEXT,
                FiscalHistoryReadCursorCodec.fingerprint("EXPORTS", company.getId(),
                        UUID.randomUUID(), installation.getId())));

        assertThatThrownBy(() -> service.exportsCursor(25, cursor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alcance");
    }
}
