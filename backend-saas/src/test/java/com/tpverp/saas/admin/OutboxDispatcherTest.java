package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@SuppressWarnings({"rawtypes", "unchecked"})
class OutboxDispatcherTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void securityDispatcherClaimsBeforeDeliveryAndUsesStableIdempotencyKey() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IntegrationSecretCipher cipher = new IntegrationSecretCipher(KEY);
        ResultSet rs = mock(ResultSet.class);
        UUID id = UUID.randomUUID();
        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getString("idempotency_key")).thenReturn("notification-1");
        when(rs.getString("event_type")).thenReturn("PASSWORD_RESET_REQUESTED");
        when(rs.getString("realm")).thenReturn("admin");
        when(rs.getString("username_key")).thenReturn("safe-admin");
        when(rs.getString("encrypted_payload")).thenReturn(cipher.encrypt("one-time-token"));
        answerClaim(jdbc, rs);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        AtomicReference<SecurityNotificationChannel.SecurityNotification> delivered = new AtomicReference<>();
        SecurityNotificationChannel channel = notification -> { delivered.set(notification); return true; };

        int count = new SecurityNotificationDispatcher(
                jdbc, cipher, channel, CLOCK, Duration.ofMinutes(15), Duration.ofMinutes(2), 8)
                .dispatchPending();

        assertThat(count).isEqualTo(1);
        assertThat(delivered.get().oneTimeToken()).isEqualTo("one-time-token");
        assertThat(delivered.get().idempotencyKey()).isEqualTo("notification-1");
    }

    @Test
    void integrationDispatcherClaimsBeforeDeliveryAndPassesProviderIdempotency() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IntegrationSecretCipher cipher = new IntegrationSecretCipher(KEY);
        ResultSet rs = mock(ResultSet.class);
        UUID runId = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();
        when(rs.getObject("id", UUID.class)).thenReturn(runId);
        when(rs.getObject("integration_id", UUID.class)).thenReturn(integrationId);
        when(rs.getString("idempotency_key")).thenReturn("key-1");
        when(rs.getString("payload")).thenReturn("{\"value\":1}");
        when(rs.getString("integration_type")).thenReturn("WEBHOOK");
        when(rs.getString("target_url")).thenReturn("https://example.test/hook");
        when(rs.getString("api_key_encrypted")).thenReturn(cipher.encrypt("api-secret"));
        answerClaim(jdbc, rs);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        AtomicReference<IntegrationDeliveryChannel.IntegrationDelivery> delivered = new AtomicReference<>();
        IntegrationDeliveryChannel channel = delivery -> { delivered.set(delivery); return true; };

        int count = new IntegrationOutboxDispatcher(
                jdbc, cipher, channel, CLOCK, Duration.ofMinutes(5), Duration.ofMinutes(2), 8)
                .dispatchPending();

        assertThat(count).isEqualTo(1);
        assertThat(delivered.get().apiKey()).isEqualTo("api-secret");
        assertThat(delivered.get().payload()).isEqualTo("{\"value\":1}");
        assertThat(delivered.get().idempotencyKey()).isEqualTo("key-1");
    }

    private static void answerClaim(JdbcTemplate jdbc, ResultSet rs) {
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }}
