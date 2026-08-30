package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps a replay operation id to the canonical receipt owned by a return
 * document.  A replay must remain observable by its own operation id without
 * creating a second receipt (and therefore without debiting twice).
 */
@Entity
@Table(name = "saas_member_balance_retention_receipt_alias")
public class SaasMemberBalanceRetentionReceiptAlias {

    @Id
    @Column(name = "operation_id")
    private UUID operationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_operation_id", nullable = false)
    private SaasMemberBalanceRetentionReceipt receipt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected SaasMemberBalanceRetentionReceiptAlias() {
    }

    public SaasMemberBalanceRetentionReceiptAlias(
            UUID operationId, SaasMemberBalanceRetentionReceipt receipt, Instant now) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.receipt = Objects.requireNonNull(receipt, "receipt");
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public UUID getOperationId() {
        return operationId;
    }

    public SaasMemberBalanceRetentionReceipt getReceipt() {
        return receipt;
    }
}
