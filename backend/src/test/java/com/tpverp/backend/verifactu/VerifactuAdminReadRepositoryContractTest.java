package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class VerifactuAdminReadRepositoryContractTest {

    @Test
    void reportsBoundedWindowAndTruncationAfterProbeOfOneMoreRow() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(
                anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(201L);

        var page = new VerifactuAdminReadRepository(jdbc).findSubmissions(
                UUID.randomUUID(), UUID.randomUUID(), null, null, null, null,
                null, null, null, 8, 25);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(200);
        assertThat(page.totalPages()).isEqualTo(8);
        assertThat(page.truncated()).isTrue();
        verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.contains("limit :queueProbeLimit"),
                any(MapSqlParameterSource.class), eq(Long.class));
    }

    @Test
    void keepsExactWindowUnmarkedWhenProbeFindsExactlyTwoHundredRows() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(
                anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(200L);

        var page = new VerifactuAdminReadRepository(jdbc).findSubmissions(
                UUID.randomUUID(), UUID.randomUUID(), null, null, null, null,
                null, null, null, 8, 25);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(200);
        assertThat(page.totalPages()).isEqualTo(8);
        assertThat(page.truncated()).isFalse();
    }
}
