package com.tpverp.backend.document;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class VoucherFamilyNumberAllocator {

    private final JdbcTemplate jdbc;

    public VoucherFamilyNumberAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int next(UUID storeId) {
        var allocated = jdbc.queryForList("""
                insert into vale_familia_contador (tienda_id, ultimo_consecutivo)
                values (?, 1)
                on conflict (tienda_id) do update
                   set ultimo_consecutivo = vale_familia_contador.ultimo_consecutivo + 1
                 where vale_familia_contador.ultimo_consecutivo < ?
                returning ultimo_consecutivo
                """, Integer.class, storeId, VoucherFamily.MAX_SEQUENCE);
        if (allocated.isEmpty()) {
            throw new IllegalStateException("voucher_family_sequence_exhausted");
        }
        return allocated.getFirst();
    }
}
