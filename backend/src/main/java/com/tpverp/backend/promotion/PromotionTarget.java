package com.tpverp.backend.promotion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "promocion_objetivo", uniqueConstraints = @UniqueConstraint(
        columnNames = {"promocion_id", "tipo", "objetivo_id"}))
public class PromotionTarget {

    @Id
    private UUID id;
    @Column(name = "promocion_id", nullable = false)
    private UUID promocionId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PromotionTargetType tipo;
    @Column(name = "objetivo_id", nullable = false)
    private UUID objetivoId;
    @Version
    private long version;

    protected PromotionTarget() {
    }

    public PromotionTarget(UUID promotionId, PromotionTargetType type, UUID targetId) {
        id = UUID.randomUUID();
        promocionId = Objects.requireNonNull(promotionId, "promotionId");
        tipo = Objects.requireNonNull(type, "type");
        objetivoId = Objects.requireNonNull(targetId, "targetId");
    }

    public UUID id() {
        return id;
    }

    public UUID promotionId() {
        return promocionId;
    }

    public PromotionTargetType type() {
        return tipo;
    }

    public UUID targetId() {
        return objetivoId;
    }
}
