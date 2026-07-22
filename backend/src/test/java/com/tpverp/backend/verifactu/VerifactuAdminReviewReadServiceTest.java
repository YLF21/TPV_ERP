package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class VerifactuAdminReviewReadServiceTest {

    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    @Mock private CurrentOrganization organization;
    @Mock private VerifactuAdminReviewReadRepository reads;
    @Mock private VerifactuSubmissionPropertiesFactory properties;
    @Mock private VerifactuClockMonitor clockMonitor;
    @Mock private Environment environment;
    @Mock private Store store;
    @Mock private Company company;

    private VerifactuAdminReviewReadService service;

    @BeforeEach
    void setUp() {
        service = new VerifactuAdminReviewReadService(
                organization, reads, properties, clockMonitor, environment,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void defectiveRecordsAreScopedFilteredAndUseInclusiveStoreDates() {
        scope();
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        var expected = new VerifactuAdminDefectiveRecordPage(List.of(), 1, 25, 0, 0);
        when(reads.findDefectiveRecords(
                COMPANY_ID,
                STORE_ID,
                Instant.parse("2026-06-30T23:00:00Z"),
                Instant.parse("2026-07-31T23:00:00Z"),
                FiscalSubmissionStatus.RECHAZADO,
                FiscalDocumentType.F2,
                FiscalRecordOperation.ALTA,
                "T-10",
                1,
                25)).thenReturn(expected);

        var result = service.defectiveRecords(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                FiscalSubmissionStatus.RECHAZADO,
                FiscalDocumentType.F2,
                FiscalRecordOperation.ALTA,
                " T-10 ",
                1,
                25);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void rejectsNonDefectiveStatusesAndInvalidPaginationBeforeReadingScope() {
        assertThatThrownBy(() -> service.defectiveRecords(
                null, null, FiscalSubmissionStatus.ACEPTADO,
                null, null, null, 0, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
        assertThatThrownBy(() -> service.defectiveRecords(
                null, null, null, null, null, null, -1, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page");
        assertThatThrownBy(() -> service.defectiveRecords(
                LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 1),
                null, null, null, null, 0, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dateFrom");

        verify(organization, never()).currentStore();
    }

    @Test
    void attemptsHideWhetherARecordIsMissingOrOutsideTheStore() {
        scope();
        var recordId = UUID.randomUUID();
        when(reads.recordExists(COMPANY_ID, STORE_ID, recordId)).thenReturn(false);

        assertThatThrownBy(() -> service.attempts(recordId, 0, 25))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("Registro fiscal no encontrado");

        verify(reads, never()).findAttempts(COMPANY_ID, STORE_ID, recordId, 0, 25);
    }

    @Test
    void diagnosticsArePassiveScopedAndSanitizeUnavailableRuntimeDetails() {
        scope();
        var lastAttempt = new VerifactuAdminDiagnosticEvent(
                NOW.minusSeconds(60), FiscalSubmissionStatus.RECHAZADO);
        when(reads.findLastAttempt(COMPANY_ID, STORE_ID)).thenReturn(lastAttempt);
        when(properties.current()).thenThrow(new IllegalStateException("secret path"));
        when(clockMonitor.current()).thenThrow(new IllegalStateException("database secret"));
        when(environment.getProperty("tpv.verifactu.worker-enabled", Boolean.class, false))
                .thenReturn(true);

        var result = service.diagnostics();

        assertThat(result.endpointConfigured()).isFalse();
        assertThat(result.endpointMode()).isNull();
        assertThat(result.workerEnabled()).isTrue();
        assertThat(result.lastAttempt()).isEqualTo(lastAttempt);
        assertThat(result.clock().warningCode()).isEqualTo("CLOCK_STATUS_UNAVAILABLE");
        assertThat(result.observedAt()).isEqualTo(NOW);
    }

    private void scope() {
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(STORE_ID);
        when(store.getEmpresa()).thenReturn(company);
        when(company.getId()).thenReturn(COMPANY_ID);
    }
}
