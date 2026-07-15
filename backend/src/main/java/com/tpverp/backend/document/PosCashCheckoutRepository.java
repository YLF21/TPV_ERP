package com.tpverp.backend.document;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PosCashCheckoutRepository extends JpaRepository<PosCashCheckout, UUID> {
    @Modifying
    @Query(value = """
        INSERT INTO pos_cash_checkout
          (id,checkout_id,company_id,store_id,terminal_id,user_id,request_hash,status,
           created_at,updated_at,version)
        VALUES (:id,:checkoutId,:companyId,:storeId,:terminalId,:userId,:requestHash,
                'PENDING',:now,:now,0)
        ON CONFLICT (company_id,store_id,terminal_id,user_id,checkout_id) DO NOTHING
        """, nativeQuery = true)
    int reserve(
            @Param("id") UUID id,
            @Param("checkoutId") UUID checkoutId,
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("terminalId") UUID terminalId,
            @Param("userId") UUID userId,
            @Param("requestHash") String requestHash,
            @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select c from PosCashCheckout c
        where c.checkoutId=:checkoutId and c.companyId=:companyId and c.storeId=:storeId
          and c.terminalId=:terminalId and c.userId=:userId
        """)
    Optional<PosCashCheckout> findScopedForUpdate(
            @Param("checkoutId") UUID checkoutId,
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("terminalId") UUID terminalId,
            @Param("userId") UUID userId);
}
