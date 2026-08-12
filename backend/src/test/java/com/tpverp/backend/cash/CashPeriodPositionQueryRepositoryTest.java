package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class CashPeriodPositionQueryRepositoryTest {

    @Test
    void bindsTheHistoricalBoundaryAsAJdbcTimestamp() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var boundary = Instant.parse("2026-08-11T23:00:00Z");
        var storeId = UUID.randomUUID();
        when(jdbc.queryForObject(
                        anyString(),
                        any(MapSqlParameterSource.class),
                        eq(BigDecimal.class)))
                .thenReturn(new BigDecimal("12.34"));

        var result = new CashPeriodPositionQueryRepository(jdbc).positionAt(storeId, boundary);

        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForObject(anyString(), parameters.capture(), eq(BigDecimal.class));
        assertThat(parameters.getValue().getValue("storeId")).isEqualTo(storeId);
        assertThat(parameters.getValue().getValue("boundary"))
                .isEqualTo(Timestamp.from(boundary));
        assertThat(result).isEqualByComparingTo("12.34");
    }
}
