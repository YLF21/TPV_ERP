package com.tpverp.saas.loyalty;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaasMemberBalanceAccountRepository
        extends JpaRepository<SaasMemberBalanceAccount, UUID> {

    boolean existsByCompanyId(UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select account
            from SaasMemberBalanceAccount account
            where account.companyId = :companyId and account.memberId = :memberId
            """)
    Optional<SaasMemberBalanceAccount> findForUpdate(
            @Param("companyId") UUID companyId,
            @Param("memberId") UUID memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select account
            from SaasMemberBalanceAccount account
            where account.companyId = :companyId
            order by account.memberId
            """)
    List<SaasMemberBalanceAccount> findCompanyAccountsForUpdate(
            @Param("companyId") UUID companyId);

    default List<SaasMemberBalanceAccount> resetPointsForBootstrap(UUID companyId) {
        List<SaasMemberBalanceAccount> accounts = findCompanyAccountsForUpdate(companyId);
        accounts.forEach(account -> account.replacePoints(BigDecimal.ZERO, BigDecimal.ZERO));
        return accounts;
    }

    List<SaasMemberBalanceAccount> findByCompanyIdOrderByMemberIdAsc(UUID companyId);

    @Query(value = """
            select member_id as memberId,
                   points as points,
                   points_debt as pointsDebt,
                   official_revision as officialRevision,
                   updated_at as updatedAt
            from saas_member_balance_account
            where company_id = :companyId
              and official_revision > :afterRevision
            order by official_revision, member_id::text
            limit :limit
            """, nativeQuery = true)
    List<OfficialPointsRow> findOfficialFeed(
            @Param("companyId") UUID companyId,
            @Param("afterRevision") long afterRevision,
            @Param("limit") int limit);

    @Query(value = """
            select member_id as memberId,
                   points as points,
                   points_debt as pointsDebt,
                   official_revision as officialRevision,
                   updated_at as updatedAt
            from saas_member_balance_account
            where company_id = :companyId and member_id = :memberId
            """, nativeQuery = true)
    Optional<OfficialPointsRow> findOfficialAccount(
            @Param("companyId") UUID companyId,
            @Param("memberId") UUID memberId);

    interface OfficialPointsRow {
        UUID getMemberId();
        BigDecimal getPoints();
        BigDecimal getPointsDebt();
        long getOfficialRevision();
        Instant getUpdatedAt();
    }

    @Modifying
    @Query(value = """
            INSERT INTO saas_member_wallet_projection_lock(lock_key)
            VALUES (:lockKey)
            ON CONFLICT (lock_key) DO NOTHING
            """, nativeQuery = true)
    void ensureProjectionLock(@Param("lockKey") String lockKey);

    @Query(value = """
            SELECT lock_key
            FROM saas_member_wallet_projection_lock
            WHERE lock_key = :lockKey
            FOR UPDATE
            """, nativeQuery = true)
    String lockProjectionKey(@Param("lockKey") String lockKey);
}
