package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "autorizacion_cambio_precio_venta")
public class TemporaryPriceAuthorizationGrant {

    @Id
    private UUID id;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;
    @Column(name = "terminal_id", nullable = false)
    private UUID terminalId;
    @Column(name = "operador_id", nullable = false)
    private UUID operatorId;
    @Column(name = "autorizador_id", nullable = false)
    private UUID authorizerId;
    @Column(name = "operador_nombre", nullable = false, length = 128)
    private String operatorName;
    @Column(name = "autorizador_nombre", nullable = false, length = 128)
    private String authorizerName;
    @Column(name = "delegada", nullable = false)
    private boolean delegated;
    @Column(name = "linea_carrito_id", nullable = false, length = 128)
    private String cartLineId;
    @Column(name = "producto_id", nullable = false)
    private UUID productId;
    @Column(name = "precio_unitario", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;
    @Column(name = "version_politica", nullable = false)
    private long policyVersion;
    @Column(name = "emitida_en", nullable = false)
    private Instant issuedAt;
    @Column(name = "expira_en", nullable = false)
    private Instant expiresAt;
    @Column(name = "origen_reserva_tipo", length = 48)
    private String claimSourceType;
    @Column(name = "origen_reserva_id")
    private UUID claimSourceId;
    @Column(name = "reservada_en")
    private Instant claimedAt;
    @Column(name = "consumida_en")
    private Instant consumedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long version;

    protected TemporaryPriceAuthorizationGrant() {
    }

    public TemporaryPriceAuthorizationGrant(
            String tokenHash,
            UUID companyId,
            UUID storeId,
            UUID terminalId,
            UUID operatorId,
            String operatorName,
            UUID authorizerId,
            String authorizerName,
            boolean delegated,
            String cartLineId,
            UUID productId,
            BigDecimal unitPrice,
            long policyVersion,
            Instant issuedAt,
            Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.tokenHash = required(tokenHash, "tokenHash");
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.operatorId = Objects.requireNonNull(operatorId, "operatorId");
        this.operatorName = required(operatorName, "operatorName");
        this.authorizerId = Objects.requireNonNull(authorizerId, "authorizerId");
        this.authorizerName = required(authorizerName, "authorizerName");
        this.delegated = delegated;
        this.cartLineId = required(cartLineId, "cartLineId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.unitPrice = positive(unitPrice);
        if (policyVersion < 0) throw new IllegalArgumentException("policyVersion");
        this.policyVersion = policyVersion;
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public void claim(
            UUID expectedCompanyId,
            UUID expectedStoreId,
            UUID expectedTerminalId,
            UUID expectedOperatorId,
            String expectedCartLineId,
            UUID expectedProductId,
            BigDecimal expectedUnitPrice,
            long expectedPolicyVersion,
            String sourceType,
            UUID sourceId,
            Instant now) {
        if (consumedAt != null) {
            throw new IllegalStateException("temporary_price_authorization_already_used");
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalStateException("temporary_price_authorization_expired");
        }
        if (!companyId.equals(expectedCompanyId)
                || !storeId.equals(expectedStoreId)
                || !terminalId.equals(expectedTerminalId)
                || !operatorId.equals(expectedOperatorId)
                || !cartLineId.equals(required(expectedCartLineId, "cartLineId"))
                || !productId.equals(expectedProductId)
                || unitPrice.compareTo(positive(expectedUnitPrice)) != 0
                || policyVersion != expectedPolicyVersion) {
            throw new IllegalStateException("temporary_price_authorization_mismatch");
        }
        var normalizedSourceType = required(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        if (claimSourceId != null) {
            if (claimSourceId.equals(sourceId) && claimSourceType.equals(normalizedSourceType)) {
                return;
            }
            throw new IllegalStateException("temporary_price_authorization_in_use");
        }
        claimSourceType = normalizedSourceType;
        claimSourceId = sourceId;
        claimedAt = now;
    }

    public void consume(String sourceType, UUID sourceId, Instant now) {
        requireClaim(sourceType, sourceId);
        if (consumedAt == null) consumedAt = Objects.requireNonNull(now, "now");
    }

    public void release(String sourceType, UUID sourceId) {
        requireClaim(sourceType, sourceId);
        if (consumedAt != null) {
            throw new IllegalStateException("temporary_price_authorization_already_used");
        }
        claimSourceType = null;
        claimSourceId = null;
        claimedAt = null;
    }

    private void requireClaim(String sourceType, UUID sourceId) {
        if (!Objects.equals(claimSourceType, sourceType)
                || !Objects.equals(claimSourceId, sourceId)) {
            throw new IllegalStateException("temporary_price_authorization_claim_mismatch");
        }
    }

    public UUID getId() { return id; }
    public UUID getOperatorId() { return operatorId; }
    public String getOperatorName() { return operatorName; }
    public UUID getAuthorizerId() { return authorizerId; }
    public String getAuthorizerName() { return authorizerName; }
    public boolean isDelegated() { return delegated; }
    public String getCartLineId() { return cartLineId; }
    public UUID getProductId() { return productId; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public long getPolicyVersion() { return policyVersion; }
    public Instant getExpiresAt() { return expiresAt; }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field);
        return value.trim();
    }

    private static BigDecimal positive(BigDecimal value) {
        var normalized = Money.euros(Objects.requireNonNull(value, "unitPrice"));
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("temporary_price_must_be_greater_than_zero");
        }
        return normalized;
    }
}
