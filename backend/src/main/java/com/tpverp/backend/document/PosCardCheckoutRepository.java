package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PosCardCheckoutRepository extends JpaRepository<PosCardCheckout, UUID> {
    @Modifying
    @Query(value="""
        INSERT INTO pos_card_checkout
          (id,terminal_id,schema_version,request_hash,document_snapshot,total,status,gateway_owner,gateway_lease_until,
           attempt,creado_en,actualizado_en,version)
        VALUES (:id,:terminalId,1,:hash,CAST(:snapshot AS jsonb),:total,'PENDING',:owner,:leaseUntil,1,:now,:now,0)
        ON CONFLICT (id) DO NOTHING
        """, nativeQuery=true)
    int reserve(@Param("id") UUID id, @Param("terminalId") UUID terminalId,
            @Param("hash") String hash, @Param("snapshot") String snapshot, @Param("total") BigDecimal total,
            @Param("owner") UUID owner, @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil);

    @Modifying
    @Query(value="""
        UPDATE pos_card_checkout SET ticket_owner=:owner,ticket_lease_until=:leaseUntil,
          actualizado_en=:now,version=version+1
        WHERE id=:id AND status='APPROVED' AND documento_id IS NULL
          AND (ticket_owner IS NULL OR ticket_lease_until < :now)
        """, nativeQuery=true)
    int claimTicket(@Param("id") UUID id, @Param("owner") UUID owner,
            @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);
}
