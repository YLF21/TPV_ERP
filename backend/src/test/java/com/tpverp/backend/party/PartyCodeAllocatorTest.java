package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Tienda;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PartyCodeAllocatorTest {

    @Test
    void formateaCodigosLocalesDeClienteYMember() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Tienda store = PartyTestData.store(PartyTestData.company());
        when(jdbc.queryForObject(any(String.class), eq(Long.class), any(), any()))
                .thenReturn(1L, 2L);
        PartyCodeAllocator allocator = new PartyCodeAllocator(jdbc);

        assertThat(allocator.nextClient(store)).isEqualTo("C-001-000001");
        assertThat(allocator.nextMember(store)).isEqualTo("M-001-000002");
    }

    @Test
    void formateaCodigosEmpresarialesDeProveedorYComercial() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var company = PartyTestData.company();
        when(jdbc.queryForObject(any(String.class), eq(Long.class), any(), any()))
                .thenReturn(1L, 2L);
        PartyCodeAllocator allocator = new PartyCodeAllocator(jdbc);

        assertThat(allocator.nextSupplier(company)).isEqualTo("S-000001");
        assertThat(allocator.nextCommercial(company)).isEqualTo("CO-000002");
    }

    @Test
    void reservesConsecutiveClientBlock() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Tienda store = PartyTestData.store(PartyTestData.company());
        when(jdbc.queryForObject(any(String.class), eq(Long.class), any(), any(), eq(3)))
                .thenReturn(5L);

        assertThat(new PartyCodeAllocator(jdbc).nextClients(store, 3))
                .containsExactly("C-001-000003", "C-001-000004", "C-001-000005");
    }
}
