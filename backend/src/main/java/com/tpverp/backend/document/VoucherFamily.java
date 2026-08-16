package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "vale_familia", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tienda_origen_id", "consecutivo"}),
        @UniqueConstraint(columnNames = {"empresa_id", "identificador"})
})
public class VoucherFamily {

    public static final int MAX_SEQUENCE = 999_999;

    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "tienda_origen_id", nullable = false)
    private UUID originStoreId;
    @Column(name = "consecutivo", nullable = false)
    private int consecutive;
    @Column(name = "identificador", nullable = false, length = 10)
    private String identifier;
    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    protected VoucherFamily() {
    }

    public VoucherFamily(
            UUID companyId,
            UUID originStoreId,
            String originStoreCode,
            int consecutive,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.originStoreId = Objects.requireNonNull(originStoreId, "originStoreId");
        if (originStoreCode == null || !originStoreCode.matches("[0-9]{3}")
                || "000".equals(originStoreCode)) {
            throw new IllegalArgumentException("voucher_family_store_code_invalid");
        }
        if (consecutive < 1 || consecutive > MAX_SEQUENCE) {
            throw new IllegalArgumentException("voucher_family_sequence_invalid");
        }
        this.consecutive = consecutive;
        this.identifier = originStoreCode + "-" + String.format(
                java.util.Locale.ROOT, "%06d", consecutive);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID originStoreId() {
        return originStoreId;
    }

    public int consecutive() {
        return consecutive;
    }

    public String identifier() {
        return identifier;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
