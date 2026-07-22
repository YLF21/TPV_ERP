package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "venta_aparcada_recuperacion")
public class ParkedSaleRecovery {

    public enum Status { CLAIMED, ACKNOWLEDGED }

    @Id
    @Column(name = "recovery_id")
    private UUID recoveryId;
    @Column(name = "venta_aparcada_id", nullable = false, unique = true)
    private UUID parkedSaleId;
    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "usuario_id", nullable = false)
    private UUID userId;
    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16)
    private Status status;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "documento", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> document;
    @Column(name = "comentario", length = 500)
    private String comment;
    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;
    @Column(name = "confirmado_en")
    private Instant acknowledgedAt;
    @Version
    private long version;

    protected ParkedSaleRecovery() {}

    public ParkedSaleRecovery(
            UUID recoveryId, ParkedSale sale, UUID companyId, UUID userId, Instant createdAt) {
        this.recoveryId = Objects.requireNonNull(recoveryId, "recoveryId");
        this.parkedSaleId = Objects.requireNonNull(sale, "sale").getId();
        this.storeId = sale.getTiendaId();
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.status = Status.CLAIMED;
        this.document = new LinkedHashMap<>(sale.documentSnapshot());
        this.comment = sale.getComment();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public ParkedSaleOpened opened() {
        return new ParkedSaleOpened(ParkedSale.documentCommand(document), comment);
    }

    public void acknowledge(Instant now) {
        if (status == Status.ACKNOWLEDGED) return;
        status = Status.ACKNOWLEDGED;
        acknowledgedAt = Objects.requireNonNull(now, "now");
    }

    public boolean matches(UUID saleId, UUID store, UUID company) {
        return parkedSaleId.equals(saleId) && storeId.equals(store) && companyId.equals(company);
    }

    public UUID getRecoveryId() { return recoveryId; }
    public UUID getParkedSaleId() { return parkedSaleId; }
    public UUID getStoreId() { return storeId; }
    public UUID getCompanyId() { return companyId; }
    public Status getStatus() { return status; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
}
