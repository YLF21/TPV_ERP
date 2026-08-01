package com.tpverp.backend.cash;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashCloseWithdrawalIdempotencyRepository
        extends JpaRepository<CashCloseWithdrawalIdempotency, UUID> {

    @Query(value = """
            select 1
              from (
                    select pg_advisory_xact_lock(
                        hashtextextended(:lockKey, 0)
                    )
              ) locked
            """, nativeQuery = true)
    Integer lockIdempotencyKey(@Param("lockKey") String lockKey);
}
