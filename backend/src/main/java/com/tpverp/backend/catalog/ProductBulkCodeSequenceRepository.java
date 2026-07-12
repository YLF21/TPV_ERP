package com.tpverp.backend.catalog;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class ProductBulkCodeSequenceRepository {

    private final EntityManager entityManager;

    public ProductBulkCodeSequenceRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public int next(UUID storeId, LocalDate date) {
        java.util.List<?> values = entityManager.createNativeQuery("""
                        insert into producto_edicion_masiva_secuencia (
                            tienda_id, fecha, ultimo_numero)
                        values (:storeId, :date, 1)
                        on conflict (tienda_id, fecha) do update
                        set ultimo_numero = producto_edicion_masiva_secuencia.ultimo_numero + 1
                        where producto_edicion_masiva_secuencia.ultimo_numero < 999
                        returning ultimo_numero
                        """)
                .setParameter("storeId", storeId)
                .setParameter("date", date)
                .getResultList();
        if (values.isEmpty()) {
            throw new IllegalStateException("Se ha alcanzado el limite diario de listas");
        }
        Number value = (Number) values.getFirst();
        return value.intValue();
    }
}
