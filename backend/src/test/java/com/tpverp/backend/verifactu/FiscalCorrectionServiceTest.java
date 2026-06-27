package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class FiscalCorrectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-21T10:15:30Z");

    @Mock FiscalRecordRepository records;
    @Mock FiscalSubmissionStateRepository states;
    @Mock FiscalRecordService fiscalRecords;
    @Mock CurrentOrganization organization;
    @Mock ApplicationEventPublisher events;
    @Mock Authentication authentication;

    private UUID companyId;
    private UUID storeId;
    private UUID userId;
    private FiscalRecord original;
    private FiscalSubmissionState state;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        original = record(companyId, storeId, 1, Map.of(
                "baseTotal", new BigDecimal("10.00"),
                "impuestoTotal", new BigDecimal("2.10"),
                "total", new BigDecimal("12.10")));
        state = new FiscalSubmissionState(
                original.getId(), FiscalSubmissionStatus.RECHAZADO, NOW.minusSeconds(60));
        var company = mock(Company.class);
        var store = mock(Store.class);
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(records.findByIdAndCompanyIdAndStoreId(original.getId(), companyId, storeId))
                .thenReturn(Optional.of(original));
        when(states.findForUpdate(original.getId())).thenReturn(Optional.of(state));
    }

    @Test
    void createsPendingCorrectionAndQueuesItForImmediateSubmission() {
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(organization.currentUser(authentication)).thenReturn(user);
        var correction = record(companyId, storeId, 2, original.getSnapshot());
        when(fiscalRecords.registerCorrection(any(), any())).thenReturn(correction);

        var result = service().correct(
                original.getId(),
                new FiscalCorrectionRequest(
                        "NIF incorrecto", "B12345674", "Cliente SL", null),
                authentication);

        assertThat(result.id()).isEqualTo(correction.getId());
        assertThat(result.originalRecordId()).isEqualTo(original.getId());
        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.PENDIENTE);
        verify(events).publishEvent(new FiscalRecordQueuedEvent(correction.getId()));
    }

    @Test
    void rejectsNonDefectiveStateWithoutCreatingCorrection() {
        state.mark(FiscalSubmissionStatus.ACEPTADO, NOW);

        assertThatThrownBy(() -> service().correct(
                original.getId(),
                new FiscalCorrectionRequest("Correccion", null, null, "Venta"),
                authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El registro fiscal no admite subsanacion");

        verify(fiscalRecords, never()).registerCorrection(any(), any());
        verify(events, never()).publishEvent(any());
    }

    private FiscalCorrectionService service() {
        return new FiscalCorrectionService(
                records, states, fiscalRecords, new FiscalCorrectionSnapshot(),
                organization, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static FiscalRecord record(
            UUID companyId, UUID storeId, long sequence, Map<String, Object> snapshot) {
        return new FiscalRecord(
                UUID.randomUUID(), companyId, UUID.randomUUID(), storeId, UUID.randomUUID(),
                sequence, FiscalRecordOperation.ALTA, FiscalDocumentType.F2,
                "001-260621-000001", LocalDate.of(2026, 6, 21), NOW,
                "Atlantic/Canary", "B12345674", new BigDecimal("2.10"),
                new BigDecimal("12.10"), sequence == 1 ? null : "A".repeat(64),
                "B".repeat(64), "C".repeat(64), snapshot,
                "1.0", "SHA-256", "0.0.1");
    }
}
