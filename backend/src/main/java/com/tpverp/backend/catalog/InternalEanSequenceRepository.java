package com.tpverp.backend.catalog;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class InternalEanSequenceRepository {

    private final EntityManager entityManager;

    public InternalEanSequenceRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public long next(UUID storeId, InternalEanFormat format) {
        var values = entityManager.createNativeQuery("""
                        insert into secuencia_ean_interno (
                            tienda_id, formato, ultimo_numero)
                        values (:storeId, :format, 0)
                        on conflict (tienda_id, formato) do update
                        set ultimo_numero = secuencia_ean_interno.ultimo_numero + 1
                        where secuencia_ean_interno.ultimo_numero < :maximum
                        returning ultimo_numero
                        """)
                .setParameter("storeId", storeId)
                .setParameter("format", format.name())
                .setParameter("maximum", format.maximumSequence())
                .getResultList();
        if (values.isEmpty()) {
            throw new IllegalStateException("internal_ean_sequence_exhausted");
        }
        return ((Number) values.getFirst()).longValue();
    }
}
