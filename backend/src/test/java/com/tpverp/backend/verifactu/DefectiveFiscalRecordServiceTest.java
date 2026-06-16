package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
class DefectiveFiscalRecordServiceTest {

    @Mock
    private FiscalSubmissionStateRepository states;
    @Mock
    private FiscalRecordRepository records;
    @Mock
    private CurrentOrganization organization;

    private Empresa company;
    private Tienda store;
    private DefectiveFiscalRecordService service;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        company = new Empresa("B12345674", "Empresa", address);
        store = new Tienda(company, "001", "Tienda", address, "hash",
                "Atlantic/Canary", "EUR", "es-ES");
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        service = new DefectiveFiscalRecordService(states, records, organization);
    }

    @Test
    void listsDefectiveRecordsForCurrentStoreOnly() {
        var rejected = state(FiscalSubmissionStatus.RECHAZADO, "NIF_INVALIDO", "NIF no valido");
        var otherStore = state(FiscalSubmissionStatus.DEFECTUOSO, "CAMPO", "Dato invalido");
        when(states.findAllByStatusInOrderByUpdatedAtDesc(List.of(
                FiscalSubmissionStatus.RECHAZADO,
                FiscalSubmissionStatus.DEFECTUOSO,
                FiscalSubmissionStatus.ACEPTADO_CON_ERRORES)))
                .thenReturn(List.of(rejected, otherStore));
        when(records.findById(rejected.getRecordId()))
                .thenReturn(Optional.of(record(rejected.getRecordId(), store.getId())));
        when(records.findById(otherStore.getRecordId()))
                .thenReturn(Optional.of(record(otherStore.getRecordId(), UUID.randomUUID())));
        var result = service.list();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().status()).isEqualTo(FiscalSubmissionStatus.RECHAZADO);
        assertThat(result.getFirst().errorCode()).isEqualTo("NIF_INVALIDO");
        assertThat(result.getFirst().number()).isEqualTo("FV-001-26-000001");
    }

    private FiscalSubmissionState state(
            FiscalSubmissionStatus status, String code, String message) {
        var value = new FiscalSubmissionState(UUID.randomUUID(), status,
                Instant.parse("2026-06-16T10:00:00Z"));
        set(value, "lastErrorCode", code);
        set(value, "lastError", message);
        return value;
    }

    private FiscalRecord record(UUID recordId, UUID storeId) {
        var record = new FiscalRecord(
                UUID.randomUUID(), company.getId(), UUID.randomUUID(), storeId,
                UUID.randomUUID(), 1, FiscalRecordOperation.ALTA, FiscalDocumentType.F1,
                "FV-001-26-000001", LocalDate.of(2026, 6, 16),
                Instant.parse("2026-06-16T10:00:00Z"), "Atlantic/Canary",
                "B12345674", new BigDecimal("2.10"), new BigDecimal("12.10"),
                null, "A".repeat(64), "B".repeat(64), Map.of("numero", "FV-001-26-000001"),
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
