package com.tpverp.backend.party.loyalty.bootstrap;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver.BootstrapContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_wallet_bootstrap_worker_state")
public class MemberWalletBootstrapWorkerState {

    @Id
    @Column(name = "tienda_id", nullable = false)
    private UUID localStoreId;
    @Column(name = "empresa_id", nullable = false)
    private UUID localCompanyId;
    @Column(name = "saas_empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "saas_tienda_id", nullable = false)
    private UUID storeId;
    @Column(name = "active_bootstrap_id")
    private UUID activeBootstrapId;
    @Column(name = "attempts", nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "last_error", length = 1000)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected MemberWalletBootstrapWorkerState() {
    }

    public MemberWalletBootstrapWorkerState(BootstrapContext context, Instant now) {
        localStoreId = context.localStoreId();
        localCompanyId = context.localCompanyId();
        companyId = context.companyId();
        storeId = context.storeId();
        createdAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
        nextAttemptAt = now;
    }

    public void refreshContext(BootstrapContext context, Instant now) {
        boolean changed = !localCompanyId.equals(context.localCompanyId())
                || !companyId.equals(context.companyId())
                || !storeId.equals(context.storeId());
        localCompanyId = context.localCompanyId();
        companyId = context.companyId();
        storeId = context.storeId();
        updatedAt = now;
        if (changed) {
            activeBootstrapId = null;
            attempts = 0;
            lastError = null;
            nextAttemptAt = now;
        }
    }

    public void trackBootstrap(UUID bootstrapId, Instant now) {
        activeBootstrapId = Objects.requireNonNull(bootstrapId, "bootstrapId");
        updatedAt = now;
    }

    public void clearBootstrap(Instant now) {
        activeBootstrapId = null;
        recordSuccess(now);
    }

    public void recordSuccess(Instant now) {
        attempts = 0;
        lastError = null;
        nextAttemptAt = now;
        updatedAt = now;
    }

    public void recordFailure(String error, Instant now) {
        attempts++;
        lastError = trim(error);
        int exponent = Math.min(Math.max(attempts - 1, 0), 6);
        long delay = Math.min(3600L, 60L * (1L << exponent));
        nextAttemptAt = now.plus(delay, ChronoUnit.SECONDS);
        updatedAt = now;
    }

    public boolean isDue(Instant now) {
        return nextAttemptAt == null || !nextAttemptAt.isAfter(now);
    }

    private static String trim(String value) {
        String result = value == null || value.isBlank()
                ? "Error de bootstrap sin detalle"
                : value.trim();
        return result.length() <= 1000 ? result : result.substring(0, 1000);
    }

    public UUID getActiveBootstrapId() {
        return activeBootstrapId;
    }

    public UUID getStoreId() {
        return storeId;
    }
}
