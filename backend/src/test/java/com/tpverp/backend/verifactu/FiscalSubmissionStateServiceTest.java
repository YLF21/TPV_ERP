package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
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

@ExtendWith(MockitoExtension.class)
class FiscalSubmissionStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");

    @Mock
    private FiscalSubmissionStateRepository states;
    @Mock
    private FiscalRecordRepository records;
    @Mock
    private CurrentOrganization organization;

    private Empresa company;
    private Tienda store;
    private FiscalSubmissionState state;
    private FiscalSubmissionStateService service;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        company = new Empresa("B12345674", "Empresa", address);
        store = new Tienda(company, "001", "Tienda", address, "hash",
                "Atlantic/Canary", "EUR", "es-ES");
        state = new FiscalSubmissionState(
                UUID.randomUUID(), FiscalSubmissionStatus.PENDIENTE, NOW.minusSeconds(60));
        lenient().when(organization.currentCompany()).thenReturn(company);
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(states.findById(state.getRecordId())).thenReturn(Optional.of(state));
        lenient().when(records.findById(state.getRecordId()))
                .thenReturn(Optional.of(record(store.getId())));
        lenient().when(states.save(state)).thenReturn(state);
        service = new FiscalSubmissionStateService(
                states, records, organization, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void marksAcceptedAndClearsPreviousError() {
        service.markRejected(state.getRecordId(), "NIF", "NIF no valido");

        var accepted = service.markAccepted(state.getRecordId());

        assertThat(accepted.getStatus()).isEqualTo(FiscalSubmissionStatus.ACEPTADO);
        assertThat(accepted.getLastErrorCode()).isNull();
        assertThat(accepted.getLastError()).isNull();
        assertThat(accepted.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void storesAeAtRejectionWithoutBlockingSales() {
        var rejected = service.markRejected(
                state.getRecordId(), "NIF_INVALIDO", "El NIF no es correcto");

        assertThat(rejected.getStatus()).isEqualTo(FiscalSubmissionStatus.RECHAZADO);
        assertThat(rejected.getLastErrorCode()).isEqualTo("NIF_INVALIDO");
        assertThat(rejected.getLastError()).isEqualTo("El NIF no es correcto");
    }

    @Test
    void requiresErrorDetailsForVisibleIncidents() {
        assertThatThrownBy(() -> service.markDefective(state.getRecordId(), " ", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("error");

        verify(states, never()).save(state);
    }

    @Test
    void rejectsStateChangesForAnotherStore() {
        when(records.findById(state.getRecordId()))
                .thenReturn(Optional.of(record(UUID.randomUUID())));

        assertThatThrownBy(() -> service.markSending(state.getRecordId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registro fiscal");
    }

    private FiscalRecord record(UUID storeId) {
        return new FiscalRecord(
                UUID.randomUUID(), company.getId(), UUID.randomUUID(), storeId,
                UUID.randomUUID(), 1, FiscalRecordOperation.ALTA, FiscalDocumentType.F1,
                "FV-001-26-000001", LocalDate.of(2026, 6, 16), NOW,
                "Atlantic/Canary", "B12345674", new BigDecimal("2.10"),
                new BigDecimal("12.10"), null, "A".repeat(64), "B".repeat(64),
                Map.of("numero", "FV-001-26-000001"),
                "VERIFACTU-1", "AEAT-SHA256-1", "TPV-ERP-0.0.1");
    }
}
