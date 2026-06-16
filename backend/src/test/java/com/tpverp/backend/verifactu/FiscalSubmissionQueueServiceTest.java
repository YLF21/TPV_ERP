package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
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

    private Empresa company;
    private Tienda store;
    private FiscalSubmissionQueueService queue;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        company = new Empresa("B12345674", "Empresa", address);
        store = new Tienda(company, "001", "Tienda", address, "hash",
                "Atlantic/Canary", "EUR", "es-ES");
        lenient().when(organization.currentCompany()).thenReturn(company);
        lenient().when(organization.currentStore()).thenReturn(store);
        queue = new FiscalSubmissionQueueService(
                states, records, organization, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listsPendingRecordsForCurrentStoreInQueueOrder() {
        var pending = state(FiscalSubmissionStatus.PENDIENTE);
        var sent = state(FiscalSubmissionStatus.ENVIADO);
        var otherStore = state(FiscalSubmissionStatus.PENDIENTE);
        when(states.findAllByStatusInOrderByUpdatedAtAsc(List.of(
                FiscalSubmissionStatus.PENDIENTE,
                FiscalSubmissionStatus.ENVIADO)))
                .thenReturn(List.of(pending, sent, otherStore));
        when(records.findById(pending.getRecordId()))
                .thenReturn(Optional.of(record(pending.getRecordId(), store.getId(), 1)));
        when(records.findById(sent.getRecordId()))
                .thenReturn(Optional.of(record(sent.getRecordId(), store.getId(), 2)));
        when(records.findById(otherStore.getRecordId()))
                .thenReturn(Optional.of(record(otherStore.getRecordId(), UUID.randomUUID(), 3)));

        var result = queue.pending();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(FiscalSubmissionQueueItem::sequence)
                .containsExactly(1L, 2L);
    }

    @Test
    void claimsFirstPendingRecordAndMarksItSending() {
        var pending = state(FiscalSubmissionStatus.PENDIENTE);
        when(states.findAllByStatusInOrderByUpdatedAtAsc(List.of(
                FiscalSubmissionStatus.PENDIENTE,
                FiscalSubmissionStatus.ENVIADO)))
                .thenReturn(List.of(pending));
        when(records.findById(pending.getRecordId()))
                .thenReturn(Optional.of(record(pending.getRecordId(), store.getId(), 1)));
        when(states.findById(pending.getRecordId())).thenReturn(Optional.of(pending));
        when(states.save(pending)).thenReturn(pending);

        var claimed = queue.claimNext();

        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().state().getStatus())
                .isEqualTo(FiscalSubmissionStatus.ENVIANDO);
        assertThat(claimed.orElseThrow().record().getNumber())
                .isEqualTo("FV-001-26-000001");
    }

    @Test
    void returnsEmptyWhenQueueHasNoCurrentStoreRecords() {
        when(states.findAllByStatusInOrderByUpdatedAtAsc(List.of(
                FiscalSubmissionStatus.PENDIENTE,
                FiscalSubmissionStatus.ENVIADO)))
                .thenReturn(List.of());

        assertThat(queue.claimNext()).isEmpty();
    }

    private FiscalSubmissionState state(FiscalSubmissionStatus status) {
        return new FiscalSubmissionState(UUID.randomUUID(), status, NOW.minusSeconds(60));
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
