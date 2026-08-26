package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.junit.jupiter.api.Test;

class FiscalRecordReadRepositoryContractTest {

    @Test
    void documentNumberIsAnEscapedCaseInsensitivePrefix() {
        assertThat(FiscalRecordReadRepository.documentNumberPrefix("  A%_\\B"))
                .isEqualTo("a\\%\\_\\\\b%");
    }

    @Test
    void exactNumberUsesEqualityAndCannotMatchARecordSuffix() {
        var jdbc = Mockito.mock(NamedParameterJdbcTemplate.class);
        Mockito.doReturn(List.of()).when(jdbc).query(
                Mockito.anyString(), Mockito.any(MapSqlParameterSource.class),
                Mockito.<RowMapper<FiscalRecordReadRepository.Row>>any());
        var repository = new FiscalRecordReadRepository(jdbc);

        repository.findCursorRows(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, "A-1", FiscalRecordNumberMatch.EXACT, null,
                50L, null, 3);

        var sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(jdbc).query(sql.capture(), Mockito.any(MapSqlParameterSource.class),
                Mockito.<RowMapper<FiscalRecordReadRepository.Row>>any());
        assertThat(sql.getValue()).contains("lower(record.serie_numero) = :documentNumber")
                .doesNotContain("documentNumberPrefix", " like ");
    }

    @Test
    void cursorQueryUsesKeysetWithoutCountOrOffset() {
        var jdbc = Mockito.mock(NamedParameterJdbcTemplate.class);
        Mockito.doReturn(List.of()).when(jdbc).query(
                Mockito.anyString(), Mockito.any(MapSqlParameterSource.class),
                Mockito.<RowMapper<FiscalRecordReadRepository.Row>>any());
        var repository = new FiscalRecordReadRepository(jdbc);

        repository.findCursorRows(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, "A-", null, 50L, null, 3);

        var sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(jdbc).query(sql.capture(), Mockito.any(MapSqlParameterSource.class),
                Mockito.<RowMapper<FiscalRecordReadRepository.Row>>any());
        assertThat(sql.getValue()).contains("record.secuencia <= :snapshotSequence")
                .contains("order by record.secuencia desc")
                .contains("like :documentNumberPrefix")
                .doesNotContain("count(*)", " offset ");
    }
}
