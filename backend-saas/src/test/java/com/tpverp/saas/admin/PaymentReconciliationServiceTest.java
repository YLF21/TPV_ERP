package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class PaymentReconciliationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-08T18:00:00Z"), ZoneOffset.UTC);

    @Test
    void rejectsDuplicateReferenceBeforeAttemptingInsert() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(null);

        assertThatThrownBy(() -> service(jdbc).create(UUID.randomUUID(), request(null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("referencia externa");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void constraintFailureDoesNotIssueDiagnosticQueriesInAbortedTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(null);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataIntegrityViolationException("constraint"));

        assertThatThrownBy(() -> service(jdbc).create(UUID.randomUUID(), request(null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("restriccion de integridad");

        verify(jdbc).queryForObject(anyString(), eq(Boolean.class), any(Object[].class));
    }

    private PaymentReconciliationService service(JdbcTemplate jdbc) {
        return new PaymentReconciliationService(jdbc, mock(PaymentReconciliationAdapter.class), CLOCK);
    }

    private CreatePaymentReconciliationRequest request(UUID paymentId) {
        return new CreatePaymentReconciliationRequest(paymentId, "MANUAL_BANK", "BANK-2026-001",
                "10.00", "EUR", CLOCK.instant(), "Conciliacion de prueba");
    }
}
