package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    }

    @Test
    void createsPendingCorrectionAndQueuesItForImmediateSubmission() {
        givenOriginal();
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
        givenOriginal();
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

    @Test
    void rejectsTechnicalDefectInsteadOfCreatingAdministrativeCorrection() {
        givenOriginal();
        state.markIncident(
                FiscalSubmissionStatus.DEFECTUOSO,
                "INVALID_XSD",
                "XML fiscal invalido",
                NOW);

        assertThatThrownBy(() -> service().correct(
                original.getId(),
                new FiscalCorrectionRequest("Correccion", null, null, "Venta"),
                authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El registro fiscal no admite subsanacion");

        verify(fiscalRecords, never()).registerCorrection(any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void rejectsCancellationRecordEvenWhenItsSubmissionWasRejected() {
        when(records.findByIdAndCompanyIdAndStoreId(original.getId(), companyId, storeId))
                .thenReturn(Optional.of(original));
        set(original, "operation", FiscalRecordOperation.ANULACION);

        assertThatThrownBy(() -> service().correct(
                original.getId(),
                new FiscalCorrectionRequest("Correccion", null, null, "Venta"),
                authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El registro fiscal no admite subsanacion");

        verify(fiscalRecords, never()).registerCorrection(any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void recoversSameAttemptAfterTheOriginalWasMarkedSubsanado() {
        givenOriginal();
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(organization.currentUser(authentication)).thenReturn(user);
        var request = new FiscalCorrectionRequest(
                "NIF incorrecto", "B12345674", "Cliente SL", null, "corr-1");
        var persistedSnapshot = new FiscalCorrectionSnapshot().apply(
                original.getSnapshot(), request, original.getId(), userId, NOW, true);
        persistedSnapshot = new java.util.LinkedHashMap<>(persistedSnapshot);
        persistedSnapshot.put("subsanacionObjetivoId", original.getId().toString());
        var correction = record(companyId, storeId, 2, persistedSnapshot);
        var accepted = new FiscalSubmissionState(
                correction.getId(), FiscalSubmissionStatus.ACEPTADO, NOW);
        state.mark(FiscalSubmissionStatus.SUBSANADO, NOW);
        when(records.findFirstByDocumentIdAndOperationOrderBySequenceAsc(
                original.getDocumentId(), FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(original));
        when(records.findCorrectionByOriginalIdAndIdempotencyKey(
                original.getId(), "corr-1")).thenReturn(Optional.of(correction));
        when(states.findById(correction.getId())).thenReturn(Optional.of(accepted));

        var result = service().correct(original.getId(), request, authentication);

        assertThat(result.id()).isEqualTo(correction.getId());
        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.ACEPTADO);
        verify(fiscalRecords, never()).registerCorrection(any(), any());
    }

    @Test
    void rejectsSameKeyWithDifferentCorrectionPayload() {
        givenOriginal();
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(organization.currentUser(authentication)).thenReturn(user);
        var persistedSnapshot = new FiscalCorrectionSnapshot().apply(
                original.getSnapshot(),
                new FiscalCorrectionRequest("NIF incorrecto", "B12345674", "Cliente SL",
                        null, "corr-1"),
                original.getId(), userId, NOW, true);
        var correction = record(companyId, storeId, 2, persistedSnapshot);
        when(records.findFirstByDocumentIdAndOperationOrderBySequenceAsc(
                original.getDocumentId(), FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(original));
        when(records.findCorrectionByOriginalIdAndIdempotencyKey(
                original.getId(), "corr-1")).thenReturn(Optional.of(correction));

        assertThatThrownBy(() -> service().correct(
                original.getId(),
                new FiscalCorrectionRequest("Otro motivo", "B12345674", "Cliente SL",
                        null, "corr-1"),
                authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("subsanacion_idempotency_conflict");
    }

    @Test
    void rejectsDifferentPayloadWhileAnotherCorrectionIsPending() {
        givenOriginal();
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(organization.currentUser(authentication)).thenReturn(user);
        var pending = record(companyId, storeId, 2, Map.of(
                "subsanacion", "S", "subsanacionMotivo", "Anterior",
                "subsanacionObjetivoId", original.getId().toString(),
                "subsanacionIdempotencyKey", "old-key",
                "impuestoTotal", new BigDecimal("2.10"), "total", new BigDecimal("12.10")));
        when(records.findLatestCorrectionByOriginalId(original.getId()))
                .thenReturn(Optional.of(pending));
        when(states.findById(pending.getId())).thenReturn(Optional.of(new FiscalSubmissionState(
                pending.getId(), FiscalSubmissionStatus.PENDIENTE, NOW)));

        assertThatThrownBy(() -> service().correct(original.getId(),
                new FiscalCorrectionRequest("Nueva", null, null, "Venta", "new-key"),
                authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("subsanacion_pending_conflict");
    }

    @Test
    void allowsNewKeyAfterRejectedCorrection() {
        givenOriginal();
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(organization.currentUser(authentication)).thenReturn(user);
        var rejected = record(companyId, storeId, 2, Map.of(
                "subsanacion", "S", "subsanacionMotivo", "Anterior",
                "subsanacionObjetivoId", original.getId().toString(),
                "subsanacionIdempotencyKey", "old-key",
                "impuestoTotal", new BigDecimal("2.10"), "total", new BigDecimal("12.10")));
        when(records.findLatestCorrectionByOriginalId(original.getId()))
                .thenReturn(Optional.of(rejected));
        when(states.findById(rejected.getId())).thenReturn(Optional.of(new FiscalSubmissionState(
                rejected.getId(), FiscalSubmissionStatus.RECHAZADO, NOW)));
        var created = record(companyId, storeId, 3, original.getSnapshot());
        when(fiscalRecords.registerCorrection(any(), any())).thenReturn(created);

        var result = service().correct(original.getId(),
                new FiscalCorrectionRequest("Nueva", null, null, "Venta", "new-key"),
                authentication);

        assertThat(result.id()).isEqualTo(created.getId());
        verify(fiscalRecords).registerCorrection(any(), any());
    }

    @Test
    void allowsSuccessiveCorrectionAgainstAcceptedWithErrorsTarget() {
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(organization.currentUser(authentication)).thenReturn(user);
        var first = record(companyId, storeId, 2, Map.of(
                "subsanacion", "S", "subsanacionMotivo", "Primera",
                "subsanacionObjetivoId", original.getId().toString(),
                "subsanacionIdempotencyKey", "first",
                "impuestoTotal", new BigDecimal("2.10"), "total", new BigDecimal("12.10")));
        var next = record(companyId, storeId, 3, Map.of(
                "subsanacion", "S", "subsanacionMotivo", "Segunda",
                "subsanacionObjetivoId", first.getId().toString(),
                "subsanacionIdempotencyKey", "second",
                "impuestoTotal", new BigDecimal("2.10"), "total", new BigDecimal("12.10")));
        when(records.findByIdAndCompanyIdAndStoreId(first.getId(), companyId, storeId))
                .thenReturn(Optional.of(first));
        when(records.findFirstByDocumentIdAndOperationOrderBySequenceAsc(
                first.getDocumentId(), FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(original));
        when(states.findForUpdate(original.getId())).thenReturn(Optional.of(state));
        when(states.findById(first.getId())).thenReturn(Optional.of(new FiscalSubmissionState(
                first.getId(), FiscalSubmissionStatus.ACEPTADO_CON_ERRORES, NOW)));
        when(records.findCorrectionByOriginalIdAndIdempotencyKey(
                original.getId(), "second")).thenReturn(Optional.empty());
        when(records.findLatestCorrectionByOriginalId(original.getId()))
                .thenReturn(Optional.of(first));
        when(fiscalRecords.registerCorrection(any(), any())).thenReturn(next);

        var result = service().correct(first.getId(),
                new FiscalCorrectionRequest("Segunda", null, null, "Venta", "second"),
                authentication);

        assertThat(result.id()).isEqualTo(next.getId());
        verify(fiscalRecords).registerCorrection(eq(original), any());
    }

    @Test
    void blocksNewRootCorrectionWhileLatestCorrectionIsTechnicallyDefective() {
        givenOriginal();
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(organization.currentUser(authentication)).thenReturn(user);
        var defective = record(companyId, storeId, 2, Map.of(
                "subsanacion", "S", "subsanacionMotivo", "Anterior",
                "subsanacionObjetivoId", original.getId().toString(),
                "subsanacionIdempotencyKey", "old-key",
                "impuestoTotal", new BigDecimal("2.10"), "total", new BigDecimal("12.10")));
        when(records.findLatestCorrectionByOriginalId(original.getId()))
                .thenReturn(Optional.of(defective));
        defectiveState(defective, "INVALID_XSD");

        assertThatThrownBy(() -> service().correct(original.getId(),
                new FiscalCorrectionRequest("Nueva", null, null, "Venta", "new-key"),
                authentication))
                .isInstanceOf(FiscalCorrectionPendingConflictException.class);
        verify(fiscalRecords, never()).registerCorrection(any(), any());
    }

    @Test
    void replaysSameKeyForDefectiveCorrectionWithoutCreatingAnotherRecord() {
        givenOriginal();
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(organization.currentUser(authentication)).thenReturn(user);
        var request = new FiscalCorrectionRequest("Anterior", null, null, "Venta", "same-key");
        var defectiveSnapshot = new FiscalCorrectionSnapshot().apply(
                original.getSnapshot(), request, original.getId(), userId, NOW, true);
        defectiveSnapshot = new java.util.LinkedHashMap<>(defectiveSnapshot);
        defectiveSnapshot.put("subsanacionObjetivoId", original.getId().toString());
        var defective = record(companyId, storeId, 2, defectiveSnapshot);
        when(records.findCorrectionByOriginalIdAndIdempotencyKey(
                original.getId(), "same-key")).thenReturn(Optional.of(defective));
        when(states.findById(defective.getId())).thenReturn(Optional.of(new FiscalSubmissionState(
                defective.getId(), FiscalSubmissionStatus.DEFECTUOSO, NOW)));

        var result = service().correct(original.getId(), request, authentication);

        assertThat(result.id()).isEqualTo(defective.getId());
        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.DEFECTUOSO);
        verify(fiscalRecords, never()).registerCorrection(any(), any());
    }

    @Test
    void replaysLegacyDefectiveCorrectionWithoutCreatingAnotherRecord() {
        givenOriginal();
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(organization.currentUser(authentication)).thenReturn(user);
        var request = new FiscalCorrectionRequest("Anterior", null, null, "Venta");
        var snapshot = new java.util.LinkedHashMap<>(new FiscalCorrectionSnapshot().apply(
                original.getSnapshot(), request, original.getId(), userId, NOW, true));
        snapshot.put("subsanacionObjetivoId", original.getId().toString());
        var defective = record(companyId, storeId, 2, snapshot);
        when(records.findLatestCorrectionByOriginalId(original.getId()))
                .thenReturn(Optional.of(defective));
        defectiveState(defective, "INVALID_AEAT_RESPONSE");

        var result = service().correct(original.getId(), request, authentication);

        assertThat(result.id()).isEqualTo(defective.getId());
        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.DEFECTUOSO);
        verify(fiscalRecords, never()).registerCorrection(any(), any());
    }

    @Test
    void rejectsNewCorrectionTargetingLatestTechnicalDefectEvenWithNewKey() {
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(organization.currentUser(authentication)).thenReturn(user);
        var defective = record(companyId, storeId, 2, original.getSnapshot());
        set(defective, "documentId", original.getDocumentId());
        when(records.findByIdAndCompanyIdAndStoreId(defective.getId(), companyId, storeId))
                .thenReturn(Optional.of(defective));
        when(records.findFirstByDocumentIdAndOperationOrderBySequenceAsc(
                original.getDocumentId(), FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(original));
        when(states.findForUpdate(original.getId())).thenReturn(Optional.of(state));
        when(records.findLatestCorrectionByOriginalId(original.getId()))
                .thenReturn(Optional.of(defective));
        defectiveState(defective, "INVALID_AEAT_RESPONSE");

        assertThatThrownBy(() -> service().correct(defective.getId(),
                new FiscalCorrectionRequest("Nueva", null, null, "Venta", "new-key"),
                authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El registro fiscal no admite subsanacion");
        verify(fiscalRecords, never()).registerCorrection(any(), any());
    }

    private void defectiveState(FiscalRecord record, String errorCode) {
        var defective = new FiscalSubmissionState(
                record.getId(), FiscalSubmissionStatus.DEFECTUOSO, NOW);
        defective.markIncident(FiscalSubmissionStatus.DEFECTUOSO, errorCode, "error", NOW);
        when(states.findById(record.getId())).thenReturn(Optional.of(defective));
    }

    private void givenOriginal() {
        when(records.findByIdAndCompanyIdAndStoreId(original.getId(), companyId, storeId))
                .thenReturn(Optional.of(original));
        when(states.findForUpdate(original.getId())).thenReturn(Optional.of(state));
    }

    private FiscalCorrectionService service() {
        return new FiscalCorrectionService(
                records, states, fiscalRecords, new FiscalCorrectionSnapshot(),
                organization, events, Clock.fixed(NOW, ZoneOffset.UTC),
                new VerifactuDefectClassifier());
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

    private static void set(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
