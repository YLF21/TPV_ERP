package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GiftReceiptLineRepository extends JpaRepository<GiftReceiptLine, UUID> {

    @Query(value = """
            select coalesce(sum(abs(line.cantidad)), 0)
            from documento_linea line
            join documento document on document.id = line.documento_id
            where line.ticket_regalo_linea_id = :giftReceiptLineId
              and document.estado = 'CONFIRMADO'
            """, nativeQuery = true)
    BigDecimal confirmedReturnedQuantity(
            @Param("giftReceiptLineId") UUID giftReceiptLineId);
}
