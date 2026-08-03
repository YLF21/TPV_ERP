package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "configuracion_devolucion_tienda")
public class StoreReturnConfiguration {

    @Id
    @Column(name = "tienda_id")
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StoreReturnPolicy politica = StoreReturnPolicy.REFUND_ALLOWED;

    @Version
    private long version;

    protected StoreReturnConfiguration() {
    }

    public StoreReturnConfiguration(UUID storeId) {
        this.storeId = Objects.requireNonNull(storeId, "storeId");
    }

    public UUID getStoreId() {
        return storeId;
    }

    public StoreReturnPolicy getPolicy() {
        return politica;
    }

    public void update(StoreReturnPolicy policy) {
        this.politica = Objects.requireNonNull(policy, "policy");
    }
}
