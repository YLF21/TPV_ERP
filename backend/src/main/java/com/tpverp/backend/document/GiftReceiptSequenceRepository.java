package com.tpverp.backend.document;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class GiftReceiptSequenceRepository {

    private final EntityManager entityManager;

    public GiftReceiptSequenceRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public int next(UUID storeId, LocalDate date) {
        var values = entityManager.createNativeQuery("""
                        insert into secuencia_ticket_regalo (
                            tienda_id, fecha, ultimo_numero)
                        values (:storeId, :date, 1)
                        on conflict (tienda_id, fecha) do update
                        set ultimo_numero = secuencia_ticket_regalo.ultimo_numero + 1
                        where secuencia_ticket_regalo.ultimo_numero < 99999
                        returning ultimo_numero
                        """)
                .setParameter("storeId", storeId)
                .setParameter("date", date)
                .getResultList();
        if (values.isEmpty()) {
            throw new IllegalStateException("gift_receipt_sequence_exhausted");
        }
        return ((Number) values.getFirst()).intValue();
    }
}
