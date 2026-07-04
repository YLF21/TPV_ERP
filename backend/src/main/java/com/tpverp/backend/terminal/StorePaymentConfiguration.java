package com.tpverp.backend.terminal;

import com.tpverp.backend.organization.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "configuracion_pago_tienda")
public class StorePaymentConfiguration {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tienda_id", nullable = false, unique = true)
    private Store store;

    @Column(name = "card_manual_enabled", nullable = false)
    private boolean cardManualEnabled = true;

    @Column(name = "card_manual_reference_required", nullable = false)
    private boolean cardManualReferenceRequired;

    @Column(name = "integrated_card_enabled", nullable = false)
    private boolean integratedCardEnabled = true;

    @Column(name = "manual_fallback_enabled", nullable = false)
    private boolean manualFallbackEnabled = true;

    @Column(name = "allowed_payment_terminal_providers", nullable = false, columnDefinition = "text")
    private String allowedPaymentTerminalProviders = "REDSYS_TPV_PC,PAYTEF,PAYCOMET,GLOBAL_PAYMENTS";

    @Version
    private long version;

    protected StorePaymentConfiguration() {
    }

    public StorePaymentConfiguration(Store store) {
        this.id = UUID.randomUUID();
        this.store = Objects.requireNonNull(store, "store");
    }

    public Store getStore() {
        return store;
    }

    public boolean isCardManualEnabled() {
        return cardManualEnabled;
    }

    public boolean isCardManualReferenceRequired() {
        return cardManualReferenceRequired;
    }

    public boolean isIntegratedCardEnabled() {
        return integratedCardEnabled;
    }

    public boolean isManualFallbackEnabled() {
        return manualFallbackEnabled;
    }

    public String getAllowedPaymentTerminalProviders() {
        return allowedPaymentTerminalProviders;
    }
}
