package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class IntegrationPayloadMigrationTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void encryptsLegacyPayloadsInBoundedBatches() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        IntegrationSecretCipher cipher = new IntegrationSecretCipher(
                Base64.getEncoder().encodeToString(new byte[32]));
        UUID id = UUID.randomUUID();
        String legacyPayload = "{\"customer\":\"sensitive\"}";
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getString("payload")).thenReturn(legacyPayload);
        AtomicInteger queries = new AtomicInteger();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    if (queries.getAndIncrement() > 0) {
                        return List.of();
                    }
                    RowMapper mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());

        new IntegrationPayloadMigration(jdbc, transactions, cipher).run(null);

        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), values.capture());
        Object[] update = values.getValue();
        assertThat(update[0]).isInstanceOf(String.class);
        assertThat(cipher.decrypt((String) update[0])).isEqualTo(legacyPayload);
        assertThat(update[1]).isEqualTo(id);
        assertThat(update[2]).isEqualTo(legacyPayload);
    }
}
