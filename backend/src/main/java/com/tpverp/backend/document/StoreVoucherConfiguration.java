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
@Table(name = "configuracion_vale_tienda")
public class StoreVoucherConfiguration {

    public static final int DEFAULT_VALIDITY_DAYS = 365;
    public static final int MAX_VALIDITY_DAYS = 36500;

    @Id
    @Column(name = "tienda_id")
    private UUID storeId;

    @Column(name = "vigencia_dias", nullable = false)
    private int validityDays = DEFAULT_VALIDITY_DAYS;

    @Enumerated(EnumType.STRING)
    @Column(name = "modo_caducidad", nullable = false, length = 16)
    private VoucherExpirationMode expirationMode = VoucherExpirationMode.DAYS;

    @Version
    private long version;

    protected StoreVoucherConfiguration() {
    }

    public StoreVoucherConfiguration(UUID storeId) {
        this.storeId = Objects.requireNonNull(storeId, "storeId");
    }

    public UUID storeId() {
        return storeId;
    }

    public int validityDays() {
        return validityDays;
    }

    public VoucherExpirationMode expirationMode() {
        return expirationMode;
    }

    public void update(VoucherExpirationMode mode, int days) {
        expirationMode = Objects.requireNonNull(mode, "expirationMode");
        if (days < 1 || days > MAX_VALIDITY_DAYS) {
            throw new IllegalArgumentException("voucher_validity_days_out_of_range");
        }
        validityDays = days;
    }

    public java.time.LocalDate expirationFor(java.time.LocalDate issuedOn) {
        Objects.requireNonNull(issuedOn, "issuedOn");
        return expirationMode == VoucherExpirationMode.NEVER
                ? null
                : issuedOn.plusDays(validityDays);
    }
}
