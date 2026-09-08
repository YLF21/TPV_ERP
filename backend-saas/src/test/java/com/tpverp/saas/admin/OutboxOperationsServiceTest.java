package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class OutboxOperationsServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-08T18:00:00Z"), ZoneOffset.UTC);

    @Test
    void requeueSecurityResetsDeliveryAndAuditsReason() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        UUID id = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        new OutboxOperationsService(jdbc, audit, CLOCK)
                .requeueSecurity(id, "Incidencia del proveedor resuelta");

        verify(jdbc).update(contains("attempt_count = 0"), any(Object[].class));
        verify(audit).log("REQUEUE_SECURITY_OUTBOX", "SECURITY_NOTIFICATION_OUTBOX",
                id.toString(), "Incidencia del proveedor resuelta");
    }

    @Test
    void acknowledgeIntegrationRequiresFailedDelivery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> new OutboxOperationsService(
                jdbc, mock(AdminAuditService.class), CLOCK)
                .acknowledgeIntegration(UUID.randomUUID(), "Revisada por operaciones"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
    }

    @Test
    void doubleAcknowledgeIsCompareAndSetAndAuditsOnlyWinner() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        UUID id = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        OutboxOperationsService service = new OutboxOperationsService(jdbc, audit, CLOCK);

        service.acknowledgeSecurity(id, "Revisada por operaciones");
        assertThatThrownBy(() -> service.acknowledgeSecurity(id, "Segundo reconocimiento"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");

        verify(audit, times(1)).log("ACK_SECURITY_OUTBOX", "SECURITY_NOTIFICATION_OUTBOX",
                id.toString(), "Revisada por operaciones");
    }

    @Test
    void concurrentRequeueHasExactlyOneWinner() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        AtomicInteger updates = new AtomicInteger();
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> updates.getAndIncrement() == 0 ? 1 : 0);
        UUID id = UUID.randomUUID();
        OutboxOperationsService service = new OutboxOperationsService(jdbc, audit, CLOCK);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> runRequeue(service, id, start));
            var second = executor.submit(() -> runRequeue(service, id, start));
            start.countDown();
            List<Boolean> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(true, false);
        }
        verify(audit, times(1)).log("REQUEUE_SECURITY_OUTBOX", "SECURITY_NOTIFICATION_OUTBOX",
                id.toString(), "Incidencia concurrente resuelta");
    }

    @Test
    void rejectsUnsafePaginationInputsBeforeQuerying() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OutboxOperationsService service = new OutboxOperationsService(
                jdbc, mock(AdminAuditService.class), CLOCK);

        assertThatThrownBy(() -> service.failures("invalid", 50, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("channel");
        assertThatThrownBy(() -> service.failures(null, 101, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.failures(null, 50, "not-a-cursor"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cursor");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void failureListingDoesNotExposeProviderOrInternalErrorMessages() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        new OutboxOperationsService(jdbc, mock(AdminAuditService.class), CLOCK)
                .failures(null, 50, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("'DELIVERY_FAILED' as error")
                .doesNotContain("last_error as error")
                .doesNotContain("error_message) as error");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pageCursorPreservesPostgresTimestampPrecision() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet first = failure(UUID.randomUUID(), Instant.parse("2026-09-08T18:00:00.123456Z"));
        UUID secondId = UUID.randomUUID();
        Instant secondInstant = Instant.parse("2026-09-08T18:00:00.123789Z");
        ResultSet second = failure(secondId, secondInstant);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(first, 0), mapper.mapRow(second, 1));
        });

        OutboxFailurePageResponse page = new OutboxOperationsService(
                jdbc, mock(AdminAuditService.class), CLOCK).failures("security", 1, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNotBlank();
        String decoded = new String(Base64.getUrlDecoder().decode(page.nextCursor()), StandardCharsets.UTF_8);
        assertThat(decoded).startsWith("2026-09-08T18:00:00.123456Z|");
    }

    private ResultSet failure(UUID id, Instant failedAt) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getString("channel")).thenReturn("SECURITY");
        when(rs.getString("subject")).thenReturn("PASSWORD_RESET_REQUESTED");
        when(rs.getInt("attempts")).thenReturn(8);
        when(rs.getString("error")).thenReturn("CHANNEL_UNAVAILABLE");
        when(rs.getTimestamp("failed_at")).thenReturn(Timestamp.from(failedAt));
        return rs;
    }

    private boolean runRequeue(OutboxOperationsService service, UUID id, CountDownLatch start) {
        try {
            start.await(5, TimeUnit.SECONDS);
            service.requeueSecurity(id, "Incidencia concurrente resuelta");
            return true;
        } catch (ResponseStatusException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
