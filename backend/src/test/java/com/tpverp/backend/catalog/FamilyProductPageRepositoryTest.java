package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class FamilyProductPageRepositoryTest {
    @Mock NamedParameterJdbcTemplate jdbc;

    private FamilyProductPageRepository repository;

    @BeforeEach
    void setUp() {
        repository = new FamilyProductPageRepository(jdbc);
        lenient().when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<RowMapper<FamilyProductPageRepository.FamilyProductPageRow>>any()))
                .thenReturn(List.of());
    }

    @Test
    void buildsAllSixOrdersFromAllowlistedFragmentsAndKeepsNullsLast() {
        var cases = List.of(
                new SortCase("code", "asc", "coalesce(code.valor, barcode.valor)", "asc"),
                new SortCase("code", "desc", "coalesce(code.valor, barcode.valor)", "desc"),
                new SortCase("name", "asc", "lower(p.nombre)", "asc"),
                new SortCase("name", "desc", "lower(p.nombre)", "desc"),
                new SortCase("salePrice", "asc", "sale.importe", "asc"),
                new SortCase("salePrice", "desc", "sale.importe", "desc"));

        for (SortCase sort : cases) {
            clearInvocations(jdbc);
            repository.findPage(
                    UUID.randomUUID(), FamilyProductPageRepository.ScopeKind.FAMILY,
                    UUID.randomUUID(), sort.sortBy(), sort.direction(), null, 26);

            var sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc).query(
                    sql.capture(),
                    any(MapSqlParameterSource.class),
                    org.mockito.ArgumentMatchers
                            .<RowMapper<FamilyProductPageRepository.FamilyProductPageRow>>any());
            assertThat(sql.getValue())
                    .contains(sort.expression() + " as sort_value")
                    .contains("order by (" + sort.expression() + " is null) asc, "
                            + sort.expression() + " " + sort.sqlDirection()
                            + ", p.id " + sort.sqlDirection())
                    .contains("p.tienda_id = :storeId")
                    .contains("p.familia_id = :scopeId");
        }
    }

    @Test
    void buildsMatchingKeysetPredicatesForNonNullAndNullDescendingCursors() {
        UUID cursorId = UUID.randomUUID();
        repository.findPage(
                UUID.randomUUID(), FamilyProductPageRepository.ScopeKind.SUBFAMILY,
                UUID.randomUUID(), "salePrice", "desc",
                new FamilyProductPageRepository.FamilyProductPageCursor(
                        false, "20.00", cursorId),
                10);
        var sql = ArgumentCaptor.forClass(String.class);
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(
                sql.capture(),
                parameters.capture(),
                org.mockito.ArgumentMatchers
                        .<RowMapper<FamilyProductPageRepository.FamilyProductPageRow>>any());
        assertThat(sql.getValue())
                .contains("p.subfamilia_id = :scopeId")
                .contains("sale.importe is null or sale.importe < :cursorValue")
                .contains("sale.importe = :cursorValue and p.id < :cursorId");
        assertThat(parameters.getValue().getValue("cursorValue"))
                .isEqualTo(new java.math.BigDecimal("20.00"));

        clearInvocations(jdbc);
        repository.findPage(
                UUID.randomUUID(), FamilyProductPageRepository.ScopeKind.FAMILY,
                UUID.randomUUID(), "code", "desc",
                new FamilyProductPageRepository.FamilyProductPageCursor(true, null, cursorId),
                10);
        sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(
                sql.capture(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<RowMapper<FamilyProductPageRepository.FamilyProductPageRow>>any());
        assertThat(sql.getValue())
                .contains("coalesce(code.valor, barcode.valor) is null"
                        + " and p.id < :cursorId")
                .doesNotContain(":cursorValue");
    }

    @Test
    void rejectsUnknownSqlFragmentsBeforeCallingJdbc() {
        clearInvocations(jdbc);

        assertThatThrownBy(() -> repository.findPage(
                UUID.randomUUID(), FamilyProductPageRepository.ScopeKind.FAMILY,
                UUID.randomUUID(), "name desc; drop table producto", "asc", null, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findPage(
                UUID.randomUUID(), FamilyProductPageRepository.ScopeKind.FAMILY,
                UUID.randomUUID(), "name", "asc nulls first", null, 10))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void mapsAlphanumericImageIdentifiersAsText() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        var result = mock(java.sql.ResultSet.class);
        when(result.getObject("sort_value")).thenReturn("A001");
        when(result.getString("sort_value")).thenReturn("A001");
        when(result.getObject("id", UUID.class)).thenReturn(productId);
        when(result.getLong("version")).thenReturn(3L);
        when(result.getString("imagen_id")).thenReturn("N");
        when(result.getString("imagen_hash")).thenReturn("hash");
        when(result.getString("code")).thenReturn("A001");
        when(result.getString("barcode")).thenReturn("8400000000000");
        when(result.getString("nombre")).thenReturn("PRODUCTO");
        when(result.getObject("familia_id", UUID.class)).thenReturn(familyId);
        when(result.getBoolean("activo")).thenReturn(true);
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<RowMapper<FamilyProductPageRepository.FamilyProductPageRow>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<FamilyProductPageRepository.FamilyProductPageRow> mapper =
                            invocation.getArgument(2);
                    return List.of(mapper.mapRow(result, 0));
                });

        var rows = repository.findPage(
                UUID.randomUUID(), FamilyProductPageRepository.ScopeKind.FAMILY,
                familyId, "code", "asc", null, 10);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(productId);
            assertThat(row.imageId()).isEqualTo("N");
        });
        verify(result).getString("imagen_id");
    }

    private record SortCase(
            String sortBy, String direction, String expression, String sqlDirection) {
    }
}
