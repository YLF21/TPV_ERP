package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FiscalSubmissionQueueServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");

    @Mock
    private FiscalSubmissionStateRepository states;
    @Mock
    private FiscalRecordRepository records;
    @Mock
    private CurrentOrganization organization;

    private Company company;
    private Store store;
    private FiscalSubmissionQueueService queue;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        company = new Company("B12345674", "Company", address);
        store = new Store(company, "001", "Store", address, "hash",
                "Atlantic/Canary", "EUR", "es-ES");
        lenient().when(organization.currentCompany()).thenReturn(company);
        lenient().when(organization.currentStore()).thenReturn(store);
        queue = new FiscalSubmissionQueueService(
                states, records, organization, Clock.fixed(NOW, ZoneOffset.UTC),
                new VerifactuDefectClassifier());
    }

    @Test
    void listsPendingRecordsForCurrentStoreInQueueOrder() {
        var pending = state(FiscalSubmissionStatus.PENDIENTE);
        var sent = state(FiscalSubmissionStatus.ENVIADO);
        var rejected = incident(FiscalSubmissionStatus.RECHAZADO, "NIF_INVALIDO", "NIF incorrecto");
        var otherStore = state(FiscalSubmissionStatus.PENDIENTE);
        when(states.findAllByStatusInOrderByUpdatedAtAsc(List.of(
                FiscalSubmissionStatus.PENDIENTE,
                FiscalSubmissionStatus.ENVIANDO,
                FiscalSubmissionStatus.ENVIADO,
                FiscalSubmissionStatus.RECHAZADO)))
                .thenReturn(List.of(pending, sent, rejected, otherStore));
        when(records.findById(pending.getRecordId()))
                .thenReturn(Optional.of(record(pending.getRecordId(), store.getId(), 1)));
        when(records.findById(sent.getRecordId()))
                .thenReturn(Optional.of(record(sent.getRecordId(), store.getId(), 2)));
        when(records.findById(rejected.getRecordId()))
                .thenReturn(Optional.of(record(rejected.getRecordId(), store.getId(), 3)));
        when(records.findById(otherStore.getRecordId()))
                .thenReturn(Optional.of(record(otherStore.getRecordId(), UUID.randomUUID(), 4)));

        var result = queue.pending();

        assertThat(result).hasSize(3);
        assertThat(result).extracting(FiscalSubmissionQueueItem::sequence)
                .containsExactly(1L, 2L, 3L);
        assertThat(result.get(2).errorCode()).isEqualTo("NIF_INVALIDO");
        assertThat(result.get(2).error()).isEqualTo("NIF incorrecto");
        assertThat(result.get(2).updatedAt()).isEqualTo(rejected.getUpdatedAt());
    }

    @Test
    void claimsFirstPendingRecordAndMarksItSending() {
        var pending = state(FiscalSubmissionStatus.PENDIENTE);
        when(states.findAllByStatusInOrderByUpdatedAtAsc(List.of(
                FiscalSubmissionStatus.PENDIENTE,
                FiscalSubmissionStatus.ENVIANDO,
                FiscalSubmissionStatus.ENVIADO)))
                .thenReturn(List.of(pending));
        when(records.findById(pending.getRecordId()))
                .thenReturn(Optional.of(record(pending.getRecordId(), store.getId(), 1)));
        when(states.findForUpdate(pending.getRecordId())).thenReturn(Optional.of(pending));
        when(states.save(pending)).thenReturn(pending);

        var claimed = queue.claimNext();

        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().state().getStatus())
                .isEqualTo(FiscalSubmissionStatus.ENVIANDO);
        assertThat(claimed.orElseThrow().record().getNumber())
                .isEqualTo("FV-001-26-000001");
    }

    @Test
    void workerClaimsOldestRecordAcrossStores() {
        var pending = state(FiscalSubmissionStatus.PENDIENTE);
        var otherStoreId = UUID.randomUUID();
        when(states.findAllByStatusInOrderByUpdatedAtAsc(List.of(
                FiscalSubmissionStatus.PENDIENTE,
                FiscalSubmissionStatus.ENVIANDO,
                FiscalSubmissionStatus.ENVIADO)))
                .thenReturn(List.of(pending));
        when(records.findById(pending.getRecordId()))
                .thenReturn(Optional.of(record(pending.getRecordId(), otherStoreId, 1)));
        when(states.findForUpdate(pending.getRecordId())).thenReturn(Optional.of(pending));
        when(states.save(pending)).thenReturn(pending);

        assertThat(queue.claimNext()).isPresent();
        assertThat(pending.getStatus()).isEqualTo(FiscalSubmissionStatus.ENVIANDO);
    }

    @Test
    void returnsEmptyWhenQueueHasNoCurrentStoreRecords() {
        when(states.findAllByStatusInOrderByUpdatedAtAsc(List.of(
                FiscalSubmissionStatus.PENDIENTE,
                FiscalSubmissionStatus.ENVIANDO,
                FiscalSubmissionStatus.ENVIADO)))
                .thenReturn(List.of());

        assertThat(queue.claimNext()).isEmpty();
    }

    @Test
    void waitsOneHourBeforeRetryingCommunicationFailure() {
        var sent = new FiscalSubmissionState(
                UUID.randomUUID(), FiscalSubmissionStatus.ENVIADO, NOW.minusSeconds(3599));
        when(states.findAllByStatusInOrderByUpdatedAtAsc(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(sent));
        when(records.findById(sent.getRecordId()))
                .thenReturn(Optional.of(record(sent.getRecordId(), store.getId(), 1)));

        assertThat(queue.claimNext()).isEmpty();
    }

    @Test
    void retriesCommunicationFailureAfterOneHour() {
        var sent = new FiscalSubmissionState(
                UUID.randomUUID(), FiscalSubmissionStatus.ENVIADO, NOW.minusSeconds(3600));
        when(states.findAllByStatusInOrderByUpdatedAtAsc(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(sent));
        when(records.findById(sent.getRecordId()))
                .thenReturn(Optional.of(record(sent.getRecordId(), store.getId(), 1)));
        when(states.findForUpdate(sent.getRecordId())).thenReturn(Optional.of(sent));
        when(states.save(sent)).thenReturn(sent);

        assertThat(queue.claimNext()).isPresent();
    }

    @Test
    void recoversSendingStateStuckForOneHour() {
        var sending = new FiscalSubmissionState(
                UUID.randomUUID(), FiscalSubmissionStatus.ENVIANDO, NOW.minusSeconds(3600));
        when(states.findAllByStatusInOrderByUpdatedAtAsc(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(sending));
        when(records.findById(sending.getRecordId()))
                .thenReturn(Optional.of(record(sending.getRecordId(), store.getId(), 1)));
        when(states.findForUpdate(sending.getRecordId())).thenReturn(Optional.of(sending));
        when(states.save(sending)).thenReturn(sending);

        assertThat(queue.claimNext()).isPresent();
    }

    @Test
    void rejectedRecordsRemainVisibleButAreNeverRetriedAutomatically() {
        when(states.findAllByStatusInOrderByUpdatedAtAsc(List.of(
                FiscalSubmissionStatus.PENDIENTE,
                FiscalSubmissionStatus.ENVIANDO,
                FiscalSubmissionStatus.ENVIADO)))
                .thenReturn(List.of());

        assertThat(queue.claimNext()).isEmpty();

        verify(states).findAllByStatusInOrderByUpdatedAtAsc(List.of(
                FiscalSubmissionStatus.PENDIENTE,
                FiscalSubmissionStatus.ENVIANDO,
                FiscalSubmissionStatus.ENVIADO));
    }

    @Test
    void manualRetryIsScopedVersionedAndMarksTheRecordSending() {
        var sent = state(FiscalSubmissionStatus.ENVIADO);
        var record = record(sent.getRecordId(), store.getId(), 1);
        when(records.findByIdAndCompanyIdAndStoreId(
                sent.getRecordId(), company.getId(), store.getId()))
                .thenReturn(Optional.of(record));
        when(states.findForUpdate(sent.getRecordId())).thenReturn(Optional.of(sent));
        when(states.save(sent)).thenReturn(sent);

        var claimed = queue.claimForManualRetry(sent.getRecordId(), 0);

        assertThat(claimed.record()).isSameAs(record);
        assertThat(claimed.state().getStatus()).isEqualTo(FiscalSubmissionStatus.ENVIANDO);
    }

    @Test
    void manualRetryOnlyAcceptsRetryableTechnicalDefects() {
        var retryable = incident(
                FiscalSubmissionStatus.DEFECTUOSO,
                "INVALID_AEAT_RESPONSE",
                "Respuesta AEAT no interpretable");
        when(records.findByIdAndCompanyIdAndStoreId(
                retryable.getRecordId(), company.getId(), store.getId()))
                .thenReturn(Optional.of(record(retryable.getRecordId(), store.getId(), 1)));
        when(states.findForUpdate(retryable.getRecordId())).thenReturn(Optional.of(retryable));
        when(states.save(retryable)).thenReturn(retryable);

        assertThat(queue.claimForManualRetry(retryable.getRecordId(), 0).state().getStatus())
                .isEqualTo(FiscalSubmissionStatus.ENVIANDO);

        var invalidXsd = incident(
                FiscalSubmissionStatus.DEFECTUOSO, "INVALID_XSD", "XML invalido");
        when(records.findByIdAndCompanyIdAndStoreId(
                invalidXsd.getRecordId(), company.getId(), store.getId()))
                .thenReturn(Optional.of(record(invalidXsd.getRecordId(), store.getId(), 2)));
        when(states.findForUpdate(invalidXsd.getRecordId())).thenReturn(Optional.of(invalidXsd));

        assertThatThrownBy(() -> queue.claimForManualRetry(invalidXsd.getRecordId(), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no admite reintento manual");
    }

    @Test
    void manualRetryRejectsStaleVersionAndNonRetryableStatus() {
        var accepted = state(FiscalSubmissionStatus.ACEPTADO);
        when(records.findByIdAndCompanyIdAndStoreId(
                accepted.getRecordId(), company.getId(), store.getId()))
                .thenReturn(Optional.of(record(accepted.getRecordId(), store.getId(), 1)));
        when(states.findForUpdate(accepted.getRecordId())).thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> queue.claimForManualRetry(accepted.getRecordId(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cambio");
        assertThatThrownBy(() -> queue.claimForManualRetry(accepted.getRecordId(), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no admite reintento manual");
    }

    @Test
    void manualRetryDoesNotRevealWhetherAnotherStoreOwnsTheRecord() {
        var recordId = UUID.randomUUID();
        when(records.findByIdAndCompanyIdAndStoreId(
                recordId, company.getId(), store.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> queue.claimForManualRetry(recordId, 0))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("Registro fiscal no encontrado");
    }

    private FiscalSubmissionState state(FiscalSubmissionStatus status) {
        return new FiscalSubmissionState(UUID.randomUUID(), status, NOW.minusSeconds(60));
    }

    private FiscalSubmissionState incident(
            FiscalSubmissionStatus status, String errorCode, String error) {
        var state = state(status);
        state.markIncident(status, errorCode, error, NOW.minusSeconds(30));
        return state;
    }

    private FiscalRecord record(UUID recordId, UUID storeId, long sequence) {
        var record = new FiscalRecord(
                UUID.randomUUID(), company.getId(), UUID.randomUUID(), storeId,
                UUID.randomUUID(), sequence, FiscalRecordOperation.ALTA, FiscalDocumentType.F1,
                "FV-001-26-000001", LocalDate.of(2026, 6, 16), NOW,
                "Atlantic/Canary", "B12345674", new BigDecimal("2.10"),
                new BigDecimal("12.10"), sequence == 1 ? null : "A".repeat(64),
                "B".repeat(64), "C".repeat(64), Map.of("numero", "FV-001-26-000001"),
                "VERIFACTU-1", "AEAT-SHA256-1", "TPV-ERP-0.0.1");
        set(record, "id", recordId);
        return record;
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
