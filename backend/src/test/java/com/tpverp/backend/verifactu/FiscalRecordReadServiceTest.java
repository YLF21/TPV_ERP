package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FiscalRecordReadServiceTest {

    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID INSTALLATION_ID = UUID.randomUUID();

    @Mock private CurrentOrganization organization;
    @Mock private InstallationRepository installations;
    @Mock private LicenseRepository licenses;
    @Mock private FiscalRecordReadRepository reads;
    @Mock private Store store;
    @Mock private Company company;

    private FiscalRecordReadService service;

    @BeforeEach
    void setUp() {
        service = new FiscalRecordReadService(organization, installations, licenses, reads);
    }

    @Test
    void listUsesResolvedCompanyStoreInstallationAndSupportsBothFiscalModes() {
        scope();
        var license = mock(License.class);
        var installation = mock(Installation.class);
        when(license.getLocalCompanyId()).thenReturn(COMPANY_ID);
        when(license.getInstalacionId()).thenReturn(INSTALLATION_ID);
        when(licenses.findActiveByTiendaId(STORE_ID)).thenReturn(List.of(license));
        when(installations.findById(INSTALLATION_ID)).thenReturn(Optional.of(installation));
        when(installation.getId()).thenReturn(INSTALLATION_ID);
        var expected = new FiscalRecordReadPage(List.of(), 0, 25, 0, 0);
        when(reads.findPage(COMPANY_ID, STORE_ID, INSTALLATION_ID,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F1, "A-1",
                FiscalMode.NO_VERIFACTU, 0, 25)).thenReturn(expected);

        var result = service.records(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F1, " A-1 ",
                FiscalMode.NO_VERIFACTU, 0, 25);

        assertThat(result).isSameAs(expected);
        verify(reads).findPage(COMPANY_ID, STORE_ID, INSTALLATION_ID,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F1, "A-1",
                FiscalMode.NO_VERIFACTU, 0, 25);
    }

    @Test
    void explicitExactNumberMatchIsForwardedToLegacyPageQuery() {
        resolvedScope();
        var expected = new FiscalRecordReadPage(List.of(), 0, 25, 0, 0);
        when(reads.findPage(COMPANY_ID, STORE_ID, INSTALLATION_ID,
                null, null, null, null, "A-1", FiscalRecordNumberMatch.EXACT, null,
                0, 25)).thenReturn(expected);

        var result = service.records(null, null, null, null, " A-1 ",
                FiscalRecordNumberMatch.EXACT, null, 0, 25);

        assertThat(result).isSameAs(expected);
        verify(reads).findPage(COMPANY_ID, STORE_ID, INSTALLATION_ID,
                null, null, null, null, "A-1", FiscalRecordNumberMatch.EXACT, null,
                0, 25);
    }

    @Test
    void cursorCannotBeReusedAfterChangingNumberMatch() {
        resolvedScope();
        var prefixFingerprint = FiscalRecordReadCursorCodec.fingerprint(
                COMPANY_ID, STORE_ID, INSTALLATION_ID, null, null, null, null, "A-1",
                FiscalRecordNumberMatch.PREFIX, null);
        var cursor = FiscalRecordReadCursorCodec.encode(new FiscalRecordReadCursor(
                10L, 5L, FiscalRecordReadCursor.Direction.NEXT, prefixFingerprint));

        assertThatThrownBy(() -> service.recordsCursor(null, null, null, null, "A-1",
                FiscalRecordNumberMatch.EXACT, null, 25, cursor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filtros");
        verifyNoInteractions(reads);
    }

    @Test
    void detailRequiresTheResolvedScopeAndReturnsSanitizedMetadataOnly() {
        scope();
        var license = mock(License.class);
        var installation = mock(Installation.class);
        when(license.getLocalCompanyId()).thenReturn(COMPANY_ID);
        when(license.getInstalacionId()).thenReturn(INSTALLATION_ID);
        when(licenses.findActiveByTiendaId(STORE_ID)).thenReturn(List.of(license));
        when(installations.findById(INSTALLATION_ID)).thenReturn(Optional.of(installation));
        when(installation.getId()).thenReturn(INSTALLATION_ID);
        var recordId = UUID.randomUUID();
        var row = mock(FiscalRecordReadRepository.Row.class);
        var detail = new FiscalRecordReadRepository.Detail(
                UUID.randomUUID(), COMPANY_ID, "Atlantic/Canary", "B12345678",
                "SNAPSHOT-HASH", "1", "1", "1",
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                false, null, null, null, null, null, null, "AEAT-CODE");
        when(reads.findDetail(COMPANY_ID, STORE_ID, INSTALLATION_ID, recordId))
                .thenReturn(Optional.of(row));
        when(row.detail()).thenReturn(detail);
        when(row.recordId()).thenReturn(recordId);
        when(row.installationId()).thenReturn(INSTALLATION_ID);
        when(row.storeId()).thenReturn(STORE_ID);
        when(row.sequence()).thenReturn(1L);
        when(row.operation()).thenReturn(FiscalRecordOperation.ALTA);
        when(row.documentType()).thenReturn(FiscalDocumentType.F1);
        when(row.number()).thenReturn("A-1");
        when(row.issueDate()).thenReturn(LocalDate.of(2026, 8, 1));
        when(row.generatedAt()).thenReturn(Instant.parse("2026-08-01T10:00:00Z"));
        when(row.fiscalMode()).thenReturn(FiscalMode.NO_VERIFACTU);
        when(row.hash()).thenReturn("RECORD-HASH");
        when(reads.findRelations(COMPANY_ID, STORE_ID, INSTALLATION_ID, recordId))
                .thenReturn(List.of());

        var result = service.record(recordId);

        assertThat(result.recordId()).isEqualTo(recordId);
        assertThat(result.artifact()).isNull();
        assertThat(result.submission()).isNull();
        assertThat(result.adjacentChainStatus()).isEqualTo("ADJACENT_VALID");
        when(row.sequence()).thenReturn(2L);
        assertThat(service.record(recordId).adjacentChainStatus()).isEqualTo("ADJACENT_ANOMALOUS");
        assertThat(result.relations()).isEmpty();
        assertThat(result).hasNoNullFieldsOrPropertiesExcept(
                "documentId", "totalTax", "totalAmount", "previousHash", "document",
                "artifact", "submission", "previousRecordId", "nextRecordId");
    }

    @Test
    void invalidRangeAndPageAreRejectedBeforeResolvingScope() {
        assertThatThrownBy(() -> service.records(
                LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 1),
                null, null, null, null, 0, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dateFrom");
        assertThatThrownBy(() -> service.records(
                null, null, null, null, null, null, 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
        verify(organization, never()).currentStore();
    }

    @Test
    void adjacentChainAcceptsEqualTimestampsWhenHashesAndOrderAreValid() {
        scope();
        var license = mock(License.class);
        var installation = mock(Installation.class);
        when(license.getLocalCompanyId()).thenReturn(COMPANY_ID);
        when(license.getInstalacionId()).thenReturn(INSTALLATION_ID);
        when(licenses.findActiveByTiendaId(STORE_ID)).thenReturn(List.of(license));
        when(installations.findById(INSTALLATION_ID)).thenReturn(Optional.of(installation));
        when(installation.getId()).thenReturn(INSTALLATION_ID);
        var recordId = UUID.randomUUID();
        var previousId = UUID.randomUUID();
        var nextId = UUID.randomUUID();
        var generatedAt = Instant.parse("2026-08-01T10:00:00Z");
        var detail = detail(previousId, "PREVIOUS-HASH", generatedAt,
                nextId, "RECORD-HASH", generatedAt);
        var row = mock(FiscalRecordReadRepository.Row.class);
        when(reads.findDetail(COMPANY_ID, STORE_ID, INSTALLATION_ID, recordId))
                .thenReturn(Optional.of(row));
        when(row.detail()).thenReturn(detail);
        when(row.recordId()).thenReturn(recordId);
        when(row.installationId()).thenReturn(INSTALLATION_ID);
        when(row.storeId()).thenReturn(STORE_ID);
        when(row.sequence()).thenReturn(2L);
        when(row.operation()).thenReturn(FiscalRecordOperation.ALTA);
        when(row.documentType()).thenReturn(FiscalDocumentType.F1);
        when(row.number()).thenReturn("A-2");
        when(row.issueDate()).thenReturn(LocalDate.of(2026, 8, 1));
        when(row.generatedAt()).thenReturn(generatedAt);
        when(row.fiscalMode()).thenReturn(FiscalMode.NO_VERIFACTU);
        when(row.previousHash()).thenReturn("PREVIOUS-HASH");
        when(row.hash()).thenReturn("RECORD-HASH");
        when(reads.findRelations(COMPANY_ID, STORE_ID, INSTALLATION_ID, recordId))
                .thenReturn(List.of());

        assertThat(service.record(recordId).adjacentChainStatus()).isEqualTo("ADJACENT_VALID");
    }

    @Test
    void sharedChainKeepsCrossStoreNeighborsForValidationButNotNavigation() {
        scope();
        var license = mock(License.class);
        var installation = mock(Installation.class);
        when(license.getLocalCompanyId()).thenReturn(COMPANY_ID);
        when(license.getInstalacionId()).thenReturn(INSTALLATION_ID);
        when(licenses.findActiveByTiendaId(STORE_ID)).thenReturn(List.of(license));
        when(installations.findById(INSTALLATION_ID)).thenReturn(Optional.of(installation));
        when(installation.getId()).thenReturn(INSTALLATION_ID);
        var recordId = UUID.randomUUID();
        var otherStoreId = UUID.randomUUID();
        var previousId = UUID.randomUUID();
        var nextId = UUID.randomUUID();
        var generatedAt = Instant.parse("2026-08-01T10:00:00Z");
        var detail = mock(FiscalRecordReadRepository.Detail.class);
        when(detail.previousRecordId()).thenReturn(previousId);
        when(detail.previousRecordStoreId()).thenReturn(otherStoreId);
        when(detail.previousRecordHash()).thenReturn("PREVIOUS-HASH");
        when(detail.previousRecordGeneratedAt()).thenReturn(generatedAt);
        when(detail.nextRecordId()).thenReturn(nextId);
        when(detail.nextRecordStoreId()).thenReturn(otherStoreId);
        when(detail.nextRecordPreviousHash()).thenReturn("RECORD-HASH");
        when(detail.nextRecordGeneratedAt()).thenReturn(generatedAt);
        var sharedRow = row(recordId, detail, generatedAt, 2L);
        when(reads.findDetail(COMPANY_ID, STORE_ID, INSTALLATION_ID, recordId))
                .thenReturn(Optional.of(sharedRow));
        when(reads.findRelations(COMPANY_ID, STORE_ID, INSTALLATION_ID, recordId))
                .thenReturn(List.of());

        var result = service.record(recordId);

        assertThat(result.previousRecordId()).isNull();
        assertThat(result.nextRecordId()).isNull();
        assertThat(result.adjacentChainStatus()).isEqualTo("ADJACENT_VALID");
    }

    private static FiscalRecordReadRepository.Row row(
            UUID recordId, FiscalRecordReadRepository.Detail detail, Instant generatedAt, long sequence) {
        var row = mock(FiscalRecordReadRepository.Row.class);
        when(row.detail()).thenReturn(detail);
        when(row.recordId()).thenReturn(recordId);
        when(row.installationId()).thenReturn(INSTALLATION_ID);
        when(row.storeId()).thenReturn(STORE_ID);
        when(row.sequence()).thenReturn(sequence);
        when(row.operation()).thenReturn(FiscalRecordOperation.ALTA);
        when(row.documentType()).thenReturn(FiscalDocumentType.F1);
        when(row.number()).thenReturn("A-2");
        when(row.issueDate()).thenReturn(LocalDate.of(2026, 8, 1));
        when(row.generatedAt()).thenReturn(generatedAt);
        when(row.fiscalMode()).thenReturn(FiscalMode.NO_VERIFACTU);
        when(row.previousHash()).thenReturn("PREVIOUS-HASH");
        when(row.hash()).thenReturn("RECORD-HASH");
        return row;
    }

    @Test
    void adjacentChainRejectsGeneratedAtAfterInjectedCurrentTime() {
        scope();
        var license = mock(License.class);
        var installation = mock(Installation.class);
        when(license.getLocalCompanyId()).thenReturn(COMPANY_ID);
        when(license.getInstalacionId()).thenReturn(INSTALLATION_ID);
        when(licenses.findActiveByTiendaId(STORE_ID)).thenReturn(List.of(license));
        when(installations.findById(INSTALLATION_ID)).thenReturn(Optional.of(installation));
        when(installation.getId()).thenReturn(INSTALLATION_ID);
        var recordId = UUID.randomUUID();
        var generatedAt = Instant.parse("2026-08-27T00:00:00Z");
        var detail = detail(null, null, null, null, null, null);
        var row = mock(FiscalRecordReadRepository.Row.class);
        when(reads.findDetail(COMPANY_ID, STORE_ID, INSTALLATION_ID, recordId))
                .thenReturn(Optional.of(row));
        when(row.detail()).thenReturn(detail);
        when(row.recordId()).thenReturn(recordId);
        when(row.installationId()).thenReturn(INSTALLATION_ID);
        when(row.storeId()).thenReturn(STORE_ID);
        when(row.sequence()).thenReturn(1L);
        when(row.operation()).thenReturn(FiscalRecordOperation.ALTA);
        when(row.documentType()).thenReturn(FiscalDocumentType.F1);
        when(row.number()).thenReturn("A-3");
        when(row.issueDate()).thenReturn(LocalDate.of(2026, 8, 27));
        when(row.generatedAt()).thenReturn(generatedAt);
        when(row.fiscalMode()).thenReturn(FiscalMode.NO_VERIFACTU);
        when(row.hash()).thenReturn("RECORD-HASH");
        when(reads.findRelations(COMPANY_ID, STORE_ID, INSTALLATION_ID, recordId))
                .thenReturn(List.of());

        var fixedService = new FiscalRecordReadService(organization, installations, licenses, reads,
                Clock.fixed(Instant.parse("2026-08-26T23:59:59Z"), ZoneOffset.UTC));

        assertThat(fixedService.record(recordId).adjacentChainStatus()).isEqualTo("ADJACENT_ANOMALOUS");
    }

    private void scope() {
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(STORE_ID);
        when(store.getEmpresa()).thenReturn(company);
        when(company.getId()).thenReturn(COMPANY_ID);
    }

    private void resolvedScope() {
        scope();
        var license = mock(License.class);
        var installation = mock(Installation.class);
        when(license.getLocalCompanyId()).thenReturn(COMPANY_ID);
        when(license.getInstalacionId()).thenReturn(INSTALLATION_ID);
        when(licenses.findActiveByTiendaId(STORE_ID)).thenReturn(List.of(license));
        when(installations.findById(INSTALLATION_ID)).thenReturn(Optional.of(installation));
        when(installation.getId()).thenReturn(INSTALLATION_ID);
    }

    private static FiscalRecordReadRepository.Detail detail(
            UUID previousRecordId, String previousHash, Instant previousGeneratedAt,
            UUID nextRecordId, String nextPreviousHash, Instant nextGeneratedAt) {
        return new FiscalRecordReadRepository.Detail(
                UUID.randomUUID(), COMPANY_ID, "Atlantic/Canary", "B12345678",
                "SNAPSHOT-HASH", "1", "1", "1", previousRecordId, previousHash,
                previousGeneratedAt, nextRecordId, nextPreviousHash, nextGeneratedAt,
                null, null, null, null, null, null, null, null, null,
                null, null, null, false, null, null, null, null, null, null,
                "AEAT-CODE");
    }
}
